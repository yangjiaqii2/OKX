# Telegram 交易通知实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从已提交的 `trade_event` 可靠生成幂等 Telegram 通知，并保证通知故障永不影响交易事务。

**Architecture:** `TradeEventService` 在同一数据库事务中写入通知 Outbox；定时 Dispatcher 用短 lease 领取到期任务，事务外通过 JDK `HttpClient` 调用 Telegram `sendMessage`，再落库 SENT/RETRY/FAILED。第一版仅出站通知和测试消息，不接收机器人交易命令。

**Tech Stack:** Java 17 HttpClient、Spring Boot、Spring Data JPA、MySQL/Flyway、JUnit 5、MockWebServer、Docker Compose。

## Global Constraints

- Telegram 失败不能回滚、阻塞或改变交易事件和订单状态。
- 唯一键固定为 `trade_event_id + channel`；已 SENT 事件不允许重复发送。
- Token 只存在服务端环境变量，不返回前端、不落事件表、不写日志。
- 除 429 外的 4xx 不重试；429/5xx/timeout 有上限退避，最多 5 次。
- 不实现任何入站交易命令。
- 当前脏工作区未经用户授权不创建 Git 提交。

---

### Task 1: Outbox 数据模型与同事务入队

**Files:**
- Create: `src/main/java/com/example/quant/notification/NotificationProperties.java`
- Create: `src/main/java/com/example/quant/notification/NotificationOutboxEntity.java`
- Create: `src/main/java/com/example/quant/notification/NotificationOutboxRepository.java`
- Create: `src/main/java/com/example/quant/notification/NotificationOutboxService.java`
- Create: `src/main/resources/db/migration/V25__telegram_notification_outbox.sql`
- Modify: `src/main/java/com/example/quant/QuantAssistantApplication.java`
- Modify: `src/main/java/com/example/quant/agent/event/TradeEventService.java`
- Test: `src/test/java/com/example/quant/notification/NotificationOutboxServiceTest.java`

**Interfaces:**
- Consumes: saved `TradeEventEntity`。
- Produces: `void enqueue(TradeEventEntity event)`，仅为 allowlist 事件创建 TELEGRAM Outbox。

- [ ] **Step 1: 写 migration 与失败测试**

```sql
CREATE TABLE notification_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trade_event_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL,
    lease_until TIMESTAMP NULL,
    last_http_status INT NULL,
    last_error VARCHAR(512),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_notification_event_channel (trade_event_id, channel),
    INDEX idx_notification_due (status, next_attempt_at)
);
```

测试同一事件 enqueue 两次只有一行、禁用通知不入队、`AUTO_CANDIDATE_SKIPPED` 不入队、`ENTRY_FILLED` 入队。

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -q -DskipTests=false -Dtest=NotificationOutboxServiceTest test`

- [ ] **Step 3: 实现配置、实体、仓库和同事务入队**

```java
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(boolean enabled, Telegram telegram) {
    public record Telegram(boolean enabled, String botToken, String chatId,
                           int sendTimeoutSeconds, int maxAttempts) {}
}
```

`TradeEventService.record` 保存 event 后调用 `outboxService.enqueue(saved)`；入队异常只允许在数据库本身失败时随交易事件事务失败，Telegram 网络调用不得出现在该事务中。

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `mvn -q -DskipTests=false -Dtest=TradeEventServiceTest,NotificationOutboxServiceTest test`

### Task 2: Telegram Sender 与重试分类

**Files:**
- Create: `src/main/java/com/example/quant/notification/TelegramSender.java`
- Create: `src/main/java/com/example/quant/notification/TelegramSendResult.java`
- Create: `src/main/java/com/example/quant/notification/TelegramMessageRenderer.java`
- Test: `src/test/java/com/example/quant/notification/TelegramSenderTest.java`
- Test: `src/test/java/com/example/quant/notification/TelegramMessageRendererTest.java`

**Interfaces:**
- Produces: `TelegramSendResult send(String text)`；`String render(TradeEventEntity event, TradingMode mode)`。

- [ ] **Step 1: 写 MockWebServer 失败测试**

```java
@Test
void classifies429AsRetryUsingRetryAfter() {
    server.enqueue(new MockResponse().setResponseCode(429)
            .addHeader("Retry-After", "7")
            .setBody("{\"ok\":false}"));

    TelegramSendResult result = sender.send("test");

    assertThat(result.retryable()).isTrue();
    assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(7));
}
```

同时覆盖 200/SENT、401/FAILED、500/RETRY、timeout/RETRY，检查请求使用 JSON body 且 URL/日志不输出 Token。

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -q -DskipTests=false -Dtest=TelegramSenderTest,TelegramMessageRendererTest test`

- [ ] **Step 3: 使用 JDK HttpClient 实现 sender**

请求地址为 `https://api.telegram.org/bot<token>/sendMessage`，body 只包含 `chat_id`、`text`、`disable_web_page_preview=true`。响应必须同时满足 HTTP 2xx 和 JSON `ok=true` 才算 SENT。

Renderer 只读取 allowlist 字段，标题严格区分提交、部分成交、完全成交、撤单、平仓、UNKNOWN 和失败；缺失字段显示“不可用”，不能显示 0。

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `mvn -q -DskipTests=false -Dtest=TelegramSenderTest,TelegramMessageRendererTest test`

### Task 3: Dispatcher、重启恢复和状态 API

**Files:**
- Create: `src/main/java/com/example/quant/notification/TelegramNotificationDispatcher.java`
- Create: `src/main/java/com/example/quant/controller/NotificationController.java`
- Test: `src/test/java/com/example/quant/notification/TelegramNotificationDispatcherTest.java`
- Test: `src/test/java/com/example/quant/controller/NotificationControllerTest.java`

**Interfaces:**
- Produces: scheduled `dispatch()`；`GET /api/quant/notifications/status`；`POST /api/quant/notifications/telegram/test`。

- [ ] **Step 1: 写失败测试**

测试 due PENDING 被领取并 SENT、429 设置 RETRY 和 nextAttemptAt、达到 maxAttempts 进入 FAILED、过期 SENDING lease 可恢复、SENT 不再领取、测试接口不创建交易事件。

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -q -DskipTests=false -Dtest=TelegramNotificationDispatcherTest,NotificationControllerTest test`

- [ ] **Step 3: 实现有界 dispatcher**

每 10 秒最多领取 20 条；数据库事务内从 PENDING/RETRY/过期 SENDING 改为 SENDING 并设置 60 秒 lease，事务外发送，再以新事务写结果。退避使用 `min(300, 2^attempt * 5)` 秒并加入 0..3 秒 jitter；429 优先使用 `Retry-After`。

- [ ] **Step 4: 实现状态和测试接口**

状态只返回 enabled、pending、retry、failed、lastSentAt 和脱敏错误。测试接口要求登录会话，禁用或缺 Token/Chat ID 时返回明确错误，不改变 TradingMode。

- [ ] **Step 5: 运行测试确认 GREEN**

Run: `mvn -q -DskipTests=false -Dtest=NotificationOutboxServiceTest,TelegramSenderTest,TelegramMessageRendererTest,TelegramNotificationDispatcherTest,NotificationControllerTest test`

### Task 4: Docker 配置和中文操作文档

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `docker-compose.yml`
- Modify: `scripts/test-deployment.sh`
- Create: `docs/TELEGRAM_NOTIFICATION_GUIDE.md`
- Modify: `README.md`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Consumes: `NOTIFICATION_ENABLED`、`TELEGRAM_ENABLED`、`TELEGRAM_BOT_TOKEN`、`TELEGRAM_CHAT_ID`、`TELEGRAM_SEND_TIMEOUT_SECONDS`、`TELEGRAM_MAX_ATTEMPTS`。

- [ ] **Step 1: 先扩展部署契约测试并确认 RED**

增加断言：`.env.example` 和 Compose 包含全部 Telegram 变量；默认 disabled；脚本和文档不包含真实 Token。

Run: `bash scripts/test-deployment.sh`

Expected: FAIL，提示 Telegram 配置尚未映射。

- [ ] **Step 2: 增加配置映射**

```yaml
notification:
  enabled: ${NOTIFICATION_ENABLED:false}
  telegram:
    enabled: ${TELEGRAM_ENABLED:false}
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    chat-id: ${TELEGRAM_CHAT_ID:}
    send-timeout-seconds: ${TELEGRAM_SEND_TIMEOUT_SECONDS:8}
    max-attempts: ${TELEGRAM_MAX_ATTEMPTS:5}
```

`.env.example` 保持空 Token 和默认关闭，Compose 显式传入，不使用 `env_file` 整体注入。

- [ ] **Step 3: 写完整 Telegram 操作文档**

文档明确 BotFather `/newbot`、用户 `/start`、通过官方 `getUpdates` 获取 chat id、私聊/群组/频道差异、`.env`、`./deploy.sh --check && ./deploy.sh`、测试接口、真实事件验收、400/401/403/429、Token `/revoke` 轮换和泄漏响应；不提供远程交易命令。

- [ ] **Step 4: 完整验证**

Run: `bash scripts/test-deployment.sh`

Run: `mvn clean package -DskipTests=false`

Run: `git diff --check`

Expected: 部署契约和后端全量测试通过。没有真实 Telegram Token 时明确记录 `Telegram E2E NOT EXECUTED`。

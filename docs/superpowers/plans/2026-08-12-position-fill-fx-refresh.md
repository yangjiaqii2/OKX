# 仓位成交对账与每日汇率刷新实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复终态订单 fills 不完整导致的仓位误判，适度提高震荡行情容量，并让人民币展示每天自动使用 ECB 最新日汇率。

**Architecture:** 普通订单进入终态后，REST 同步只对最近且本地累计 fill 小于交易所累计成交量的订单补查 fills，再进行仓位 reconciliation。汇率服务在应用就绪及北京时间每日 00:05 刷新 ECB 日 XML，官方域名不可达时使用 Frankfurter ECB 镜像，并在进程内保存线程安全快照；接口只读快照。震荡行情动态上限由 1 调整为 2，高波动仍为 1。

**Tech Stack:** Java 17、Spring Boot 3.2、Spring Scheduling、JDK HttpClient/XML、JUnit 5、Mockito、MySQL、Docker Compose。

## Global Constraints

- 所有交易写请求继续经过既有 TradingMode、readiness、Kill Switch、账户和订单 Guard。
- 汇率只用于 UI 人民币换算，不进入订单、PnL、风险或仓位计算。
- 不新增第三方依赖，不自动切换 LIVE，不提交 Git。

---

### Task 1: 补齐终态订单 fills 后再判定仓位归属

**Files:**
- Modify: `src/main/java/com/example/quant/okxtrade/OkxCurrentOrderSyncService.java`
- Modify: `src/main/java/com/example/quant/agent/execution/TradeOrderRepository.java`
- Modify: `src/main/java/com/example/quant/ledger/TradeFillRepository.java`
- Test: `src/test/java/com/example/quant/okxtrade/OkxCurrentOrderSyncServiceTest.java`

**Interfaces:**
- Consumes: 当前 owner 最近 24 小时 `FILLED / PARTIALLY_FILLED_CANCELED` 普通订单及其本地 fills。
- Produces: `syncOnce()` 在仓位对账前按 `ordId` 补齐缺失的 OKX fills；失败保持同步失败语义。

- [x] 增加失败测试：WS 已把订单标记 `FILLED`，但本地只有最后一笔 12/717 fill 时，REST 同步必须查询并记录完整 fills。
- [x] 运行定向测试确认旧实现失败。
- [x] 只补查最近且本地累计成交量不足的终态普通订单，完整订单不重复请求。
- [x] 重新运行订单同步和仓位对账测试。

### Task 2: 每日 ECB USD/CNY 快照

**Files:**
- Create: `src/main/java/com/example/quant/system/EcbFxRateClient.java`
- Modify: `src/main/java/com/example/quant/system/FxRateService.java`
- Modify: `src/main/java/com/example/quant/config/SystemFxRateProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Test: `src/test/java/com/example/quant/system/FxRateServiceTest.java`
- Test: `src/test/java/com/example/quant/system/EcbFxRateClientTest.java`

**Interfaces:**
- Consumes: ECB `eurofxref-daily.xml` 中 USD、CNY 对 EUR 的日参考价。
- Produces: 现有 `FxRateResponse` 结构不变，成功来源为 `ECB`，失败且无成功快照时为 `FALLBACK`。

- [x] 增加失败测试：XML 交叉汇率、启动刷新、失败保留上次成功值、首次失败兜底。
- [x] 运行测试确认旧实现缺少每日刷新行为。
- [x] 使用 JDK HttpClient 和安全 XML 解析实现 ECB 客户端。
- [x] 应用就绪刷新一次，每天北京时间 00:05 刷新；接口不触发外部调用。
- [x] 增加 URL、超时、cron、时区和兜底环境变量说明。
- [x] 重新运行汇率与控制器测试。

### Task 3: 震荡容量、文档与运行验证

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Consumes: `QUANT_AGENT_MAX_POSITIONS_CHOPPY`。
- Produces: 默认 `CHOPPY=2`、`HIGH_VOLATILITY=1`、趋势行情最多 3。

- [x] 调整默认值及部署透传，保留绝对上限 3。
- [x] 更新中文代码库文档，记录 fills、汇率和容量边界。
- [x] 运行 `mvn clean package -DskipTests=false`、前端 `npm run build`、`git diff --check`。
- [x] 重建 `okx-quant:demo-test` 并只重启 app，确认 `TRADING_MODE=DEMO`、容器 healthy、汇率来源和 XAU ownership。

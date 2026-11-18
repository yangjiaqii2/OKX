# AI 主选币、移动交易终端与 Telegram 通知设计

日期：2026-08-11

## 1. 目标

本轮包含三个相互关联但可独立验收的改动：

1. 让 AI 成为合约选择、交易方向和入场方式的主要决策者，确定性规则负责数据资格、计划计算、最终风控和 AI 故障时的自动保底。
2. 在保留 React、MUI 和 React Admin 的前提下，将现有桌面终端升级为可在手机上完成监控、候选查看、订单确认/撤销、平仓和 Kill Switch 的响应式交易终端。
3. 基于现有 `trade_event` 建立 Telegram 单向通知，不允许机器人远程发起资金变化操作。

资金安全边界不因 AI 优先级提高而降低。AI 不能决定最终仓位、杠杆、止损、止盈，也不能绕过交易模式、交易引擎状态、数据完整性、流动性、账户、日损、敞口和订单幂等保护。

## 2. 不在本轮范围

- 不接入 Telegram 入站交易命令。
- 不更换前端框架或重写全部页面。
- 不让 AI 直接接收整个 OKX 合约全集或自由构造交易所请求。
- 不把模型自身知识当作实时新闻源。
- 不调整策略参数来提高历史收益。
- 不实现 WhatsApp、Mattermost 或其他通知渠道。

## 3. AI 主选币

### 3.1 决策链路

```text
OKX ticker / candles / funding / OI / order book / instrument rules
    -> 确定性数据资格与硬流动性过滤
    -> 最多 12 个结构化候选
    -> 单次批量 AI 决策
    -> AI 选择 instId、LONG/SHORT、入场方式或 WAIT
    -> 确定性 Entry/SL/TP/杠杆/仓位计算
    -> 全部硬风控与下单前实时复核
    -> 订单提交或安全拒绝
```

初始规则层不再通过固定综合排名决定唯一交易对象，只负责形成 AI 可比较的合格候选池。候选必须具有可解析的真实价格、已收盘 K 线、Funding、合约规则和可用订单簿；过期数据、异常价差、深度不足、暂停交易或不支持的 instrument 继续在进入 AI 前失败关闭。

### 3.2 AI 输入

每轮只发送一次请求。输入使用 allowlist DTO，最多包含 12 个候选，每个候选包含：

- `instId`、最新价、24h 涨跌和成交额。
- 5m/20m/1h/4h 趋势、EMA、MACD、RSI、ATR、结构和成交量比。
- Funding、当前 OI、真实可用时的 OI 变化。
- 订单簿 spread、双边深度和执行质量。
- BTC/ETH 市场环境。
- 新闻风险状态；新闻不可用时明确为 `NEWS_UNAVAILABLE`，不能伪装为 LOW。
- 当前持仓、同方向敞口和候选可用性摘要，不包含 API Secret、Passphrase 或账户认证信息。

### 3.3 AI 输出

AI 必须返回严格 JSON：

```json
{
  "decision": "TRADE",
  "selectedInstId": "ETH-USDT-SWAP",
  "direction": "LONG",
  "entryMode": "MARKET",
  "confidence": 0.82,
  "rankedCandidates": [
    {
      "instId": "ETH-USDT-SWAP",
      "rank": 1,
      "direction": "LONG",
      "entryMode": "MARKET",
      "confidence": 0.82
    }
  ],
  "reasonList": ["..."],
  "riskList": ["..."]
}
```

字段约束：

- `decision`: `TRADE | WAIT`
- `selectedInstId`: 必须来自本轮候选池；`WAIT` 时为空。
- `direction`: `LONG | SHORT`
- `entryMode`: `MARKET | WAIT_PULLBACK | WAIT_RETEST`
- `confidence`: `0.00-1.00`
- `rankedCandidates`: 只能包含候选池内合约，每个 `instId` 最多一次，rank 必须从 1 连续递增；用于前端显示 AI 排名，第一项必须与 `selectedInstId` 一致。
- `reasonList`、`riskList`: 限制条数和单项长度，避免模型输出污染日志或数据库。

### 3.4 AI 与规则冲突

- AI 选择不同于旧规则第一名的合约：只要属于合格候选池，采用 AI 选择。
- AI 方向不同于旧规则方向：允许采用 AI 方向；随后重新按该方向计算价格几何和全部硬风控。
- AI 选择池外合约、返回非法枚举、缺字段、非法置信度或无法形成合法价格几何：本轮 AI 结果无效，进入规则保底。
- AI 合法返回 `WAIT`：本轮不交易，不触发规则保底。
- AI 超时、HTTP 错误、非法 JSON 或无效结果：规则保底允许自动下单，但仍必须通过与 AI 路径相同的最终风控。
- 候选池为空：`NO_ELIGIBLE_CANDIDATE`，不能调用 AI，也不能下单。

这套语义不会因为 AI 与旧排名不一致而形成死锁，但不会承诺每轮产生交易。合法 `WAIT` 和没有安全候选都应保持不交易。

### 3.5 决策来源与可观测性

每轮记录以下结果之一：

- `AI_PRIMARY`
- `AI_WAIT`
- `RULE_FALLBACK`
- `NO_ELIGIBLE_CANDIDATE`

记录 AI 请求关联 ID、候选数量、选中合约、方向、入场方式、置信度、降级原因、模型和耗时。不得记录 API Key、完整 Prompt 中的敏感账户数据或原始异常响应中的认证字段。

前端候选与待确认订单必须展示决策来源。若 AI 长期返回 WAIT、持续无效或进入规则保底，Dashboard 必须显示累计次数和最近原因，避免系统表面正常但实际未使用 AI。

### 3.6 新闻缺失语义

当前仓库只有空新闻适配器。新闻缺失调整为：

- 保留 `UNKNOWN/NEWS_UNAVAILABLE`，降低 AI 输入可信度和最终计划置信度。
- 新闻缺失本身不再单独阻断自动交易。
- 已获取数据中出现安全攻击、下架、暂停交易或可信重大负面事件时继续硬阻断。
- 不允许模型用训练知识声称掌握实时新闻。

## 4. 移动交易终端

### 4.1 视觉系统

采用 Industrial 方向：黑色或暖黑背景、扁平面板、1px 边框、无渐变、无玻璃效果、无装饰阴影、弱圆角。数字统一使用等宽字体与 `tabular-nums`；绿色和红色只表达交易语义。

可识别的核心视觉结构是固定交易安全栏：始终显示 `DISABLED/DEMO/LIVE`、Trading Engine、Private WS 和异常数量；手机端可点击展开阻断原因与恢复状态。`LIVE` 必须保持强危险识别，不能只显示普通小徽标。

### 4.2 导航

桌面端保留左侧导航。手机端使用带 safe-area 的底部五入口：

```text
总览 | 机会 | 订单 | 持仓 | 更多
```

“更多”包含账户与风控、账号绑定、账号安全、系统控制、交易生命周期和平仓记录。手机端不承担复杂策略参数编辑。

### 4.3 页面层级

移动 Dashboard 顺序固定为：

1. 交易模式、是否可交易和阻断原因。
2. 权益、可用余额、今日已实现和未实现 PnL。
3. 当前持仓与 Unmanaged position。
4. Open/Unknown/Reconciliation orders。
5. AI 最近选币结果、来源、置信度和降级原因。
6. Private WS、Ledger 和风控异常。

候选页必须显示合约、AI 排名、方向、入场方式、置信度、决策来源、Funding、OI 变化、流动性、新闻状态和硬门槛结果，不能只显示一个综合分。

桌面订单和持仓继续使用高密度表格；手机端使用紧凑摘要行，点击后展开价格、requested/filled/remaining size、均价、SL/TP、状态和时间。撤单、平仓放在展开区域，触摸目标不小于 44px。

### 4.4 危险操作

- 普通查看、筛选和展开不弹确认。
- 撤单使用轻量确认并显示 symbol、side 和 remaining size。
- 平仓必须显示 DEMO/LIVE、symbol、side、size、reduce-only 语义。
- Kill Switch 使用高危确认，执行后立即刷新 Trading Engine 状态。
- 手机底部导航不能覆盖 Toast、Dialog 操作按钮或表格最后一行。

### 4.5 响应式验收

必须检查 390、430、768、1024、1280 和 1440 像素宽度。390/430 下不允许页面级横向溢出；需要完整列的桌面表格只能在自身容器内滚动。Console、Network、Dialog、Drawer、底部导航和 safe-area 都必须实际验证。

## 5. Telegram 单向通知

### 5.1 边界

第一版只发送消息，不接收 `/buy`、`/sell`、`/close`、`/live` 等资金变化命令。Telegram 可用性不能影响交易事务、订单状态写入或 Trading Engine readiness。

### 5.2 数据流

复用 `trade_event` 作为事实源，新增最小持久化 Outbox：

```text
TradeEvent committed
    -> NotificationOutbox(tradeEventId, TELEGRAM)
    -> Dispatcher
    -> Telegram Bot API sendMessage
    -> SENT / RETRY / FAILED
```

唯一键为 `trade_event_id + channel`。Outbox 状态使用 `PENDING / SENDING / SENT / RETRY / FAILED`，记录尝试次数、下次重试时间、最后 HTTP 状态和脱敏错误摘要。

### 5.3 通知范围

立即通知：

- 订单提交与 OKX ACK。
- 部分成交和完全成交。
- 撤单确认。
- 平仓提交与确认。
- 保护单失败。
- `UNKNOWN/RECONCILING`。
- Trading Engine `BLOCKED`。
- Private WS 长时间 `DEGRADED/FAILED`。
- `UNMANAGED` 外部仓位。
- Kill Switch 开启。

AI 候选跳过、普通无信号和每轮评分不逐条通知；AI 选择、规则保底次数和候选摘要进入定时汇总。

### 5.4 消息语义

消息必须区分 `SIGNAL`、`ORDER_SUBMITTED`、`PARTIALLY_FILLED`、`FILLED`、`CANCELED`、`UNKNOWN` 和 `POSITION_CLOSED`，不得把 AI 建议写成已成交。

消息 allowlist 包含：TradingMode、instId、direction、order role、status、decision source、AI confidence、requested/filled/remaining size、avgPx、SL/TP 状态、可用的 decisionId/clOrdId/ordId/tradeId、时间和 Blocked 原因。不得序列化 OKX 原始响应、凭据实体或异常堆栈。

### 5.5 发送与重试

- 使用 JDK `HttpClient` 调用 Telegram Bot API，不新增 SDK。
- 连接和请求超时明确配置。
- 429 尊重 `Retry-After`。
- 5xx、连接失败和超时采用有上限的指数退避。
- 除 429 外的 4xx 直接进入 `FAILED`。
- 最多尝试 5 次；达到上限后 Dashboard 显示失败数量。
- 重启时恢复 `PENDING/RETRY`；过期 `SENDING` 通过 lease 超时重新领取。

### 5.6 配置

```dotenv
NOTIFICATION_ENABLED=false
TELEGRAM_ENABLED=false
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
TELEGRAM_SEND_TIMEOUT_SECONDS=8
TELEGRAM_MAX_ATTEMPTS=5
```

默认关闭。Token 只从服务端环境变量读取，加入 Compose 显式映射但不进入前端 API、日志或 `trade_event`。提供受认证保护的“发送测试通知”接口；该接口只验证渠道，不改变交易模式或创建交易事件。

### 5.7 操作文档

新增中文文档，覆盖 BotFather 创建机器人、用户 `/start`、获取私聊/群组/频道 chat id、`.env`、Docker 更新、测试消息、真实事件验收、400/401/403/429 排查、Token 轮换和泄漏响应。

## 6. 最小实现边界

为避免一次重写三个系统，按以下顺序实施并分别验证：

1. AI 批量选择器、决策来源与规则保底。
2. Telegram Outbox、Sender、测试接口和操作文档。
3. 主题、响应式 Layout、移动导航，以及 Dashboard/候选/订单/持仓关键页面。

每一步复用现有 `AiAnalysisService`、交易计划、`trade_event`、Spring Data JPA、JDK `HttpClient`、MUI 和 React Admin，不增加消息队列、前端框架或 Telegram SDK。

## 7. 测试与验收

### 7.1 AI

- AI 选择旧规则非第一名但属于候选池时采用 AI。
- AI 与旧规则方向不同时采用 AI 方向，并重新计算合法计划。
- 合法 `WAIT` 不进入规则保底。
- timeout、500、非法 JSON、池外 symbol、非法方向进入 `RULE_FALLBACK`。
- 候选为空不调用 AI。
- AI 与规则路径都经过相同最终硬风控。
- 新闻缺失标记为 UNKNOWN，但不单独阻断；明确重大风险继续阻断。

### 7.2 Telegram

- 同一 `tradeEventId + channel` 只生成一条 Outbox。
- 成功发送进入 SENT。
- 429、500、timeout 按策略重试；401/403 终止。
- 重启恢复 pending/retry，不重复发送已成功事件。
- 通知失败不回滚交易事件。
- 消息不包含 Secret、Passphrase、Master Key 或 Bot Token。

### 7.3 前端

- TypeScript 和 Vite 构建通过。
- 390/430/768/1024/1280/1440 实际浏览器验证。
- 手机核心页面没有页面级横向溢出。
- 底部导航、Dialog、Drawer、Toast 和 safe-area 不互相遮挡。
- LIVE、Blocked、Unknown Order、Unmanaged Position 和 AI fallback 状态真实可见。

### 7.4 完整验证

- `mvn clean package -DskipTests=false`
- `npm run build`
- `npm audit --audit-level=moderate`
- `bash scripts/test-deployment.sh`
- `git diff --check`
- DISABLED 运行态和浏览器验证

AI 和 Telegram 的真实外部 E2E 只有在对应凭据可用时执行；没有凭据时只报告模拟集成测试，不伪造成功。

## 8. 验收结论边界

本轮成功只说明 AI 主选币、移动端操作和 Telegram 通知按设计工作，不自动提升 Live readiness。真实 Demo 交易生命周期、Private WS、订单/仓位 reconciliation 和已有 Critical/High 风险仍按系统审计文档独立判断。

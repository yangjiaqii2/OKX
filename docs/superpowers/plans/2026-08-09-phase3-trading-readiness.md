# Phase 3 Trading Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可进入 OKX Demo 验证的持久化决策、订单对账、Demo 模式、启动恢复、Private WS 与真实成交账本。

**Architecture:** REST 是最终权威，Private WS 是实时事件源。数据库唯一约束负责幂等，启动状态和实例租约负责在恢复完成前阻断交易。

**Tech Stack:** Java 17、Spring Boot 3、Spring Data JPA、Flyway、Java HttpClient/WebSocket、React/TypeScript。

## Global Constraints

- 不连接或自动切换真实 LIVE。
- 交易写请求不自动重试。
- UNKNOWN/RECONCILING/外部仓位存在时失败关闭。
- 不新增 Kafka、Redis、微服务或前端框架。

---

### Task 1: 持久化 K 线决策幂等

**Files:** 新增 `strategydecision/*`、Flyway V17；修改 K 线分析、扫描任务和测试。

- [x] 先写重复领取、并发唯一约束、重启遗留 PROCESSING 转 RECONCILING 的失败测试。
- [x] 在 `ContractKlineAnalysis` 携带最新已收盘 5m 时间戳。
- [x] 用数据库唯一键领取决策；重复决策从候选集合过滤，所有退出路径写终态。
- [x] 运行 `mvn -DskipTests=false -Dtest=StrategyDecisionServiceTest,ContractMarketScanTaskTest test`。

### Task 2: 订单终态、部分成交与 Ledger

**Files:** 修改 `okxtrade/*`、`TradeOrderEntity/Repository`；新增 `tradeledger/*` 与 Flyway V18。

- [x] 先写 LIVE、PARTIALLY_FILLED、FILLED、部分成交后 CANCELED 和 UNKNOWN 恢复测试。
- [x] 删除“从 pending 消失即 INACTIVE”的推断，普通订单逐笔查询 Order Details，并用 Fills 补终态。
- [x] 持久化 requested/filled/remaining/avgPx/uTime；按 tradeId 去重真实 fills。
- [ ] 同步 bills，分别保存 pnl、fee、funding 和 balance change。
- [ ] 运行订单 reconciliation 与 ledger 定向测试。

### Task 3: TradingMode 与 Demo 请求隔离

**Files:** 修改 `TradingProperties`、`OkxRestClient`、`OkxRestOrderGateway`、配置、控制器、前端状态条与测试。

- [x] 先写 DISABLED 阻断、DEMO Header 存在、LIVE Header 不存在的 Mock HTTP Server 测试。
- [x] 用 `TradingMode` 枚举替代 `okxLiveEnabled` 决策。
- [x] 私有 REST 查询和交易请求统一由客户端添加 Demo Header；最终写网关只允许 DEMO/LIVE。
- [x] UI 以不同强度显示 DISABLED/DEMO/LIVE。

### Task 4: 启动恢复、外部仓位和单实例租约

**Files:** 新增 `readiness/*`、Flyway V19；修改交易最终 Guard、运行状态接口和测试。

- [ ] 先写未恢复阻断、UNKNOWN 阻断、外部仓位阻断、租约竞争/过期接管测试。
- [x] 启动进入 RECOVERING；订单同步失败、未绑定或存在 RECONCILING 时保持 BLOCKED，最终网关拒绝写入。
- [ ] 将无法归属本地订单的持仓标记 UNMANAGED，只告警不自动操作。
- [ ] 数据库租约心跳失败时立即 BLOCKED。

### Task 5: 最小 Private WS

**Files:** 替换 `OkxWebSocketService` 占位实现；新增 WS 消息处理器和测试。

- [ ] 先写状态迁移、断线退避、重复 fill、迟到 LIVE 不覆盖终态的测试。
- [ ] 使用 Java HttpClient.WebSocket 登录并订阅 orders/positions/account。
- [ ] 三个订阅确认且 REST reconciliation 成功后才 READY；断线立即 DEGRADED 并退避重连。
- [ ] WS order 事件复用订单 reconciliation 更新器，positions/account 事件触发 freshness 与只读同步。

### Task 6: 可观测性、UI 和最终验证

**Files:** 修改 runtime status、Dashboard/API 类型、README、`docs/CODEBASE_DOC.md` 和审计文档。

- [ ] 暴露 recovery、unknown orders、unmanaged positions、WS freshness、ledger sync 和实例租约状态。
- [x] UI 只增加交易状态与 PnL 可靠性表达，不重做主题。
- [x] 运行 `mvn clean package -DskipTests=false`、`npm run build`、`npm audit`、secret scan、`git diff --check`。
- [x] 实际启动并用浏览器检查 Console/Network/状态页；无 Demo 凭据，记录 `Demo E2E NOT EXECUTED`。

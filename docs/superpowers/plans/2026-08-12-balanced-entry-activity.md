# Balanced Entry Activity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将自动入场从约 10/10 保守调整到约 7/10，同时保留全部资金安全硬门槛。

**Architecture:** 只调整两个决策点：AI 选择器提示词与持久化等待意图触发器。订单确认、仓位、账户、日损、流动性、精度和幂等链路不变；自动交易结果额外保留一轮中最重要的硬拒绝原因。

**Tech Stack:** Java 17、Spring Boot 3.2、JUnit 5、AssertJ、Maven、MySQL、Docker Compose。

## Global Constraints

- 不允许 AI 绕过 TradingMode、readiness、Kill Switch、账户、仓位、每日风险或最终订单确认。
- 不增加持续上涨时的无条件追价逻辑。
- 等待价格容差固定为 0.20%，有效期固定为 30 分钟。
- `NOT_READY` 永远不能触发等待意图。
- 不提交 Git 变更。

---

### Task 1: 适度放宽等待意图

**Files:**
- Modify: `src/main/java/com/example/quant/agent/entry/WaitingEntryIntentService.java`
- Test: `src/test/java/com/example/quant/agent/entry/WaitingEntryIntentServiceTest.java`

**Interfaces:**
- Consumes: `ContractCandidate.klineAnalysis().decisionCandleTimestamp()`、`entryTiming5m()`、当前价和原等待方向。
- Produces: `evaluate(String, List<ContractCandidate>, Instant)` 返回符合 30 分钟、0.20% 容差和两根 K 线规则的 `TriggeredIntent`。

- [x] 增加失败测试：多头 `READY` 价格位于参考价上方 0.20% 内可以触发，超过 0.20% 不触发。
- [x] 增加失败测试：两根新 5m K 线后，匹配原等待类型的 timing 可以触发，`NOT_READY` 不能触发。
- [x] 修改到期测试，证明第 15 分钟仍有效、第 30 分钟到期。
- [x] 运行 `mvn -Dtest=WaitingEntryIntentServiceTest test -DskipTests=false`，确认旧实现按预期失败。
- [x] 以常量实现 30 分钟和 0.20% 对称容差，不增加新配置层。
- [x] 重新运行测试并确认通过。

### Task 2: AI 主选适度积极化

**Files:**
- Modify: `src/main/java/com/example/quant/agent/selection/AiContractSelectionService.java`
- Test: `src/test/java/com/example/quant/agent/selection/AiContractSelectionServiceTest.java`

**Interfaces:**
- Consumes: 既有候选 JSON，不新增交易参数。
- Produces: 提示词明确 `MARKET/LIMIT` 与等待的适用边界，JSON 协议保持不变。

- [x] 增加失败测试：发送给 AI 的提示必须包含“无需所有周期完全一致”“不过热且执行条件良好时优先 MARKET/LIMIT”“不得为了成交忽略硬风险”。
- [x] 运行 `mvn -Dtest=AiContractSelectionServiceTest test -DskipTests=false`，确认旧提示词失败。
- [x] 仅修改系统提示和用户提示，不修改解析协议或失败回退。
- [x] 重新运行测试并确认通过。

### Task 3: 保留关键硬拒绝原因

**Files:**
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeServiceTest.java`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Consumes: 候选循环中的 `CONFIRM` 拒绝与普通 PLAN 观察原因。
- Produces: 没有订单提交时优先返回本轮首个 `LOSS_COOLDOWN_ACTIVE` 等硬拒绝原因。

- [x] 增加失败测试：首个候选被每日风险拒绝、后续候选为观察时，最终记录仍包含每日风险原因。
- [x] 运行 `mvn -Dtest=AutoTradeServiceTest test -DskipTests=false`，确认旧实现失败。
- [x] 用一个局部变量保留首个关键确认拒绝，不引入新的错误分类框架。
- [x] 重新运行测试并确认通过。
- [x] 更新 `docs/CODEBASE_DOC.md` 的中文变更记录。
- [x] 运行 `mvn clean package -DskipTests=false`、前端 `npm run build`、`git diff --check`。
- [x] 重建 `okx-quant:demo-test`，保持 `TRADING_MODE=DEMO` 重启应用并检查等待/交易日志。

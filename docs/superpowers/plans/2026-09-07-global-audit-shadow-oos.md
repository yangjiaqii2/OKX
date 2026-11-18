# OKX 全局审计与 Shadow/OOS 验证基础设施实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从当前源码、迁移和真实调用链独立复核 F1-F8，修复确认的 P0/P1，并建立不触发真实交易副作用的多策略 Shadow Selection、Counterfactual Exit 与 Strategy Evaluation。

**Architecture:** 先以生产入口向下追踪扫描、选择、下单、恢复、账本和统计状态机，再用全文写调用搜索验证旁路。所有可恢复运行时事实写入数据库；Shadow 与 Counterfactual 使用独立事实表和服务，不复用 PendingOrder、预算、DailyRisk、TradeOrder、PositionLifecycle 或真实 Ledger 写路径。

**Tech Stack:** Java 25、Spring Boot 3.2、Spring Data JPA、Flyway、MySQL/H2、JUnit 5、AssertJ、Mockito、Maven。

## 全局约束

- 不修改 SL、TP、holding time、AI confidence 或 NEUTRAL threshold 等策略参数。
- P0/P1 本轮直接修复；P2 仅在 S1/S2 验证基础设施必需时修复。
- `signalVenue=PUBLIC_LIVE`、`executionVenue=DEMO/LIVE`、Counterfactual venue 为 `PUBLIC_COUNTERFACTUAL`。
- Shadow 禁止创建 PendingOrder、占用预算、claim DailyRisk、调用交易、设置杠杆或占用仓位槽位。
- Counterfactual 禁止写入真实 Ledger、DailyRisk 和 PositionLifecycle。
- 无数据指标返回 `N/A`，不得伪装成 `0`。
- 先写失败测试并确认失败，再写最小生产实现。

---

### Task 1: 全局源码、迁移、状态机和测试审计

**Files:**
- Modify: `docs/GPT56_SOL_GLOBAL_AUDIT_2026-09-07.md`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Consumes: 当前工作树的 Java 源码、V1-V29 migration、JUnit 测试和 Maven 输出。
- Produces: 带文件、类、方法证据的 F1-F8/S1/S2 状态及 P0/P1/P2 清单。

- [ ] **Step 1: 重建生产主链与所有 OKX 写入口**

  用 `rg` 定位 `placeOrder`、`closePosition`、`placeAlgoOrder`、`privatePost`、`privatePostJson`，逐个回溯调用者并记录是否经过 readiness、ownership、幂等和恢复校验。

- [ ] **Step 2: 核对实体与迁移**

  将每个 `@Column` 对照 V1-V29 DDL、索引和唯一约束；单列 legacy NULL/status/pnl completeness 的读入行为。

- [ ] **Step 3: 核对关键测试的真实边界**

  按 UNIT、INTEGRATION、RECOVERY、FIXTURE、E2E 分类，并标出 mock 掉交易所、数据库、重启或并发边界的测试。

- [ ] **Step 4: 记录证据和问题等级**

  在审计报告中逐项给出 F1-F8 YES/NO、P0/P1/P2、Demo/LIVE 适用性和策略证据结论。

### Task 2: 持久化 F7 可执行风险事实并恢复 CONFIRMING

**Files:**
- Create: `src/main/resources/db/migration/V30__pending_order_executable_risk.sql`
- Modify: `src/main/java/com/example/quant/order/PendingOrder.java`
- Modify: `src/main/java/com/example/quant/order/PendingOrderEntity.java`
- Modify: `src/main/java/com/example/quant/task/AutoTradeRecoveryTask.java`
- Modify: `src/test/java/com/example/quant/order/PendingOrderServiceTest.java`
- Modify: `src/test/java/com/example/quant/task/AutoTradeRecoveryTaskTest.java`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Consumes: `PendingOrder#replaceExecutableSize`, `PendingOrder#setExecutableContractQuantity`, `OrderConfirmService#normalizeExecutableQuantity`。
- Produces: DB 可恢复的 `executable_contract_quantity`、`executable_stop_loss_amount`，以及不会永久卡死的 `CONFIRMING` 状态。

- [ ] **Step 1: 写持久化恢复失败测试**

  保存带 executable quantity/stop amount 的 PendingOrder，重建 `PendingOrderService` 后断言两个值逐位相等；当前实现应得到 `null` 并失败。

- [ ] **Step 2: 运行测试确认 RED**

  Run: `mvn -DskipTests=false -Dtest=PendingOrderServiceTest#restoresExecutableRiskFactsWithoutRecalculation test`
  Expected: FAIL，恢复值为 `null`。

- [ ] **Step 3: 增加 V30 和实体映射**

  V30 新增两个 nullable decimal 列以兼容旧数据；domain restore 明确接收并恢复两个字段，setter 触发持久化 listener。

- [ ] **Step 4: 运行测试确认 GREEN**

  Run: `mvn -DskipTests=false -Dtest=PendingOrderServiceTest#restoresExecutableRiskFactsWithoutRecalculation test`
  Expected: PASS。

- [ ] **Step 5: 写 CONFIRMING 重启恢复失败测试**

  构造 `CONFIRMING` 且没有 exchange submission evidence 的订单，运行 recovery 后断言进入可安全重试的 `BUDGET_RESERVED` 或 `PENDING_CONFIRM`，且不调用 OKX 写 API、不重复 claim。

- [ ] **Step 6: 实现最小恢复状态迁移并验证**

  只恢复明确没有提交证据的 `CONFIRMING`；`SUBMITTING/UNKNOWN_SUBMIT_STATUS` 继续只按稳定 clOrdId 对账，禁止盲目重下。

### Task 3: 修复审计确认的其余 P0/P1

**Files:**
- Modify: 由 Task 1 证据定位的最小生产文件
- Modify: 对应 `src/test/java/...` 回归测试
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Consumes: F1-F8、状态机、并发与幂等审计结果。
- Produces: 每个 P0/P1 都有独立 RED/GREEN 证据且不扩大策略范围。

- [ ] **Step 1: 每个问题先写单一失败测试**

  测试必须经过真实 service 边界；只有外部 OKX/AI/时钟使用替身，不能直接测试同公式 helper 来代替调用链。

- [ ] **Step 2: 确认失败原因与根因一致**

  每次只运行对应测试，确认是缺失的生产行为导致失败，而非 fixture 或编译错误。

- [ ] **Step 3: 应用最小修复并运行相关回归**

  修复后运行直接测试类及相邻状态机测试；任何新失败先回到根因分析。

### Task 4: 多策略 Shadow Selection 持久化

**Files:**
- Create: `src/main/resources/db/migration/V31__shadow_selection.sql`
- Create: `src/main/java/com/example/quant/agent/selection/SelectionPolicy.java`
- Create: `src/main/java/com/example/quant/agent/selection/ShadowSelectionEntity.java`
- Create: `src/main/java/com/example/quant/agent/selection/ShadowSelectionRepository.java`
- Create: `src/main/java/com/example/quant/agent/selection/ShadowSelectionService.java`
- Modify: `src/main/java/com/example/quant/task/ContractMarketScanTask.java`
- Test: `src/test/java/com/example/quant/agent/selection/ShadowSelectionServiceTest.java`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Consumes: 同一次 scan 的候选快照、Hard Gate/Soft Gate 结果及 AI 排名。
- Produces: `CURRENT_AI_OVERRIDE`、`RULE_FIRST_AI_RANK`、`RULE_ONLY`、`LIMITED_AI_OVERRIDE` 四种 policy 的只读决策事实。

- [ ] **Step 1: 写无副作用失败测试**

  对同一 scan 评估四种 policy，断言四条持久化结果具有相同 scanId；验证 PendingOrder、budget、DailyRisk、gateway 和 slot 均无调用。

- [ ] **Step 2: 运行测试确认 RED**

  Run: `mvn -DskipTests=false -Dtest=ShadowSelectionServiceTest test`
  Expected: FAIL，因为服务和表尚不存在。

- [ ] **Step 3: 实现纯选择评估和 V31**

  policy evaluator 只返回选择、coverage 和 gate reason；写入专用表。`ContractMarketScanTask#runOnce` 在真实执行前记录 Shadow，但 Shadow 服务不依赖执行 service。

- [ ] **Step 4: 运行测试确认 GREEN**

  Run: `mvn -DskipTests=false -Dtest=ShadowSelectionServiceTest,ContractMarketScanTaskTest test`
  Expected: PASS。

### Task 5: Counterfactual Exit 持久化

**Files:**
- Create: `src/main/resources/db/migration/V32__counterfactual_exit.sql`
- Create: `src/main/java/com/example/quant/agent/evaluation/ExitPolicy.java`
- Create: `src/main/java/com/example/quant/agent/evaluation/CounterfactualExitEntity.java`
- Create: `src/main/java/com/example/quant/agent/evaluation/CounterfactualExitRepository.java`
- Create: `src/main/java/com/example/quant/agent/evaluation/CounterfactualExitService.java`
- Test: `src/test/java/com/example/quant/agent/evaluation/CounterfactualExitServiceTest.java`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Consumes: 已确认真实 entry fill、PUBLIC_LIVE 后续价格样本、entry risk 与费用假设。
- Produces: `BASELINE/S1_V2/TIME_60/TIME_120/TIME_240/BE_075/TRAIL_1R` 的 `PUBLIC_COUNTERFACTUAL` 观察结果。

- [ ] **Step 1: 写 venue 与隔离失败测试**

  断言七种 policy 均记录为 `PUBLIC_COUNTERFACTUAL`，无真实 Ledger、DailyRisk 或 lifecycle 写入；没有 entry fill 时不产生虚构 trade result。

- [ ] **Step 2: 运行测试确认 RED**

  Run: `mvn -DskipTests=false -Dtest=CounterfactualExitServiceTest test`
  Expected: FAIL，因为服务尚不存在。

- [ ] **Step 3: 实现专用事实表和确定性评估**

  每条结果保存 entry fill ID、policy、触发时间、价格、gross/fee/net、hold duration、数据完整性；唯一键保证重复行情重放幂等。

- [ ] **Step 4: 运行测试确认 GREEN**

  Run: `mvn -DskipTests=false -Dtest=CounterfactualExitServiceTest test`
  Expected: PASS。

### Task 6: Strategy Evaluation

**Files:**
- Create: `src/main/java/com/example/quant/agent/evaluation/StrategyEvaluationService.java`
- Create: `src/main/java/com/example/quant/agent/evaluation/StrategyEvaluationSummary.java`
- Test: `src/test/java/com/example/quant/agent/evaluation/StrategyEvaluationServiceTest.java`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Consumes: Shadow Selection 与 Counterfactual Exit 专用事实仓库。
- Produces: scanCount、selectionCount、coverage、tradeCount、gross、fee、net、expectancy、expectancyR、PF、drawdown、avgHold、dataQuality；无数据使用 `N/A`。

- [ ] **Step 1: 写空样本和完整样本失败测试**

  空仓库断言计数为 0、比率/金额/期望为 `N/A`；完整样本断言 fee 计入 net、PF 和 drawdown 使用已完成样本，缺失数据降低 dataQuality。

- [ ] **Step 2: 运行测试确认 RED**

  Run: `mvn -DskipTests=false -Dtest=StrategyEvaluationServiceTest test`
  Expected: FAIL，因为 summary/service 尚不存在。

- [ ] **Step 3: 实现聚合并验证 GREEN**

  仅消费专用事实表，不读取真实 AutoTradeProfit 作为 counterfactual，所有除法均显式处理零分母和缺失样本。

### Task 7: 全量验证与报告收敛

**Files:**
- Modify: `docs/GPT56_SOL_GLOBAL_AUDIT_2026-09-07.md`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Consumes: Tasks 1-6 的实现和测试证据。
- Produces: 用户要求的两部分最终报告与五个明确判断。

- [ ] **Step 1: 运行聚焦回归**

  Run: 所有 F1-F8 与 S1/S2 测试类。
  Expected: 0 failures, 0 errors。

- [ ] **Step 2: 运行全量与构建验证**

  Run: `mvn -DskipTests=false test`
  Run: `mvn -DskipTests package`
  Run: Flyway H2 integration test and, if configured locally, MySQL migration validation。
  Run: `git diff --check`

- [ ] **Step 3: 更新文档验证记录**

  在 `docs/CODEBASE_DOC.md` 记录日期、摘要、文件、影响和每条命令的真实结果；在审计报告中区分源码/H2 证明与未执行的真实 OKX/生产 MySQL 证明。

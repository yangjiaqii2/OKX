# AI 主选币实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 AI 在合格候选池中决定合约、方向和入场方式，规则负责最终计划与安全验证，并在 AI 技术故障时自动保底。

**Architecture:** 新增单次批量 `AiContractSelectionService`，复用 `AiAnalysisService.completeJson`，返回强类型选择结果和 AI 排名。`AutoTradeService` 先形成候选池再调用选择器；合法 WAIT 结束本轮，非法/超时进入现有规则排序；`ContractTradePlanBuilder` 接收显式方向和入场方式并继续负责止损、止盈、杠杆、仓位和硬风控。

**Tech Stack:** Java 17、Spring Boot 3.2、Jackson、JUnit 5、Mockito、MySQL/Flyway。

## Global Constraints

- AI 不能决定最终仓位、杠杆、止损或止盈。
- AI 不得绕过 `TradingMode`、Trading Engine readiness、订单幂等或任何资金安全 Guard。
- 一轮扫描只允许一个批量 AI 请求，最多 12 个候选，AI HTTP 自动重试保持 0。
- 合法 `WAIT` 不进入规则保底；技术失败或非法输出才进入 `RULE_FALLBACK`。
- 新闻缺失保留 `NEWS_UNAVAILABLE` 并降置信度，不单独阻断；HIGH/CRITICAL 继续阻断。
- 当前工作区存在前几轮未提交改动；未经用户明确授权不执行 `git commit`，每个任务以聚焦 diff 和测试作为检查点。

---

### Task 1: 强类型 AI 批量选择器

**Files:**
- Create: `src/main/java/com/example/quant/agent/selection/ContractSelectionDecision.java`
- Create: `src/main/java/com/example/quant/agent/selection/AiContractSelectionService.java`
- Create: `src/test/java/com/example/quant/agent/selection/AiContractSelectionServiceTest.java`
- Modify: `src/main/java/com/example/quant/config/AgentProperties.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: `AiAnalysisService.completeJson(String, String)`、`List<ContractCandidate>`。
- Produces: `ContractSelectionDecision select(List<ContractCandidate>)`；结果包含 `source`、`decision`、`selectedInstId`、`direction`、`entryMode`、`confidence`、`rankedCandidates`、理由和降级原因。

- [ ] **Step 1: 写失败测试，覆盖 AI 主选、WAIT 和非法输出**

```java
@Test
void selectsAiCandidateOutsideRuleFirstPlaceWhenItBelongsToPool() {
    when(ai.completeJson(anyString(), anyString())).thenReturn(json("""
        {"decision":"TRADE","selectedInstId":"ETH-USDT-SWAP","direction":"SHORT",
         "entryMode":"MARKET","confidence":0.82,
         "rankedCandidates":[
           {"instId":"ETH-USDT-SWAP","rank":1,"direction":"SHORT","entryMode":"MARKET","confidence":0.82},
           {"instId":"BTC-USDT-SWAP","rank":2,"direction":"LONG","entryMode":"WAIT_PULLBACK","confidence":0.70}],
         "reasonList":["ETH结构更清晰"],"riskList":["新闻不可用"]}
        """));

    ContractSelectionDecision result = service.select(List.of(btcCandidate(), ethCandidate()));

    assertThat(result.source()).isEqualTo(DecisionSource.AI_PRIMARY);
    assertThat(result.selectedInstId()).isEqualTo("ETH-USDT-SWAP");
    assertThat(result.direction()).isEqualTo(DirectionBias.BEARISH);
}
```

同时增加：合法 WAIT 返回 `AI_WAIT`；池外 symbol、重复/不连续 rank、非法枚举、缺字段和异常置信度返回 `RULE_FALLBACK`；空池返回 `NO_ELIGIBLE_CANDIDATE` 且不调用 AI。

- [ ] **Step 2: 运行测试并确认 RED**

Run: `mvn -q -DskipTests=false -Dtest=AiContractSelectionServiceTest test`

Expected: FAIL，因为选择器类型尚不存在。

- [ ] **Step 3: 实现最小结果类型与严格解析**

```java
public record ContractSelectionDecision(
        DecisionSource source,
        SelectionAction decision,
        String selectedInstId,
        DirectionBias direction,
        EntryMode entryMode,
        BigDecimal confidence,
        List<RankedCandidate> rankedCandidates,
        List<String> reasonList,
        List<String> riskList,
        String fallbackReason
) {
    public enum DecisionSource { AI_PRIMARY, AI_WAIT, RULE_FALLBACK, NO_ELIGIBLE_CANDIDATE }
    public enum SelectionAction { TRADE, WAIT }
    public enum EntryMode { MARKET, WAIT_PULLBACK, WAIT_RETEST }
    public record RankedCandidate(String instId, int rank, DirectionBias direction,
                                  EntryMode entryMode, BigDecimal confidence) {}
}
```

`AiContractSelectionService` 对候选先限制为 `candidateLimit=12`，Prompt 明确只能从输入数组选择；解析时验证 symbol allowlist、rank 连续性、第一名与 selected 一致以及字符串数组长度。捕获 AI 技术异常后只返回带脱敏原因的 `RULE_FALLBACK`，不在此处执行交易。

- [ ] **Step 4: 增加最小配置**

```yaml
quant:
  agent:
    ai-selection:
      enabled: true
      candidate-limit: 12
```

在 `AgentProperties` 中增加 `AiSelection` 嵌套配置，`candidateLimit()` 强制范围 `1..12`。

- [ ] **Step 5: 运行聚焦测试并检查 diff**

Run: `mvn -q -DskipTests=false -Dtest=AiContractSelectionServiceTest test`

Expected: PASS。检查选择器没有记录 Prompt、API Key 或原始 AI 错误响应。

### Task 2: 新闻 UNKNOWN 与 AI 候选资格

**Files:**
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java`
- Modify: `src/main/java/com/example/quant/agent/gate/ContractTradeGate.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeServiceTest.java`
- Test: `src/test/java/com/example/quant/agent/gate/ContractTradeGateTest.java`

**Interfaces:**
- Consumes: `ContractNewsRiskAnalysis.newsRiskLevel()` 和 `decision()`。
- Produces: `hardNewsBlockReason` 只为 HIGH/CRITICAL 返回拒绝；UNKNOWN 作为风险标签继续进入 AI。

- [ ] **Step 1: 写失败测试**

```java
@Test
void newsUnavailableDoesNotAloneRejectOtherwiseEligibleCandidate() {
    ContractCandidate candidate = candidateWithNews(
            ContractNewsRiskAnalysis.unknown("新闻源不可用", 20));

    assertThat(ContractTradeGate.autoDenyReasons(candidate, properties))
            .doesNotContain("news_data_unavailable");
}

@Test
void criticalNewsStillBlocksCandidate() {
    ContractCandidate candidate = candidateWithNews(
            ContractNewsRiskAnalysis.critical("交易所下架风险"));

    assertThat(ContractTradeGate.autoDenyReasons(candidate, properties))
            .contains("news_risk_critical");
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -q -DskipTests=false -Dtest=AutoTradeServiceTest,ContractTradeGateTest test`

- [ ] **Step 3: 收窄新闻硬阻断**

`hardNewsBlockReason` 仅拒绝 `HIGH` 和 `CRITICAL`；UNKNOWN 的原因保留在 AI 输入、计划风险和 UI，不改写为 LOW。不要删除重大负面事件关键字分类。

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `mvn -q -DskipTests=false -Dtest=ContractNewsAnalysisServiceTest,AutoTradeServiceTest,ContractTradeGateTest test`

Expected: UNKNOWN 可进入候选，HIGH/CRITICAL 仍被拒绝。

### Task 3: AI 顺序接入自动交易与方向覆盖

**Files:**
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java`
- Modify: `src/main/java/com/example/quant/tradeplan/TradePlanService.java`
- Modify: `src/main/java/com/example/quant/tradeplan/ContractTradePlanBuilder.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeServiceTest.java`
- Test: `src/test/java/com/example/quant/tradeplan/ContractTradePlanBuilderTest.java`

**Interfaces:**
- Consumes: `AiContractSelectionService.select(candidates)`。
- Produces: `TradePlanService.createSelectedContractPlan(ContractCandidate, ContractSelectionDecision)`；AI 选中候选排在执行序列首位，规则保底保留现有排序。

- [ ] **Step 1: 写失败测试验证优先级和 WAIT**

```java
@Test
void aiSelectedSecondCandidateExecutesBeforeRuleFirstCandidate() {
    when(selector.select(anyList())).thenReturn(aiTrade("ETH-USDT-SWAP", BEARISH, MARKET));

    AutoTradeResult result = service.evaluateAndExecute(List.of(btcCandidate(), ethCandidate()));

    verify(tradePlanService).createSelectedContractPlan(eq(ethCandidate()), any());
    assertThat(result.instId()).isEqualTo("ETH-USDT-SWAP");
}

@Test
void validAiWaitDoesNotExecuteRuleFallback() {
    when(selector.select(anyList())).thenReturn(aiWait());

    AutoTradeResult result = service.evaluateAndExecute(List.of(btcCandidate()));

    verifyNoInteractions(orderConfirmService);
    assertThat(result.message()).contains("AI_WAIT");
}
```

增加 AI failure 使用规则第一名、AI SHORT 覆盖旧 LONG 并生成合法 short 几何、最终硬风控拒绝 AI 计划的测试。

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -q -DskipTests=false -Dtest=AutoTradeServiceTest,ContractTradePlanBuilderTest test`

- [ ] **Step 3: 在自动交易准备阶段调用选择器**

在完成未决订单、持仓容量、余额检查后，将当前规则排序截取到 AI `candidateLimit`，调用选择器：

```java
ContractSelectionDecision selection = contractSelectionService.select(aiEligibleCandidates);
if (selection.source() == AI_WAIT) {
    return recordAndReturn(AutoTradeResult.skipped("AI_WAIT"), candidateCount, null);
}
List<ContractCandidate> ordered = selection.source() == AI_PRIMARY
        ? selectedFirst(selection.selectedInstId(), ruleOrdered)
        : ruleOrdered;
```

AI 选中候选使用 `createSelectedContractPlan`；后续规则候选仍走现有 `createContractPlan`。不要让选择器拥有 `OrderConfirmService` 或 OKX Gateway。

- [ ] **Step 4: 让计划构建器消费 AI 方向和入场方式**

AI `MARKET` 使用最新可验证 entry；`WAIT_PULLBACK/WAIT_RETEST` 返回 WATCH，不下单。方向与候选旧方向不同时，不复用旧方向的 SL/TP，而是基于当前 entry、ATR 和 swing structure 重新计算，再走现有 `validGeometry`、风险收益、仓位和 `ContractRiskService`。

- [ ] **Step 5: 运行聚焦测试确认 GREEN**

Run: `mvn -q -DskipTests=false -Dtest=AiContractSelectionServiceTest,AutoTradeServiceTest,ContractTradePlanBuilderTest,ContractRiskServiceTest,PositionSizingServiceTest test`

Expected: AI 主选、AI WAIT、规则保底和相反方向计划均按定义工作。

### Task 4: 决策来源 API、审计和文档

**Files:**
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeRecordEntity.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeRecordService.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeRecordView.java`
- Create: `src/main/resources/db/migration/V24__ai_contract_selection_audit.sql`
- Modify: `src/main/java/com/example/quant/controller/ContractAnalysisController.java`
- Modify: `src/main/java/com/example/quant/agent/selection/AiContractSelectionService.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeRecordServiceTest.java`
- Create: `src/test/java/com/example/quant/controller/ContractAnalysisControllerTest.java`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Produces: `GET /api/quant/contract/ai-selection` 返回最近一次选择快照；自动交易记录增加 `decisionSource`、`aiConfidence`、`aiDirection`、`aiEntryMode` 和脱敏 `selectionReason`。

- [ ] **Step 1: 写 migration 和失败测试**

```sql
ALTER TABLE auto_trade_record
    ADD COLUMN decision_source VARCHAR(32),
    ADD COLUMN ai_confidence DECIMAL(8,6),
    ADD COLUMN ai_direction VARCHAR(16),
    ADD COLUMN ai_entry_mode VARCHAR(32),
    ADD COLUMN selection_reason TEXT;
```

测试断言 AI 主选和规则保底记录不同来源，并且 API 在尚无扫描时返回 `NO_ELIGIBLE_CANDIDATE` 空快照。

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -q -DskipTests=false -Dtest=AutoTradeRecordServiceTest,ContractAnalysisControllerTest test`

- [ ] **Step 3: 持久化选择摘要并暴露只读快照**

选择器用 `AtomicReference<SelectionSnapshot>` 保存最近一次 UI 快照；审计字段随 `AutoTradeResult` 持久化。API 不返回完整 Prompt 或原始模型响应。

- [ ] **Step 4: 同步文档并完成后端验证**

更新 `docs/CODEBASE_DOC.md` 的 AI 选币链路、配置、API、降级语义和测试结果。

Run: `mvn clean package -DskipTests=false`

Expected: 全部测试和打包通过，Flyway V1-V24 验证通过。

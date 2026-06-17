# OKX Auto Trade Execution Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Optimize automatic OKX contract execution decisions without changing API signing, authorization, manual order flow, system switches, emergency stop, or position sync.

**Architecture:** Add small policy/resolver classes around the existing auto-trade path. Keep OKX entry submission intact, and limit adapter changes to protection-order sizing/fill-aware protection planning.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, AssertJ, Maven.

---

### Task 1: Smart Entry Mode

**Files:**
- Create: `src/main/java/com/example/quant/agent/entry/SmartEntryMode.java`
- Create: `src/main/java/com/example/quant/agent/entry/SmartEntryModeDecision.java`
- Create: `src/main/java/com/example/quant/agent/entry/SmartEntryModeResolver.java`
- Modify: `src/main/java/com/example/quant/config/AgentProperties.java`
- Modify: `src/main/java/com/example/quant/tradeplan/ContractTradePlanBuilder.java`
- Test: `src/test/java/com/example/quant/agent/entry/SmartEntryModeResolverTest.java`
- Test: `src/test/java/com/example/quant/tradeplan/ContractTradePlanBuilderTest.java`

- [ ] Step 1: Add failing tests for STRONG_LONG MARKET, PULLBACK_LONG WAIT_PULLBACK, breakout WAIT_RETEST, WAIT_OVERHEATED NO_ENTRY.
- [ ] Step 2: Run `mvn test -Dtest=SmartEntryModeResolverTest,ContractTradePlanBuilderTest` and verify failure.
- [ ] Step 3: Implement resolver and plan builder integration.
- [ ] Step 4: Run the same tests and verify pass.

### Task 2: Auto Trade Candidate Fallback and Position Quality

**Files:**
- Create: `src/main/java/com/example/quant/agent/execution/FailureClassification.java`
- Create: `src/main/java/com/example/quant/agent/execution/FailureReasonClassifier.java`
- Create: `src/main/java/com/example/quant/agent/execution/PositionQualityPolicy.java`
- Create: `src/main/java/com/example/quant/agent/execution/CorrelationExposureGuard.java`
- Modify: `src/main/java/com/example/quant/config/AgentProperties.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeServiceTest.java`

- [ ] Step 1: Add failing tests for dynamic score thresholds, CHOPPY/RISK_OFF capacity, same-direction alt exposure, NEXT_CANDIDATE fallback, STOP_ROUND stop.
- [ ] Step 2: Run `mvn test -Dtest=AutoTradeServiceTest` and verify failure.
- [ ] Step 3: Implement policy classes and update auto-trade loop.
- [ ] Step 4: Run the same test and verify pass.

### Task 3: Pre-Confirm Refresh

**Files:**
- Create: `src/main/java/com/example/quant/agent/execution/PreConfirmRefreshService.java`
- Create: `src/main/java/com/example/quant/agent/execution/PreConfirmRefreshResult.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java`
- Modify: `src/main/java/com/example/quant/config/AgentProperties.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeServiceTest.java`

- [ ] Step 1: Add failing test where top candidate fails pre-confirm spread/price refresh and second candidate is submitted.
- [ ] Step 2: Run `mvn test -Dtest=AutoTradeServiceTest` and verify failure.
- [ ] Step 3: Implement refresh service with candidate snapshot checks.
- [ ] Step 4: Run the same test and verify pass.

### Task 4: Fill-Aware Protection Plan

**Files:**
- Create: `src/main/java/com/example/quant/okxtrade/OkxOrderFill.java`
- Create: `src/main/java/com/example/quant/okxtrade/ProtectionPlanResult.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxOrderGateway.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxRestOrderGateway.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java`
- Modify: `src/main/java/com/example/quant/config/AgentProperties.java`
- Test: `src/test/java/com/example/quant/okxtrade/OkxTradeAdapterTest.java`

- [ ] Step 1: Add failing tests for avgPx TP/SL recalculation, deviation failure close-position behavior, small TP merge, reduceOnly side checks, total TP size <= filled size.
- [ ] Step 2: Run `mvn test -Dtest=OkxTradeAdapterTest` and verify failure.
- [ ] Step 3: Implement default fill query path and protection plan generation from actual avgPx/filledSize.
- [ ] Step 4: Run the same test and verify pass.

### Task 5: Configuration and Verification

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify tests touched above as needed.

- [ ] Step 1: Add all new `quant.agent.entry`, `protection`, `take-profit`, `fallback`, `position-quality`, `exposure`, and `pre-confirm-refresh` config defaults.
- [ ] Step 2: Run focused tests:
  `mvn test -Dtest=SmartEntryModeResolverTest,ContractTradePlanBuilderTest,AutoTradeServiceTest,OkxTradeAdapterTest`
- [ ] Step 3: Run full test suite:
  `mvn test`
- [ ] Step 4: Summarize changed files, configs, behavior, and explicit untouched core flows.

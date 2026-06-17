# OKX Contract Analysis Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the existing OKX contract candidate analysis, scoring, ranking, news-risk gating, and AI plan prompt/output inputs around 20m primary trading structure.

**Architecture:** Keep the existing `OkxContractScanner -> ContractSignalAnalyzer -> ContractCandidate -> ContractTradePlanBuilder` flow. Add structured DTO fields and rule helpers inside the current crypto analysis boundary, then make scanner ordering and trade gates consume those fields. Do not modify OKX order gateway, signing, account authorization, cancel, or position sync code.

**Tech Stack:** Java 17, Spring Boot 3, Maven, JUnit 5, AssertJ.

---

### Task 1: Structured Signal DTOs and Failing Tests

**Files:**
- Create: `src/main/java/com/example/quant/crypto/dto/ContractKlineSet.java`
- Create: `src/main/java/com/example/quant/crypto/dto/ContractKlineAnalysis.java`
- Create: `src/main/java/com/example/quant/crypto/dto/ContractNewsRiskAnalysis.java`
- Create: `src/main/java/com/example/quant/crypto/dto/ContractNewsRiskDecision.java`
- Create: `src/main/java/com/example/quant/crypto/dto/ContractScoreBreakdown.java`
- Create: `src/main/java/com/example/quant/crypto/dto/ContractSignalType.java`
- Modify: `src/main/java/com/example/quant/crypto/dto/ContractSignal.java`
- Modify: `src/main/java/com/example/quant/crypto/dto/ContractCandidate.java`
- Test: `src/test/java/com/example/quant/crypto/ContractSignalAnalyzerScenarioTest.java`

- [ ] **Step 1: Write failing scenario tests**

Add tests for STRONG_LONG, WAIT_OVERHEATED, REVERSAL_SHORT, TREND_SHORT, WAIT_OVERSOLD, NEUTRAL, insufficient 20m data, and risk/reward gating using synthetic candles.

- [ ] **Step 2: Run red test**

Run: `mvn test -Dtest=ContractSignalAnalyzerScenarioTest`
Expected: compile or assertion failure because structured signal fields and logic do not exist yet.

### Task 2: 20m/5m/1h/4h Kline Analysis and Scoring

**Files:**
- Modify: `src/main/java/com/example/quant/crypto/ContractSignalAnalyzer.java`
- Modify: `src/main/java/com/example/quant/crypto/OkxMarketService.java`

- [ ] **Step 1: Implement kline set support**

Add `ContractKlineSet` input to the analyzer and preserve the old analyzer overload by adapting its candle list as 20m/5m fallback.

- [ ] **Step 2: Implement indicators**

Compute EMA20/EMA60, MACD histogram trend, RSI14, ATR14/ATRPct, ADX14, Bollinger bandwidth, volume ratio, wick/body ratios, swing structure, EMA pullback/rejection, and 5m entry timing.

- [ ] **Step 3: Implement signal type and weighted score**

Classify STRONG_LONG, PULLBACK_LONG, WAIT_OVERHEATED, REVERSAL_SHORT, TREND_SHORT, WAIT_OVERSOLD, NEUTRAL, and NO_TRADE. Calculate 0-100 component scores and total score with weights trend 25, volume 25, liquidity 15, volatility 10, oi/funding 10, market 8, news 7.

- [ ] **Step 4: Run green test**

Run: `mvn test -Dtest=ContractSignalAnalyzerScenarioTest,ContractSignalAnalyzerTest`
Expected: all selected analyzer tests pass.

### Task 3: Scanner Data Fetching, Liquidity, News Risk, and Ranking

**Files:**
- Modify: `src/main/java/com/example/quant/config/AgentProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/example/quant/crypto/OkxContractScanner.java`
- Modify: `src/main/java/com/example/quant/crypto/ContractNewsAnalysisService.java`
- Modify: `src/main/java/com/example/quant/agent/gate/ContractTradeGate.java`
- Test: `src/test/java/com/example/quant/crypto/OkxContractScannerTest.java`

- [ ] **Step 1: Add configurable thresholds**

Add today-change, liquidity, volatility, funding, kline, and ranking thresholds under `quant.agent`.

- [ ] **Step 2: Fetch multi-timeframe candles**

Fetch 5m, 1H, 4H, 1D candles and aggregate 5m candles into 20m candles when OKX does not provide 20m directly.

- [ ] **Step 3: Add news-risk adapter behavior**

Return a structured LOW/UNKNOWN/HIGH/CRITICAL risk object from existing news service boundary. If sources are unavailable, mark UNKNOWN and cap action at WAIT_CONFIRM.

- [ ] **Step 4: Rank Top 10 with finalRankScore**

Filter low turnover, excessive spread, insufficient depth, missing kline data, overheated longs, oversold shorts, extreme funding, and bad risk/reward from first-place eligibility. Use final rank score and tie-breakers instead of raw total score.

- [ ] **Step 5: Run scanner tests**

Run: `mvn test -Dtest=OkxContractScannerTest,ContractTradeGateTest`
Expected: top candidate is selected by composite final rank and non-tradable top scores are skipped.

### Task 4: AI Plan Prompt and Plan Gating

**Files:**
- Modify: `src/main/java/com/example/quant/tradeplan/ContractTradePlanBuilder.java`
- Test: existing trade plan and auto-trade tests as applicable

- [ ] **Step 1: Extend AI prompt**

Send signalType, 20m/5m/1h/4h analysis, score breakdown, news risk decision, liquidity, funding, stop-loss distance, and risk/reward to the AI prompt as structured JSON-oriented context.

- [ ] **Step 2: Stop unsafe fallback plans**

For NO_TRADE/WAIT_OVERHEATED/WAIT_OVERSOLD/NEUTRAL or news HIGH/CRITICAL/UNKNOWN, generate WAIT-style plan risk text instead of forcing an open plan.

- [ ] **Step 3: Verify plan tests**

Run: `mvn test -Dtest=AutoTradeServiceTest,ContractTradeGateTest`
Expected: auto trade only consumes candidates passing the updated candidate gate.

### Task 5: Final Verification

**Files:**
- All modified files above

- [ ] **Step 1: Run focused crypto tests**

Run: `mvn test -Dtest=ContractSignalAnalyzerScenarioTest,ContractSignalAnalyzerTest,OkxContractScannerTest,OrderBookLiquidityServiceTest,ContractTradeGateTest`

- [ ] **Step 2: Run full backend tests**

Run: `mvn test`

- [ ] **Step 3: Review diff for forbidden scope**

Run: `git diff -- src/main/java/com/example/quant/okxtrade src/main/java/com/example/quant/account/PositionSnapshotService.java src/main/java/com/example/quant/order`
Expected: no unintended changes from this task.

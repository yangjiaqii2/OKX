# Trading Analysis Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the documented position-risk, AI-plan, protection-order, contract-selection, OI, candle, and account-risk gaps without changing the intended strategy profitability parameters.

**Architecture:** Keep the existing Spring Boot modules and route every automated order through deterministic plan generation, risk-capped budget allocation, shared execution risk checks, and one protection owner. Split scanning into tradeability filtering before strategy ranking, persist the minimum state required for OI and daily risk, and fail closed when exchange facts are unavailable.

**Tech Stack:** Java 25 runtime, Spring Boot 3.2, Spring Data JPA, Flyway, MySQL 8, JUnit 5, AssertJ, Mockito, React/TypeScript only where status fields require display changes.

## Global Constraints

- Preserve `DISABLED / DEMO / LIVE`; never enable LIVE automatically.
- Do not change strategy thresholds to improve reported returns.
- AI may explain or downgrade but may not set executable direction, prices, leverage, or size.
- Exchange facts and instrument rules remain authoritative; unknown data is not zero.
- Use database uniqueness/transactions for persistent safety state; do not add Redis, Kafka, or another framework.
- Do not commit or push without an explicit user request.
- Update `docs/CODEBASE_DOC.md` after each completed logical task.

---

### Task 1: Deterministic plan and price geometry

**Files:**
- Modify: `src/main/java/com/example/quant/tradeplan/ContractTradePlanBuilder.java`
- Modify: `src/main/java/com/example/quant/position/PositionSizingService.java`
- Test: `src/test/java/com/example/quant/tradeplan/ContractTradePlanBuilderTest.java`
- Test: `src/test/java/com/example/quant/position/PositionSizingServiceTest.java`

**Interfaces:**
- Consumes: `ContractCandidate` deterministic direction and candidate Entry/SL/TP.
- Produces: `TradePlan` whose executable fields cannot be overridden by `AiAnalysisService`; invalid LONG/SHORT geometry returns `WATCH` or rejected sizing.

- [x] Add failing tests proving an AI reverse action and AI price/leverage changes do not change executable plan fields.
- [x] Run `mvn -DskipTests=false -Dtest=ContractTradePlanBuilderTest test` and confirm the new tests fail for the expected override.
- [x] Make `rulePlan` the sole executable draft, use AI only for explanation/downgrade, and make invalid geometry fail instead of returning `1.5R`.
- [x] Add failing direction-geometry tests to `PositionSizingServiceTest`, then implement signed LONG/SHORT geometry validation through the existing request context.
- [x] Run both test classes and review the focused diff; do not commit.

### Task 2: Risk-capped allocation and strict confirmation

**Files:**
- Modify: `src/main/java/com/example/quant/agent/budget/AutoTradeBudgetService.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java`
- Modify: `src/main/java/com/example/quant/order/OrderConfirmService.java`
- Modify: `src/main/java/com/example/quant/risk/ContractRiskService.java`
- Test: `src/test/java/com/example/quant/agent/budget/AutoTradeBudgetServiceTest.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeServiceTest.java`
- Test: `src/test/java/com/example/quant/order/OrderConfirmServiceTest.java`
- Test: `src/test/java/com/example/quant/risk/ContractRiskServiceTest.java`

**Interfaces:**
- Consumes: `TradePlan.suggestedMargin`, account equity/available balance, stop distance, score factor, configured hard gates.
- Produces: final margin `min(qualityAdjusted, planRiskCap, remaining, maxSingle, accountAvailable)` and a risk rejection when estimated stop loss exceeds the allowed loss.

- [x] Add failing budget tests showing score can only reduce the slot and `suggestedMargin` is an absolute cap.
- [x] Change allocation to use `qualityAdjustedBudgetUsdt` and pass `plan.suggestedMargin()` from `AutoTradeService`; account lookup failure blocks new automated entries.
- [x] Add failing confirmation tests showing auto mode cannot zero minimum volume/score, triple funding limits, or set 100% margin allowance.
- [x] Remove auto-specific hard-gate relaxation for margin, funding, volume and signal score.
- [x] Replace sample-safe runtime freshness values with measured REST candidate/orderbook timestamps; Public WS remains a separate unavailable source.
- [x] Add a failing `ContractRiskServiceTest` for actual stop loss above `accountEquity * maxSingleLossRate`, then implement the comparison using margin, leverage, and signed stop distance.
- [x] Run the six focused test classes and review the focused diff; do not commit.

### Task 3: Single protection owner

**Files:**
- Modify: `src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java`
- Modify: `src/main/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleService.java`
- Test: `src/test/java/com/example/quant/okxtrade/OkxTradeAdapterTest.java`
- Test: `src/test/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleServiceTest.java`

**Interfaces:**
- Consumes: normalized plan stop, actual filled size, existing OKX algo state.
- Produces: one emergency SL owner and lifecycle-created TP legs without a second SL; partial fill protection uses actual fills.

- [x] Add failing adapter test proving attached orders contain the normalized plan SL and no attached TP.
- [x] Update entry attachment to one planned emergency SL only.
- [x] Add failing lifecycle test proving full-fill recovery does not submit a second SL when the attached SL is present/reconciled.
- [x] Reuse the stable attached `algoClOrdId` and active-algo reconciliation before submitting TP legs; an unconfirmed attached SL keeps the order in protection failure/recovery instead of creating another SL.
- [x] Run adapter and lifecycle tests and review the focused diff; do not commit.

### Task 4: Tradeability-first contract scanning

**Files:**
- Modify: `src/main/java/com/example/quant/crypto/OkxContractScanner.java`
- Modify: `src/main/java/com/example/quant/crypto/ContractSignalAnalyzer.java`
- Create only if necessary: `src/main/java/com/example/quant/crypto/OpenInterestSnapshotEntity.java`
- Create only if necessary: `src/main/java/com/example/quant/crypto/OpenInterestSnapshotRepository.java`
- Create only if necessary: `src/main/resources/db/migration/V22__open_interest_snapshots.sql`
- Test: `src/test/java/com/example/quant/crypto/OkxContractScannerTest.java`
- Test: `src/test/java/com/example/quant/crypto/ContractSignalAnalyzerTest.java`

**Interfaces:**
- Consumes: OKX SWAP ticker, instrument metadata, orderbook, closed candles, funding, current and previous OI.
- Produces: quote-turnover-correct, direction-neutral prefiltered candidates; naturally aligned 20m bars; OI UNKNOWN when no comparable history exists.

- [x] Add failing ticker fixtures proving `volCcy24h * last` is used and liquidity filtering happens before any gain ranking/truncation.
- [x] Fix turnover and remove pre-filter gain Top 30; sort/rank only after hard tradeability filtering.
- [x] Add failing 20m tests for natural UTC boundaries, missing bars, and unsorted input; implement exact four-bar aggregation.
- [x] Add failing OI tests for first observation UNKNOWN and later aligned percentage change; implement the smallest persistent snapshot/repository path compatible with current scanner construction.
- [x] Disable reversal signals from automatic eligibility while retaining their analysis output for Shadow/inspection.
- [x] Run scanner and signal tests and review the focused diff; do not commit.

### Task 5: Persistent daily/account hard risk

**Files:**
- Create: `src/main/java/com/example/quant/risk/DailyRiskStateEntity.java`
- Create: `src/main/java/com/example/quant/risk/DailyRiskStateRepository.java`
- Create: `src/main/java/com/example/quant/risk/DailyRiskService.java`
- Create: `src/main/resources/db/migration/V23__daily_risk_state.sql`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java`
- Modify: `src/main/java/com/example/quant/order/OrderConfirmService.java`
- Test: `src/test/java/com/example/quant/risk/DailyRiskServiceTest.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeServiceTest.java`

**Interfaces:**
- Consumes: owner, UTC trading day, unique entry decision/order, reconciled managed-trade net PnL, exchange equity.
- Produces: `DailyRiskDecision` that blocks only risk-increasing entries when max trades, daily drawdown, consecutive losses, cooldown, or ledger uncertainty triggers.

- [x] Add failing persistence tests for max daily trades, consecutive losses/cooldown, UTC day rollover, restart recovery, and UNKNOWN ledger fail-closed.
- [x] Implement one owner/day state row and transactional/idempotent entry counting keyed by existing decision/order identity.
- [x] Call the service immediately before automated and manual risk-increasing submission; do not block cancel, protection, reduce-only, or close.
- [x] Record/update state only from reconciled exchange/ledger facts; never infer a loss from disappearance of an order.
- [x] Run daily risk and order/auto-trade tests and review the focused diff; do not commit.

### Task 6: Shadow evidence and documentation closeout

**Files:**
- Prefer modifying: `src/main/java/com/example/quant/agent/plan/TradePlanEntity.java`
- Prefer modifying: `src/main/java/com/example/quant/agent/plan/TradePlanRecordService.java`
- Create only if existing plan records cannot represent it: `src/main/resources/db/migration/V24__strategy_observation.sql`
- Test: `src/test/java/com/example/quant/agent/plan/TradePlanRecordServiceTest.java`
- Modify: `docs/CODEBASE_DOC.md`
- Modify: `docs/TRADING_ANALYSIS_OPTIMIZATION_STRATEGY_2026-08-10.md`

**Interfaces:**
- Consumes: candidate eligibility, strategy/signal type, feature snapshot, data timestamps, deterministic plan, later MFE/MAE/outcome facts.
- Produces: reproducible Shadow observations that never create pending orders or call OKX writes.

- [x] Add failing record tests for strategy version, signal pool, eligibility result, data timestamp, and AI explanation source.
- [x] Extend the existing plan/audit payload instead of creating a parallel framework; add a table only if queryable outcomes cannot fit existing records.
- [x] Prove `buildNoRiskPlan`/Shadow cannot create an executable pending order through the auto-trading path.
- [x] Update Chinese codebase documentation with actual implemented boundaries and remaining items; do not mark MFE/MAE calibration complete unless outcome updates exist.
- [x] Run `mvn clean package -DskipTests=false`, frontend build if API/UI types changed, `git diff --check`, and final diff review.

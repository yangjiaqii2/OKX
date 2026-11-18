# AI Wait Entry Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist AI pullback/retest decisions, trigger them once on a later closed 5m candle, and expire missed entries without chasing price.

**Architecture:** Keep `strategy_decision` as candle-decision authority and add one small `waiting_entry_intent` table keyed by its stable client order id. `AutoTradeService` processes a trigger before asking AI for a new decision, then routes the refreshed candidate through the existing plan, risk, budget, order, OKX and reconciliation chain. Existing limit-order timeout remains the no-chase mechanism; no second exchange order is created unless cancellation is conclusively reconciled.

**Tech Stack:** Java 17+, Spring Boot 3, Spring Data JPA, Flyway, MySQL 8, JUnit 5, Mockito, AssertJ.

## Global Constraints

- Keep `TRADING_MODE=DEMO`; never enable LIVE automatically.
- A WAIT intent never submits directly and never reserves budget.
- Only confirmed closed 5m candles may create or trigger an intent.
- Trigger execution must reuse every existing hard guard and stable `clOrdId` behavior.
- A missed pullback expires; it is never converted into a market chase.
- Do not add dependencies, schedulers, queues, or distributed infrastructure.
- Do not commit or push the dirty working tree.

---

### Task 1: Make AI decision and entry mode semantics executable

**Files:**
- Modify: `src/main/java/com/example/quant/agent/selection/AiContractSelectionService.java`
- Test: `src/test/java/com/example/quant/agent/selection/AiContractSelectionServiceTest.java`

**Interfaces:**
- Produces: a `ContractSelectionDecision` where `MARKET/LIMIT` remains `AI_PRIMARY`, and `WAIT_PULLBACK/WAIT_RETEST` becomes `AI_WAIT` while retaining selected symbol, direction, ranking and confidence.

- [ ] Add failing parser tests for `TRADE + WAIT_PULLBACK` normalization and canonical `WAIT + selectedInstId` parsing.
- [ ] Run `mvn -Dtest=AiContractSelectionServiceTest test` and confirm the new assertions fail.
- [ ] Update the prompt and parser so a wait mode cannot enter immediate order execution, but preserves the selected setup instead of falling into rule fallback.
- [ ] Run the focused test and confirm it passes.

### Task 2: Persist one active waiting intent per owner and symbol

**Files:**
- Create: `src/main/resources/db/migration/V27__waiting_entry_intent.sql`
- Create: `src/main/java/com/example/quant/agent/entry/WaitingEntryIntentStatus.java`
- Create: `src/main/java/com/example/quant/agent/entry/WaitingEntryIntentEntity.java`
- Create: `src/main/java/com/example/quant/agent/entry/WaitingEntryIntentRepository.java`
- Create: `src/main/java/com/example/quant/agent/entry/WaitingEntryIntentService.java`
- Test: `src/test/java/com/example/quant/agent/entry/WaitingEntryIntentServiceTest.java`

**Interfaces:**
- Consumes: `ContractCandidate`, `ContractSelectionDecision`, owner, stable decision client order id, and `Instant now`.
- Produces: `createOrGet(...)`, `evaluate(List<ContractCandidate>, Instant)`, and terminal transition methods. `evaluate` returns at most one `TriggeredIntent` containing the refreshed candidate, original direction and stable client order id.

- [ ] Add failing tests for duplicate creation, concurrent uniqueness recovery, no trigger before a newer closed candle, long pullback trigger, short retest trigger, expiry, stop invalidation and HIGH news invalidation.
- [ ] Run `mvn -Dtest=WaitingEntryIntentServiceTest test` and confirm failure because the persistence model does not exist.
- [ ] Add the Flyway table with unique `decision_client_order_id` and nullable unique `active_key`; terminal rows clear `active_key`.
- [ ] Implement the minimum transactional service. Use `referencePrice`, existing stop price as `invalidationPrice`, and `expireAt=createdAt+15m`; require a newer `decisionCandleTimestamp` and `entryTiming5m=READY` before triggering.
- [ ] Run the focused test and confirm it passes.

### Task 3: Route waiting and triggered decisions through the existing trading chain

**Files:**
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java`
- Modify: `src/main/java/com/example/quant/task/ContractMarketScanTask.java`
- Modify: `src/main/java/com/example/quant/strategydecision/StrategyDecisionStatus.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeServiceTest.java`
- Test: `src/test/java/com/example/quant/task/ContractMarketScanTaskTest.java`

**Interfaces:**
- Consumes: `WaitingEntryIntentService.TriggeredIntent` before a fresh AI call.
- Produces: `WAITING_ENTRY` result with an `instId`, `TRIGGERED` execution using original stable `clOrdId`, and strategy-decision status `WAITING_ENTRY`.

- [ ] Add failing tests that AI wait persists an intent without budget/order calls, a triggered intent bypasses a new AI choice but not hard gates, and repeated scans cannot submit it twice.
- [ ] Run the two focused test classes and confirm the new tests fail.
- [ ] Add the waiting service to `AutoTradeService`. Evaluate waiting intents first; when triggered, construct an internal MARKET selection for the refreshed candidate and reuse the existing execution loop. When AI selects WAIT, persist only the selected setup and return `WAITING_ENTRY`.
- [ ] Carry the original stable client order id into the pending order path and mark the intent `SUBMITTED`, `FAILED_SAFE`, or `RECONCILING` from the actual result.
- [ ] Map `WAITING_ENTRY` in `ContractMarketScanTask.completeDecisions` instead of incorrectly completing it as `NO_SIGNAL`.
- [ ] Run focused tests and confirm they pass.

### Task 4: Make missed LIMIT behavior explicitly no-chase

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleService.java`
- Test: `src/test/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleServiceTest.java`

**Interfaces:**
- Consumes: existing OKX order details and configured entry timeout.
- Produces: confirmed cancel followed by `ENTRY_TIMEOUT_CANCELLED`; cancel timeout/error keeps the order active/reconciling and never sends a replacement.

- [ ] Add a failing test proving an unfilled LIMIT is canceled after 15 minutes and no second `placeOrder` call occurs; add a failure test proving cancel uncertainty does not mark it canceled.
- [ ] Run the lifecycle focused test and confirm the new assertions fail.
- [ ] Set `entry-timeout-minutes` to 15 and tighten cancellation handling so only an acknowledged/already-terminal cancel may release budget and mark timeout-canceled.
- [ ] Do not implement automatic repricing in this pass: the safe maximum adjustment count is zero until a replacement can be represented as a separate persisted order and reconciled independently.
- [ ] Run the focused lifecycle test and confirm it passes.

### Task 5: Observability, documentation and complete verification

**Files:**
- Modify: `src/main/java/com/example/quant/agent/execution/FailureReasonClassifier.java`
- Modify: `frontend/src/pages/AutoTradeRecordList.tsx`
- Modify: `docs/CODEBASE_DOC.md`
- Modify: `README.md`

**Interfaces:**
- Produces: distinct `AI_WAITING_ENTRY`, `AI_WAIT_EXPIRED`, `AI_WAIT_INVALIDATED`, and `HARD_RISK_BLOCKED` reasons without presenting soft diagnostics as hard denial.

- [ ] Add the new reason/status labels using existing table components; do not redesign the page.
- [ ] Update Chinese codebase documentation with schema, lifecycle, safety boundary, affected files and verification results.
- [ ] Run `mvn clean package -DskipTests=false` and require zero failures.
- [ ] Run `cd frontend && npm run build` and require TypeScript/Vite success.
- [ ] Run `git diff --check` and verify the packaged JAR contains no `.env`.
- [ ] Build and restart only the DEMO Docker app, then verify health, `TradingMode=DEMO`, Private WS readiness, and that a natural AI wait creates one persisted intent without an OKX order.

# OKX Auto Trade Budget And Concurrency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Treat `marginUsdt` as the total auto-trade budget, reserve it atomically, prevent duplicate/concurrent auto orders, and harden pending-order/protection states.

**Architecture:** Add a focused in-memory budget reservation service matching the current in-memory `PendingOrderService`, then wire it into `AutoTradeService`, `PendingOrderService`, and `OrderConfirmService`. Keep OKX signing/account/manual/position-sync paths untouched; only extend the order gateway payload/idempotency and state transitions around automatic entries.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, AssertJ, existing in-memory pending-order model and existing OKX adapter.

---

### Task 1: Budget Allocation Model

**Files:**
- Create: `src/main/java/com/example/quant/agent/budget/AutoTradeBudgetService.java`
- Create: `src/main/java/com/example/quant/agent/budget/BudgetAllocation.java`
- Create: `src/main/java/com/example/quant/agent/budget/BudgetReservation.java`
- Modify: `src/main/java/com/example/quant/config/AgentProperties.java`
- Test: `src/test/java/com/example/quant/agent/budget/AutoTradeBudgetServiceTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify `marginUsdt=50` produces target 45, min target 40.
  - Verify slots 45/30/25 and scores 92/86/82 allocate 22.5/13.5/10, total 46.
  - Verify single position cap 25 on a 50 budget.
  - Verify reserved+used can never exceed 50 under concurrent reserve calls.

- [ ] **Step 2: Run tests and verify RED**
  - `mvn test -Dtest=AutoTradeBudgetServiceTest`

- [ ] **Step 3: Implement minimal budget service**
  - Add synchronized reservation map with `RESERVED`, `USED`, `RELEASED`.
  - Calculate `BudgetAllocation` from total budget, slot index, score factor, risk cap, remaining budget, and max single cap.
  - Add idempotent `reserve`, `markUsed`, `release`.

- [ ] **Step 4: Run tests and verify GREEN**
  - `mvn test -Dtest=AutoTradeBudgetServiceTest`

### Task 2: PendingOrder Budget And State Machine

**Files:**
- Modify: `src/main/java/com/example/quant/order/OrderStatus.java`
- Modify: `src/main/java/com/example/quant/order/PendingOrder.java`
- Modify: `src/main/java/com/example/quant/order/PendingOrderService.java`
- Modify: `src/main/java/com/example/quant/order/PendingOrderView.java`
- Test: `src/test/java/com/example/quant/order/PendingOrderServiceTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify creating an auto pending order stores `orderMarginUsdt`, `budgetReservationId`, and budget allocation JSON.
  - Verify compare-and-set allows only one transition from `BUDGET_RESERVED` to `CONFIRMING`.
  - Verify expired/rejected/cancelled orders release reserved budget through caller hooks.

- [ ] **Step 2: Run tests and verify RED**
  - `mvn test -Dtest=PendingOrderServiceTest`

- [ ] **Step 3: Implement states and fields**
  - Add statuses requested by spec while keeping old statuses compatible.
  - Add `orderMarginUsdt`, `budgetReservationId`, `clientOrderId`, `budgetAllocationJson`.
  - Add synchronized compare-and-set transition methods.

- [ ] **Step 4: Run tests and verify GREEN**
  - `mvn test -Dtest=PendingOrderServiceTest`

### Task 3: AutoTradeService Budget, Locks, Symbol Idempotency

**Files:**
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java`
- Modify: `src/main/java/com/example/quant/agent/execution/FailureReasonClassifier.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeServiceTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify `marginUsdt=50` does not pass 50 as each order margin; first three orders use 22.5/13.5/10 for scores 92/86/82.
  - Verify two concurrent `evaluateAndExecute` calls only allow one execution.
  - Verify same symbol in-flight is skipped and falls back.
  - Verify budget reserve failure stops round or skips according to classification.

- [ ] **Step 2: Run tests and verify RED**
  - `mvn test -Dtest=AutoTradeServiceTest`

- [ ] **Step 3: Wire budget service**
  - Replace old margin allocator with budget allocation/reservation.
  - Reserve budget before creating `PendingOrder`.
  - Release reservation on plan/watch/pre-confirm/confirm rejection.
  - Keep at most one auto execution flow and symbol TTL locks.

- [ ] **Step 4: Run tests and verify GREEN**
  - `mvn test -Dtest=AutoTradeServiceTest`

### Task 4: OrderConfirmService Budget State And CAS

**Files:**
- Modify: `src/main/java/com/example/quant/order/OrderConfirmService.java`
- Test: `src/test/java/com/example/quant/order/OrderConfirmServiceTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify auto confirm ignores the passed total budget and uses `PendingOrder.orderMarginUsdt`.
  - Verify double confirm only submits OKX once.
  - Verify OKX rejection releases reserved budget.
  - Verify timeout/unknown submit status is classified and not blindly retried.

- [ ] **Step 2: Run tests and verify RED**
  - `mvn test -Dtest=OrderConfirmServiceTest`

- [ ] **Step 3: Implement confirmation state flow**
  - Allow only `PENDING_CONFIRM`/`BUDGET_RESERVED`.
  - Transition to `CONFIRMING`, then `SUBMITTING`, then `SUBMITTED`.
  - Mark reservation USED on successful submit; RELEASED on rejection/failure before submit.

- [ ] **Step 4: Run tests and verify GREEN**
  - `mvn test -Dtest=OrderConfirmServiceTest`

### Task 5: OKX Client Order Idempotency And Protection Recovery

**Files:**
- Modify: `src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxOrderGateway.java`
- Test: `src/test/java/com/example/quant/okxtrade/OkxTradeAdapterTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify entry payload uses stable `AUTO_...` clOrdId from `PendingOrder`.
  - Verify submit timeout queries by clOrdId before any retry.
  - Verify stop-loss failure triggers close position with configured action.
  - Verify TP failure with stop-loss success returns warning, not immediate close.

- [ ] **Step 2: Run tests and verify RED**
  - `mvn test -Dtest=OkxTradeAdapterTest`

- [ ] **Step 3: Implement idempotency and protection result handling**
  - Use `PendingOrder.clientOrderId` for entry.
  - Add query-by-clOrdId payload support through existing `queryOrder`.
  - Submit stop-loss first; classify stop-loss failure as protection failed.

- [ ] **Step 4: Run tests and verify GREEN**
  - `mvn test -Dtest=OkxTradeAdapterTest`

### Task 6: Scan Lock And Recovery Task

**Files:**
- Modify: `src/main/java/com/example/quant/task/ContractMarketScanTask.java`
- Create: `src/main/java/com/example/quant/agent/execution/AutoTradeRecoveryTask.java`
- Test: `src/test/java/com/example/quant/task/ContractMarketScanTaskTest.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeRecoveryTaskTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify second scan skips while first scan is running.
  - Verify expired pending orders release reserved budget.
  - Verify stale reserved budget releases idempotently.

- [ ] **Step 2: Run tests and verify RED**
  - `mvn test -Dtest=ContractMarketScanTaskTest,AutoTradeRecoveryTaskTest`

- [ ] **Step 3: Implement lock and recovery**
  - Add non-blocking scan lock and timeout check.
  - Add scheduled recovery that releases stale reserved budgets and expired pending orders.

- [ ] **Step 4: Run tests and verify GREEN**
  - `mvn test -Dtest=ContractMarketScanTaskTest,AutoTradeRecoveryTaskTest`

### Task 7: Final Verification

**Files:**
- Modify as needed only in scoped files.

- [ ] **Step 1: Run focused tests**
  - `mvn test -Dtest=AutoTradeBudgetServiceTest,PendingOrderServiceTest,AutoTradeServiceTest,OrderConfirmServiceTest,OkxTradeAdapterTest,ContractMarketScanTaskTest,AutoTradeRecoveryTaskTest`

- [ ] **Step 2: Run full tests**
  - `mvn test`

- [ ] **Step 3: Summarize delivery**
  - Include files, budget semantics, config, allocation formula, locks, state machine, idempotency, recovery, and explicit untouched areas.

# Auto Trade Real Loop Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the current OKX auto-trade flow into a restart-aware, idempotent, auditable first-stage trading loop with persisted controls, budgets, recovery, close records, current-order visibility, review records, and currency display support.

**Architecture:** Keep the existing Spring Boot service boundaries and React pages. Add focused JPA entities/repositories for runtime settings, budget reservations, persisted pending orders, and close-position records; extend the OKX gateway with current normal/algo order queries and clOrdId recovery; expose compact REST APIs for review and current-order views; update frontend pages with additional tabs rather than replacing existing workflows.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Data JPA, Flyway, JUnit 5, AssertJ, React 18, Vite, MUI.

**Execution status after 2026-06-18 implementation:**

- Completed: runtime control persistence, budget reservation persistence, clOrdId timeout recovery, UNKNOWN submit recovery, close request records, OKX current normal/algo order APIs, review/failure fields, NO_RISK hard spread gate, lifecycle entry timeout, partial-fill protection compensation, sideways TP tightening, max-hold close submission, frontend lifecycle/current-order/close-record/review views, and USD/RMB display toggle.
- Verified: `mvn test` passed with 157 tests; `npm --prefix frontend run build` passed with the existing Vite chunk-size warning.
- Deferred to the next phase: full `PendingOrder` table-backed restoration, enable-time OKX key verification binding, final `CLOSED` close sync with budget release, bulk TP/SL cancellation/invalid marking after close, persisted protection-order retry state, OKX fill/fee/funding ingestion, and exact realized PnL reconciliation.

---

### Task 1: Persist Runtime Control State

**Files:**
- Create: `src/main/java/com/example/quant/system/SystemControlEntity.java`
- Create: `src/main/java/com/example/quant/system/SystemControlRepository.java`
- Modify: `src/main/java/com/example/quant/system/SystemControlService.java`
- Modify: `src/main/resources/db/migration/V11__auto_trade_real_loop_phase1.sql`
- Test: `src/test/java/com/example/quant/system/SystemControlServiceTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify enabling auto trade persists enabled flag, owner, risk mode, total budget, no-risk minimum score, and minimum leverage.
  - Verify a fresh `SystemControlService` using the same repository loads the persisted status.
  - Verify disabling auto trade and emergency stop persist immediately.

- [ ] **Step 2: Run tests and verify RED**
  - Run: `mvn test -Dtest=SystemControlServiceTest`
  - Expected: fails because `SystemControlService` has no repository-backed constructor or persisted state.

- [ ] **Step 3: Implement minimal persistence**
  - Add a single-row `system_control_state` table keyed by `id='GLOBAL'`.
  - Load state on construction when a repository is present; keep existing no-repository constructors for unit tests.
  - Save state after enable, disable, emergency stop, and resume.

- [ ] **Step 4: Run tests and verify GREEN**
  - Run: `mvn test -Dtest=SystemControlServiceTest`

### Task 2: Persist Auto-Trade Budget Reservations

**Files:**
- Create: `src/main/java/com/example/quant/agent/budget/BudgetReservationEntity.java`
- Create: `src/main/java/com/example/quant/agent/budget/BudgetReservationRepository.java`
- Modify: `src/main/java/com/example/quant/agent/budget/AutoTradeBudgetService.java`
- Modify: `src/main/resources/db/migration/V11__auto_trade_real_loop_phase1.sql`
- Test: `src/test/java/com/example/quant/agent/budget/AutoTradeBudgetServiceTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify `reserveBudget` writes a `RESERVED` row.
  - Verify a fresh service instance with the same repository reports the existing reserved budget.
  - Verify `markUsed` and `release` update the row idempotently.

- [ ] **Step 2: Run tests and verify RED**
  - Run: `mvn test -Dtest=AutoTradeBudgetServiceTest`

- [ ] **Step 3: Implement repository-backed reservations**
  - Keep the in-memory map for no-repository tests.
  - When a repository exists, load active rows for budget calculations and write every state transition.
  - Treat `RESERVED` as pre-occupied budget, `USED` as used budget, and `RELEASED` as available.

- [ ] **Step 4: Run tests and verify GREEN**
  - Run: `mvn test -Dtest=AutoTradeBudgetServiceTest`

### Task 3: Recover OKX Submit Timeout by Stable clOrdId

**Files:**
- Modify: `src/main/java/com/example/quant/okxtrade/OkxOrderGateway.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxRestOrderGateway.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java`
- Modify: `src/main/java/com/example/quant/order/OrderConfirmService.java`
- Modify: `src/main/java/com/example/quant/task/AutoTradeRecoveryTask.java`
- Test: `src/test/java/com/example/quant/okxtrade/OkxTradeAdapterTest.java`
- Test: `src/test/java/com/example/quant/task/AutoTradeRecoveryTaskTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify a place-order timeout queries OKX by `clOrdId`.
  - Verify an existing OKX order after timeout returns `SUBMITTED` without releasing budget.
  - Verify a confirmed missing `clOrdId` returns a non-executed result and allows budget release.
  - Verify recovery of `UNKNOWN_SUBMIT_STATUS` queries by `clOrdId` and either marks `SUBMITTED`/used or `REJECTED`/released.

- [ ] **Step 2: Run tests and verify RED**
  - Run: `mvn test -Dtest=OkxTradeAdapterTest,AutoTradeRecoveryTaskTest`

- [ ] **Step 3: Implement idempotent query path**
  - Query `/api/v5/trade/order` with `instId` and `clOrdId` when a timeout occurs.
  - Add `OkxTradeAdapter.recoverUnknownSubmitStatus(PendingOrder)` for scheduled recovery.
  - Do not release budget when OKX status remains unknown.

- [ ] **Step 4: Run tests and verify GREEN**
  - Run: `mvn test -Dtest=OkxTradeAdapterTest,AutoTradeRecoveryTaskTest`

### Task 4: Persist Pending Orders and Broaden Review Records

**Files:**
- Create: `src/main/java/com/example/quant/order/PendingOrderEntity.java`
- Create: `src/main/java/com/example/quant/order/PendingOrderRepository.java`
- Modify: `src/main/java/com/example/quant/order/PendingOrderService.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeRecordEntity.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeRecordService.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeRecordView.java`
- Modify: `src/main/resources/db/migration/V11__auto_trade_real_loop_phase1.sql`
- Test: `src/test/java/com/example/quant/order/PendingOrderServiceTest.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeRecordServiceTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify auto pending orders persist status, `clientOrderId`, budget reservation id, and trade plan id.
  - Verify a fresh pending-order service restores unfinished orders.
  - Verify `SKIPPED`, `REJECTED`, `FAILED`, and `UNKNOWN_SUBMIT_STATUS` records are persisted and searchable.

- [ ] **Step 2: Run tests and verify RED**
  - Run: `mvn test -Dtest=PendingOrderServiceTest,AutoTradeRecordServiceTest`

- [ ] **Step 3: Implement persistence and review fields**
  - Persist pending orders on create and status transitions.
  - Add review fields: `scan_id`, `score`, `final_rank_score`, `signal_type`, `risk_mode`, `stage`, `reason_code`, `reason_message`, `fallback_allowed`, and `updated_at`.
  - Remove the service-layer default that hides non-EXECUTED records when no status is supplied.

- [ ] **Step 4: Run tests and verify GREEN**
  - Run: `mvn test -Dtest=PendingOrderServiceTest,AutoTradeRecordServiceTest`

### Task 5: Close Records and Current OKX Orders API

**Files:**
- Create: `src/main/java/com/example/quant/account/ClosePositionRecordEntity.java`
- Create: `src/main/java/com/example/quant/account/ClosePositionRecordRepository.java`
- Create: `src/main/java/com/example/quant/account/ClosePositionRecordView.java`
- Create: `src/main/java/com/example/quant/okxtrade/OkxCurrentOrderView.java`
- Create: `src/main/java/com/example/quant/okxtrade/OkxCurrentOrderService.java`
- Create: `src/main/java/com/example/quant/controller/OkxOrderController.java`
- Modify: `src/main/java/com/example/quant/account/PositionCloseService.java`
- Modify: `src/main/java/com/example/quant/controller/AccountController.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxOrderGateway.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxRestOrderGateway.java`
- Modify: `src/main/resources/db/migration/V11__auto_trade_real_loop_phase1.sql`
- Test: `src/test/java/com/example/quant/account/PositionCloseServiceTest.java`
- Test: `src/test/java/com/example/quant/okxtrade/OkxCurrentOrderServiceTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify clicking close creates `CLOSE_SUBMITTED` record with user, symbol, side, margin mode, clOrdId-like local id, source, and OKX response id.
  - Verify current normal orders and current algo orders are mapped to the required visible fields.

- [ ] **Step 2: Run tests and verify RED**
  - Run: `mvn test -Dtest=PositionCloseServiceTest,OkxCurrentOrderServiceTest`

- [ ] **Step 3: Implement close and current-order APIs**
  - Persist a close request before returning.
  - Expose `GET /api/quant/account/positions/close-records`.
  - Expose `GET /api/quant/okx/orders/current` and `GET /api/quant/okx/orders/algo`.

- [ ] **Step 4: Run tests and verify GREEN**
  - Run: `mvn test -Dtest=PositionCloseServiceTest,OkxCurrentOrderServiceTest`

### Task 6: Frontend Views and Currency Display

**Files:**
- Modify: `frontend/src/api/quantApi.ts`
- Modify: `frontend/src/api/quantDataProvider.ts`
- Modify: `frontend/src/pages/AutoTradeRecordList.tsx`
- Modify: `frontend/src/pages/PendingOrderList.tsx`
- Modify: `frontend/src/pages/Dashboard.tsx`
- Modify: `frontend/src/pages/ContractCandidateList.tsx`
- Modify: `frontend/src/components/PositionSnapshotTable.tsx`
- Create: `frontend/src/pages/CurrentOkxOrderList.tsx`
- Create: `frontend/src/pages/ClosePositionRecordList.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Add data-provider resources**
  - Map `okx-current-orders`, `okx-current-algo-orders`, and `close-position-records`.
  - Keep original USDT values and add display-only currency conversion helpers.

- [ ] **Step 2: Add pages**
  - Add “当前委托/保护单” list with normal and algo order tabs.
  - Add “平仓记录” list.
  - Expand “自动交易复盘” to show non-EXECUTED statuses and reason fields.

- [ ] **Step 3: Add currency switch**
  - Add USD/RMB toggle on dashboard/account-facing balances.
  - Use a fixed fallback exchange rate when no live rate is configured; do not feed converted values into trading payloads.

- [ ] **Step 4: Verify frontend build**
  - Run: `npm --prefix frontend run build`

### Task 7: Documentation and Verification

**Files:**
- Modify: `docs/contract-agent-loop-design.md`

- [ ] **Step 1: Update Chinese documentation**
  - Document persisted runtime state, persisted budget semantics, timeout clOrdId recovery, current-order APIs, close records, review fields, and frontend pages.

- [ ] **Step 2: Run focused backend tests**
  - Run: `mvn test -Dtest=SystemControlServiceTest,AutoTradeBudgetServiceTest,OkxTradeAdapterTest,AutoTradeRecoveryTaskTest,PendingOrderServiceTest,AutoTradeRecordServiceTest,PositionCloseServiceTest,OkxCurrentOrderServiceTest`

- [ ] **Step 3: Run full backend and frontend verification**
  - Run: `mvn test`
  - Run: `npm --prefix frontend run build`

- [ ] **Step 4: Summarize remaining phase-2 work**
  - Explicitly list anything not fully implemented, especially fill polling, protection retry backoff, OKX fills/fees/funding ingestion, and exact realized PnL reconciliation.

### Task 8: Auto-Trade Lifecycle Timeouts

**Files:**
- Create: `src/main/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleService.java`
- Create: `src/main/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleSnapshot.java`
- Modify: `src/main/java/com/example/quant/config/AgentProperties.java`
- Modify: `src/main/java/com/example/quant/task/AutoTradeRecoveryTask.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxOrderGateway.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxRestOrderGateway.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java`
- Modify: `src/main/java/com/example/quant/agent/execution/TradeOrderEntity.java`
- Modify: `src/main/resources/db/migration/V11__auto_trade_real_loop_phase1.sql`
- Test: `src/test/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleServiceTest.java`

- [ ] **Step 1: Write failing tests**
  - Verify an unfilled entry order older than 10 minutes is cancelled, marked `ENTRY_TIMEOUT_CANCELLED`, releases budget, and can fall back next round.
  - Verify a partially filled entry order cancels the remaining size and submits protection from actual `filledSize` and `avgPx`.
  - Verify a 3-hour auto-trade position with PnL between -1% and +1% enters `SIDEWAYS_TIMEOUT_TP_ADJUSTED`.
  - Verify long and short small-profit TP trigger prices move in the correct direction.
  - Verify an 8-hour auto-trade position submits close-position and becomes `CLOSE_SUBMITTED`.
  - Verify close completion releases budget and marks old TP/SL invalid or cancelled.

- [ ] **Step 2: Run tests and verify RED**
  - Run: `mvn test -Dtest=AutoTradeLifecycleServiceTest`

- [ ] **Step 3: Implement lifecycle monitor**
  - Add `quant.agent.lifecycle.*` defaults: `entry-timeout-minutes=10`, `sideways-position-hours=3`, `sideways-pnl-range-pct=1`, `sideways-exit-profit-pct=0.3`, `max-hold-hours=8`, `max-hold-action=CLOSE_POSITION`, `sideways-action=TIGHTEN_TAKE_PROFIT`, `move-stop-to-breakeven=false`.
  - Query OKX current positions, normal orders, and algo orders through gateway abstractions.
  - Cancel stale unfilled entries, tighten TP for sideways positions, and close max-hold positions.
  - Persist lifecycle status and next automatic action for frontend display.

- [ ] **Step 4: Run tests and verify GREEN**
  - Run: `mvn test -Dtest=AutoTradeLifecycleServiceTest,AutoTradeRecoveryTaskTest`

### Task 9: Lifecycle Frontend Display

**Files:**
- Create: `frontend/src/pages/AutoTradeLifecycleList.tsx`
- Modify: `frontend/src/api/quantApi.ts`
- Modify: `frontend/src/api/quantDataProvider.ts`
- Modify: `frontend/src/pages/AutoTradeRecordList.tsx`
- Modify: `frontend/src/components/PositionSnapshotTable.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Add lifecycle resource**
  - Expose lifecycle status fields: `NORMAL`, `ENTRY_PENDING`, `ENTRY_TIMEOUT_CANCELLED`, `SIDEWAYS_TIMEOUT`, `SIDEWAYS_TIMEOUT_TP_ADJUSTED`, `MAX_HOLD_TIMEOUT`, `CLOSE_SUBMITTED`, `CLOSED`, `EMERGENCY_ATTENTION_REQUIRED`.

- [ ] **Step 2: Render budget and protection separately**
  - Show entry margin/budget used separately from reduce-only TP/SL protection orders.
  - Show entry wait duration, hold duration, unrealized PnL percent, sideways timeout, max-hold timeout, and next automatic action.

- [ ] **Step 3: Verify frontend build**
  - Run: `npm --prefix frontend run build`

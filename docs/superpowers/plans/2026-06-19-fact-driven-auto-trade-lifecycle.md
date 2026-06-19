# Fact Driven Auto Trade Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将自动交易从“提交 OKX 委托”升级为可从数据库和 OKX 当前状态恢复的事实驱动生命周期。

**Architecture:** 以 `PendingOrder`、`TradeOrder`、`ClosePositionRecord` 为现有事实表，新增 `trade_event` 作为时间线事实，并在恢复任务中强制切换到自动交易 owner 用户上下文。入场保护单只由成交事实触发，持仓不可用时生命周期进入明确降级状态，不能把未知当无持仓。

**Tech Stack:** Spring Boot, Spring Data JPA, Flyway SQL migrations, JUnit 5, AssertJ, Mockito, React, Vite, MUI.

---

## File Structure

- Modify: `src/main/java/com/example/quant/task/AutoTradeRecoveryTask.java` - owner 上下文恢复入口、未知提交安全等待、事件记录。
- Modify: `src/main/java/com/example/quant/order/OrderConfirmService.java` - 提交状态未知异常分类与预算保留。
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java` - 捕获 UNKNOWN submit 时不释放预算。
- Modify: `src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java` - LIMIT 只记录入场提交，MARKET 成交未确认不盲交保护单。
- Modify: `src/main/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleService.java` - 成交事实查询、持仓不可用状态、NET 模式保护单 payload。
- Modify: `src/main/java/com/example/quant/account/ClosePositionRecoveryService.java` and `ClosePositionRecordRepository.java` - 按当前用户恢复平仓记录。
- Create: `src/main/java/com/example/quant/agent/event/TradeEventEntity.java`, `TradeEventRepository.java`, `TradeEventService.java`, `TradeEventType.java` - lifecycle 事件事实。
- Create: `src/main/resources/db/migration/V14__fact_driven_trade_lifecycle.sql` - trade_event、trade_review、必要索引与字段。
- Modify/Create: lifecycle fact DTO/service/controller methods under `src/main/java/com/example/quant/agent/lifecycle/` and `AutoTradeRecordController.java`.
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeProfitService.java`, `AutoTradeProfitSummary.java` - 收益数据质量字段。
- Create/Modify: system FX classes and `SystemControlController.java` - `/api/quant/system/fx-rate?base=USD&quote=CNY`。
- Modify: `frontend/src/api/quantApi.ts`, `frontend/src/pages/Dashboard.tsx` - RMB 使用后端汇率，失败回退 USD，展示汇率来源/时间和收益质量。
- Modify: `src/main/java/com/example/quant/crypto/ContractSignalAnalyzer.java` and scenario tests - 做空反转门槛。
- Modify: `docs/contract-agent-loop-design.md` - 中文文档和变更日志。

## Tasks

### Task 1: Recovery Owner Context

**Files:**
- Test: `src/test/java/com/example/quant/task/AutoTradeRecoveryTaskTest.java`
- Modify: `src/main/java/com/example/quant/task/AutoTradeRecoveryTask.java`

- [ ] **Step 1: Write failing tests**

Add tests named `scheduledRecoveryRunsAsAutoTradeOwnerForBudgetAndOkxContext` and `skipsRecoveryWhenOwnerIsMissing`.

Expected assertions:
- `AuthUserContext.currentUsername()` observed by `OkxCurrentOrderSyncService`, `OkxTradeAdapter`, `AutoTradeLifecycleService`, `ClosePositionRecoveryService`, and `AutoTradeBudgetService` is `userA`.
- A `local-admin` reservation with the same id/symbol is not used or released during owner recovery.
- Missing owner records `RECOVERY_FAILED` and does not call OKX/budget/lifecycle services.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -Dtest=AutoTradeRecoveryTaskTest test`
Expected: new tests fail because recovery runs without `AuthUserContext.callAs(ownerUsername, ...)`.

- [ ] **Step 3: Implement owner wrapping**

Inject `SystemControlService` and `AgentAuditLogger`/`TradeEventService` where available. In `runOnce`, read `systemControlService.autoTradeOwnerUsername()`, require non-blank owner, then execute all recovery work via:

```java
return AuthUserContext.callAs(ownerUsername, () -> runOnceAsOwner(now));
```

Skip and record failed recovery when owner is blank or OKX owner calls throw credential/key failures.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `mvn -Dtest=AutoTradeRecoveryTaskTest test`
Expected: all tests pass.

### Task 2: Unknown Submit Budget Safety

**Files:**
- Test: `src/test/java/com/example/quant/order/OrderConfirmServiceTest.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeServiceTest.java`
- Modify: `src/main/java/com/example/quant/order/OrderConfirmService.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java`
- Modify: `src/main/java/com/example/quant/task/AutoTradeRecoveryTask.java`

- [ ] **Step 1: Write failing tests**

Add tests:
- `autoConfirmationSubmitTimeoutMarksUnknownAndKeepsReservedBudget`
- `autoTradeDoesNotReleaseBudgetWhenConfirmThrowsAfterUnknownSubmit`
- Adjust unknown clOrdId missing recovery so it releases only after `submittedAt` or `confirmedAt` age exceeds `unknownSubmitStatusTimeoutSeconds`.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -Dtest=OrderConfirmServiceTest,AutoTradeServiceTest,AutoTradeRecoveryTaskTest test`
Expected: budget is currently released as `PLAN_OR_CONFIRM_FAILED` or unknown-missing releases immediately.

- [ ] **Step 3: Implement exception classification**

Add `OrderSubmitStatusUnknownException extends RuntimeException` in `com.example.quant.order`. Throw it from `OrderConfirmService` when timeout leaves order in `UNKNOWN_SUBMIT_STATUS`. In `AutoTradeService`, catch this type before generic `RuntimeException`, remove in-flight marker but keep reservation reserved and return/record `UNKNOWN_SUBMIT_STATUS`.

- [ ] **Step 4: Add safety waiting**

When OKX query returns `OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT`, release only if the unknown order age is greater than `agentProperties.recovery().unknownSubmitStatusTimeoutSeconds()`. Otherwise keep attention required and event `ENTRY_SUBMIT_UNKNOWN`.

- [ ] **Step 5: Run tests and verify GREEN**

Run: `mvn -Dtest=OrderConfirmServiceTest,AutoTradeServiceTest,AutoTradeRecoveryTaskTest test`
Expected: all tests pass.

### Task 3: Entry Fill Driven Protection

**Files:**
- Test: `src/test/java/com/example/quant/okxtrade/OkxTradeAdapterTest.java`
- Test: `src/test/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleServiceTest.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java`
- Modify: `src/main/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleService.java`

- [ ] **Step 1: Write failing tests**

Add tests:
- `limitOrderSubmitDoesNotSubmitProtectionWhenNoFillIsConfirmed`
- `marketOrderWithoutConfirmedFillReturnsEntryFillUnconfirmed`
- `lifecycleSubmitsProtectionAfterFullEntryFill`

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -Dtest=OkxTradeAdapterTest,AutoTradeLifecycleServiceTest test`
Expected: LIMIT currently submits protection immediately.

- [ ] **Step 3: Implement adapter behavior**

For non-market orders, after OKX accepts entry:
- record entry `SUBMITTED`;
- return executed live order message `ENTRY_SUBMITTED_WAITING_FILL`;
- do not call `placeProtectionOrders`.

For market orders, query fill; if `accFillSz <= 0`, record no protection and return `ENTRY_FILL_UNCONFIRMED`.

- [ ] **Step 4: Implement lifecycle full-fill protection**

When order status is `SUBMITTED`, query OKX order before stale timeout. If state is `filled` and `accFillSz > 0`, submit protection for actual `accFillSz`, mark `PROTECTION_SUBMITTED`, mark budget used, and write events.

- [ ] **Step 5: Run tests and verify GREEN**

Run: `mvn -Dtest=OkxTradeAdapterTest,AutoTradeLifecycleServiceTest test`
Expected: all tests pass.

### Task 4: Position Sync Unavailable Is Not No Position

**Files:**
- Test: `src/test/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleServiceTest.java`
- Modify: `src/main/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleService.java`

- [ ] **Step 1: Write failing test**

Add `positionSnapshotFailureDoesNotCancelReleaseOrClose`.

Expected: status remains unchanged, budget remains `USED` or `RESERVED`, no cancel/close/algo calls occur, result exposes `positionSyncUnavailable=1`, and snapshots show `POSITION_SYNC_UNAVAILABLE`.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -Dtest=AutoTradeLifecycleServiceTest test`

- [ ] **Step 3: Implement explicit position sync state**

Replace `positions()` returning empty on exception with `PositionSyncResult.available/unavailable`. In `runOnce`, when unavailable, skip entry cancel, sideways TP, max-hold close and no-position decisions. In `snapshots`, report lifecycle status and next action as `POSITION_SYNC_UNAVAILABLE`.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `mvn -Dtest=AutoTradeLifecycleServiceTest test`

### Task 5: Close Recovery User Isolation

**Files:**
- Test: `src/test/java/com/example/quant/account/ClosePositionRecoveryServiceTest.java`
- Modify: `src/main/java/com/example/quant/account/ClosePositionRecoveryService.java`
- Modify: `src/main/java/com/example/quant/account/ClosePositionRecordRepository.java`

- [ ] **Step 1: Write failing test**

Add `recoversOnlyCurrentOwnersCloseSubmittedRecords`.

Expected: under `AuthUserContext.callAs("userA", ...)`, only userA `CLOSE_SUBMITTED` records are loaded and mutated; userB records remain unchanged.

- [ ] **Step 2: Run test and verify RED**

Run: `mvn -Dtest=ClosePositionRecoveryServiceTest test`

- [ ] **Step 3: Implement repository query**

Add `List<ClosePositionRecordEntity> findByUserNameAndStatus(String userName, String status)`. Use current username for submitted close records.

- [ ] **Step 4: Run test and verify GREEN**

Run: `mvn -Dtest=ClosePositionRecoveryServiceTest test`

### Task 6: Lifecycle Protection posSide Mode

**Files:**
- Test: `src/test/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleServiceTest.java`
- Modify: `src/main/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleService.java`

- [ ] **Step 1: Write failing test**

Add `netModeProtectionPayloadOmitsPosSide`.

- [ ] **Step 2: Run test and verify RED**

Run: `mvn -Dtest=AutoTradeLifecycleServiceTest test`

- [ ] **Step 3: Inject `OkxPositionModeProvider`**

Only add `posSide` in `algoPayload` when provider returns `LONG_SHORT`.

- [ ] **Step 4: Run test and verify GREEN**

Run: `mvn -Dtest=AutoTradeLifecycleServiceTest test`

### Task 7: FX Rate API and Dashboard RMB

**Files:**
- Test: `src/test/java/com/example/quant/controller/SystemControlControllerTest.java`
- Modify/Create: system FX service/record/controller
- Modify: `frontend/src/api/quantApi.ts`
- Modify: `frontend/src/pages/Dashboard.tsx`

- [ ] **Step 1: Write backend test**

Add `returnsConfiguredUsdCnyFxRate` for `/api/quant/system/fx-rate?base=USD&quote=CNY`.

- [ ] **Step 2: Implement simple configured FX service**

Return `base`, `quote`, `rate`, `source`, `updatedAt`. Default may be property-backed static config, not frontend hard-code.

- [ ] **Step 3: Update frontend**

Load FX rate during dashboard load. `RMB` toggle uses backend rate only when available; failed FX call forces USD display and shows warning/helper.

- [ ] **Step 4: Verify**

Run: `mvn -Dtest=SystemControlControllerTest test` and `npm --prefix frontend test` if available, otherwise `npm --prefix frontend run build`.

### Task 8: Profit Data Quality

**Files:**
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeProfitServiceTest.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeProfitService.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeProfitSummary.java`
- Modify: `frontend/src/pages/Dashboard.tsx`

- [ ] **Step 1: Write failing test**

Expect exact data quality values:
- `ESTIMATED_UNREALIZED_ONLY`
- `CLOSE_RECORD_ESTIMATED`
- `OKX_FILLS_NET_PNL` reserved for future fills/bills.

- [ ] **Step 2: Implement service labels**

Close-record-only realized PnL returns `CLOSE_RECORD_ESTIMATED`, not complete net PnL. Keep message explicit about missing fills/bills.

- [ ] **Step 3: Update frontend labels**

Display `估算` or `真实净收益` based on data quality.

- [ ] **Step 4: Run tests**

Run: `mvn -Dtest=AutoTradeProfitServiceTest test`.

### Task 9: Trade Event Timeline

**Files:**
- Create migration and event classes.
- Modify services to write key events.
- Test: focused service tests for event persistence.

- [ ] **Step 1: Add migration**

Create `trade_event` with fields required in the user request and indexes by `(user_name, created_at)`, `pending_order_id`, `auto_trade_record_id`, `trade_order_id`.

- [ ] **Step 2: Add entity/repository/service**

`TradeEventService.record(...)` resolves current username and saves best-effort events.

- [ ] **Step 3: Emit events**

Emit at least `BUDGET_RESERVED`, `ENTRY_SUBMITTED`, `ENTRY_SUBMIT_UNKNOWN`, `ENTRY_FILLED`, `ENTRY_PARTIAL_FILLED`, `ENTRY_TIMEOUT_CANCELLED`, `PROTECTION_SUBMITTED`, `PROTECTION_FAILED`, `SIDEWAYS_TP_REPLACED`, `MAX_HOLD_CLOSE_SUBMITTED`, `MANUAL_CLOSE_SUBMITTED`, `CLOSE_CONFIRMED`, `BUDGET_RELEASED`, `RECOVERY_FAILED`.

- [ ] **Step 4: Run tests**

Run targeted event service tests and lifecycle/recovery tests.

### Task 10: Fact Driven Lifecycle View

**Files:**
- Modify/Create lifecycle fact DTO/service.
- Modify: `src/main/java/com/example/quant/controller/AutoTradeRecordController.java`
- Modify: `frontend/src/pages/AutoTradeLifecycleList.tsx`

- [ ] **Step 1: Add aggregate DTO**

Include entry order status/fill size, protection list, position status, close status, budget status, recent events, and manual action flag.

- [ ] **Step 2: Add endpoint**

Expose aggregate from `/api/quant/auto-trade/lifecycle`.

- [ ] **Step 3: Update frontend page**

Display aggregate facts rather than only PendingOrder fields.

- [ ] **Step 4: Verify build/tests**

Run backend tests plus frontend build.

### Task 11: Trade Review Records

**Files:**
- Create migration/entity/repository/service.
- Modify close recovery to create review after close confirmation.

- [ ] **Step 1: Add migration/table**

Create `trade_review` fields including `review_reason`, `strategy_tag`, `improvement_hint`, source ids and timestamps.

- [ ] **Step 2: Add review classifier**

Classify normal TP/SL, sideways small profit, max hold exit, early entry, tight stop, protection failed, liquidity/slippage, news shock, trend reversal failure from close record/source/errors and related pending/trade orders.

- [ ] **Step 3: Call after close confirmed**

When `ClosePositionRecoveryService` marks a record `CLOSED`, create review if absent.

- [ ] **Step 4: Add tests**

Run `mvn -Dtest=ClosePositionRecoveryServiceTest test`.

### Task 12: Reversal Short Gate

**Files:**
- Test: `src/test/java/com/example/quant/crypto/ContractSignalAnalyzerScenarioTest.java`
- Modify: `src/main/java/com/example/quant/crypto/ContractSignalAnalyzer.java`

- [ ] **Step 1: Write failing tests**

Add:
- `highPumpSharpDropWithoutStructureBreakOnlyWatches`
- `highPumpSharpDropWithBreakdownAndRiskPassesCanOpenShort`

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -Dtest=ContractSignalAnalyzerScenarioTest test`

- [ ] **Step 3: Implement gate**

For high today change and short-term sharp drop, evaluate `REVERSAL_SHORT` only with volume expansion, lower structure/breakdown, upper wick/rejection, overheated funding, and BTC/ETH not strongly up. Otherwise return `WAIT_OVERHEATED`/`WAIT`.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `mvn -Dtest=ContractSignalAnalyzerScenarioTest test`

## Final Verification

- [ ] Run `mvn test`.
- [ ] Run frontend verification: `npm --prefix frontend run build` or project test command if present.
- [ ] Update `docs/contract-agent-loop-design.md` with Chinese architecture notes and reverse-chronological change log.
- [ ] Run `git status --short` and inspect diff for unrelated edits.

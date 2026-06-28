# Auto Trade Lifecycle Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Continue automatic trading after successful slow scans, close post-entry lifecycles when OKX positions disappear, release their budgets, and clean stale or orphan protection orders.

**Architecture:** Keep scan timing policy in `ContractMarketScanTask`. Extend `OkxCurrentOrderSyncService` to invalidate local orders missing from OKX, and make `ClosePositionRecoveryService` the single owner of terminal lifecycle reconciliation and orphan-protection cancellation. Reuse current tables and retry states; add no migrations or dependencies.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Data JPA, JUnit 5, AssertJ, Mockito, Maven.

---

### Task 1: Continue auto trading after a successful slow scan

**Files:**
- Modify: `src/main/java/com/example/quant/task/ContractMarketScanTask.java:75-99`
- Test: `src/test/java/com/example/quant/task/ContractMarketScanTaskTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void successfulSlowScanStillRunsAutoTrade() {
    AgentProperties properties = new AgentProperties();
    properties.timeout().setScanRoundTimeoutSeconds(-1);
    ContractAnalysisService analysis = mock(ContractAnalysisService.class);
    AutoTradeService autoTrade = mock(AutoTradeService.class);
    when(analysis.refreshCandidates()).thenReturn(List.of());
    when(autoTrade.evaluateAndExecute(List.of())).thenReturn(new AutoTradeResult(
            "SKIPPED", null, null, null, null, null, null, 0, BigDecimal.ZERO,
            BigDecimal.ZERO, "no_candidates", Instant.now()));

    ScanTaskLog result = new ContractMarketScanTask(analysis, autoTrade, properties).runOnce();

    assertThat(result.status()).isEqualTo("SUCCESS");
    assertThat(result.message()).contains("SCAN_SLOW");
    verify(autoTrade).evaluateAndExecute(List.of());
}
```

- [ ] **Step 2: Run RED**

Run: `mvn -Dmaven.repo.local=/home/timaster/View/.m2/repository -Dtest=ContractMarketScanTaskTest#successfulSlowScanStillRunsAutoTrade test`

Expected: FAIL because current code returns `SCAN_TIMEOUT` before invoking auto trade.

- [ ] **Step 3: Implement the minimal behavior**

```java
boolean slowScan = elapsed.compareTo(Duration.ofSeconds(agentProperties.timeout().scanRoundTimeoutSeconds())) > 0;
if (slowScan) {
    log.warn("contract-market-scan slow: scanId={}, elapsedSeconds={}", scanId, elapsed.toSeconds());
}
AutoTradeResult autoTrade = autoTradeService.evaluateAndExecute(candidates);
String timing = slowScan ? ", warning=SCAN_SLOW, elapsedSeconds=" + elapsed.toSeconds() : "";
```

Append `timing` to the successful task message and remove the timeout early return.

- [ ] **Step 4: Run GREEN**

Run: `mvn -Dmaven.repo.local=/home/timaster/View/.m2/repository -Dtest=ContractMarketScanTaskTest test`

Expected: all task tests pass.

### Task 2: Invalidate local active orders missing from OKX

**Files:**
- Modify: `src/main/java/com/example/quant/okxtrade/OkxCurrentOrderSyncService.java:28-118`
- Test: `src/test/java/com/example/quant/okxtrade/OkxCurrentOrderSyncServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void marksMissingProtectionOrderInactive() {
    TradeOrderEntity protection = protection("algo-missing", "algo-cl-missing");
    when(currentOrderService.currentOrders()).thenReturn(List.of());
    when(currentOrderService.currentAlgoOrders()).thenReturn(List.of());
    when(repository.findByStatus("PROTECTION_SUBMITTED")).thenReturn(List.of(protection));

    service.syncOnce();

    assertThat(protection.getStatus()).isEqualTo("INACTIVE");
    assertThat(protection.getOkxState()).isEqualTo("NOT_FOUND_ON_OKX");
    verify(repository).save(protection);
}
```

- [ ] **Step 2: Run RED**

Run: `mvn -Dmaven.repo.local=/home/timaster/View/.m2/repository -Dtest=OkxCurrentOrderSyncServiceTest#marksMissingProtectionOrderInactive test`

Expected: FAIL because current synchronization skips reduce-only records.

- [ ] **Step 3: Generalize reconciliation**

Collect order IDs and client IDs from both normal and algo OKX orders, then reconcile all local active rows:

```java
if (!hasIdentity(order) || stillLive(order, activeOrderIds, activeClientOrderIds)) {
    continue;
}
order.setStatus("INACTIVE");
order.setOkxState("NOT_FOUND_ON_OKX");
order.setErrorMessage("OKX_ACTIVE_ORDER_NOT_FOUND");
order.setUpdatedAt(now);
repository.save(order);
```

Rows without either OKX ID or client ID remain unchanged.

- [ ] **Step 4: Run GREEN**

Run: `mvn -Dmaven.repo.local=/home/timaster/View/.m2/repository -Dtest=OkxCurrentOrderSyncServiceTest test`

Expected: all synchronization tests pass.

### Task 3: Close naturally disappeared positions and release budgets

**Files:**
- Modify: `src/main/java/com/example/quant/account/ClosePositionRecoveryService.java:84-112`
- Test: `src/test/java/com/example/quant/account/ClosePositionRecoveryServiceTest.java`

- [ ] **Step 1: Write failing close and guard tests**

```java
order.markSubmitted(NOW.minusSeconds(60), "entry-1");
order.markProtectionSubmitted("PROTECTION_SUBMITTED");
budgetService.markUsed(order.budgetReservationId());

CloseRecoveryResult result = serviceWithNoPositions().runOnce(NOW);

assertThat(result.closed()).isEqualTo(1);
assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
assertThat(budgetService.reservation(order.budgetReservationId()).orElseThrow().status())
        .isEqualTo(BudgetReservationStatus.RELEASED);
```

Add a second test proving a `SUBMITTED` order with no position stays `SUBMITTED`.

- [ ] **Step 2: Run RED**

Run: `mvn -Dmaven.repo.local=/home/timaster/View/.m2/repository -Dtest=ClosePositionRecoveryServiceTest test`

Expected: natural disappearance test fails because only explicit close records are processed.

- [ ] **Step 3: Implement eligible-state reconciliation**

```java
private static final Set<OrderStatus> POSITION_CONFIRMED_STATUSES = EnumSet.of(
        OrderStatus.ENTRY_FILLED, OrderStatus.EXECUTED, OrderStatus.PROTECTION_SUBMITTED,
        OrderStatus.ACTIVE, OrderStatus.ACTIVE_WITH_TP_WARNING, OrderStatus.SIDEWAYS_TIMEOUT,
        OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED, OrderStatus.MAX_HOLD_TIMEOUT,
        OrderStatus.PROTECTION_FAILED, OrderStatus.EMERGENCY_ATTENTION_REQUIRED);
```

After a successful position query, close eligible orders with no matching `instId + posSide`, create or reuse a `POSITION_GONE` close record, mark the pending order `CLOSED`, and release any `RESERVED` or `USED` budget. Skip all non-eligible states.

- [ ] **Step 4: Run GREEN**

Run: `mvn -Dmaven.repo.local=/home/timaster/View/.m2/repository -Dtest=ClosePositionRecoveryServiceTest test`

Expected: explicit close, natural close, unfilled-order guard, query-failure guard and idempotency tests pass.

### Task 4: Cancel orphan protections and invalidate closed-position orders

**Files:**
- Modify: `src/main/java/com/example/quant/agent/execution/TradeOrderRepository.java`
- Modify: `src/main/java/com/example/quant/account/ClosePositionRecoveryService.java:160-205`
- Test: `src/test/java/com/example/quant/account/ClosePositionRecoveryServiceTest.java`

- [ ] **Step 1: Write failing orphan cleanup tests**

```java
when(tradeOrderRepository.findByInstIdAndReduceOnlyTrueAndStatusIn(
        "BTC-USDT-SWAP", ACTIVE_PROTECTION_STATUSES)).thenReturn(List.of(bound, orphan));

service.runOnce(NOW);

assertThat(gateway.cancelAlgoPayloads).containsExactlyInAnyOrder(
        Map.of("instId", "BTC-USDT-SWAP", "algoId", "algo-bound"),
        Map.of("instId", "BTC-USDT-SWAP", "algoClOrdId", "algo-orphan-cl"));
assertThat(bound.getStatus()).isEqualTo("INVALID");
assertThat(orphan.getStatus()).isEqualTo("INVALID");
```

Add a cancellation failure case expecting `PROTECTION_CANCEL_FAILED`.

- [ ] **Step 2: Run RED**

Run: `mvn -Dmaven.repo.local=/home/timaster/View/.m2/repository -Dtest=ClosePositionRecoveryServiceTest test`

Expected: FAIL because cleanup currently queries only by pending-order ID.

- [ ] **Step 3: Add the focused query and cleanup**

```java
List<TradeOrderEntity> findByInstIdAndReduceOnlyTrueAndStatusIn(
        String instId, Collection<String> statuses);
```

Cancel each returned protection by `algoId`, falling back to `algoClOrdId`. Mark success as `INVALID/CANCELLED`; mark failure as `PROTECTION_CANCEL_FAILED/CANCEL_FAILED` with `cancelRetry=1`. Mark active non-reduce-only rows tied to the closed pending order as `INACTIVE` without another OKX cancellation.

- [ ] **Step 4: Run lifecycle regressions**

Run: `mvn -Dmaven.repo.local=/home/timaster/View/.m2/repository -Dtest=ClosePositionRecoveryServiceTest,AutoTradeLifecycleServiceTest,AutoTradeRecoveryTaskTest,OkxCurrentOrderSyncServiceTest test`

Expected: all selected tests pass.

### Task 5: Full verification

**Files:**
- Verify only; do not modify unrelated files.

- [ ] **Step 1: Run the complete backend suite**

Run: `mvn -Dmaven.repo.local=/home/timaster/View/.m2/repository test`

Expected: 200 or more tests, zero failures and zero errors. Run outside the restricted socket sandbox only if `OkxRestClientTest` reports `SocketException: Operation not permitted`.

- [ ] **Step 2: Check the final diff**

Run: `git diff --check` and `git status --short`.

Expected: no whitespace errors; the user-owned changes in `docs/contract-agent-loop-design.md` and `SystemControlControllerWebTest.java` remain untouched.

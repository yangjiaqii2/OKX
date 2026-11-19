package com.example.quant.agent.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.ClosePositionRecordEntity;
import com.example.quant.account.ClosePositionRecordRepository;
import com.example.quant.account.PositionCloseService;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetAllocationRequest;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.budget.BudgetReservationStatus;
import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.config.AgentProperties;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxPositionMode;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderEntity;
import com.example.quant.order.PendingOrderRepository;
import com.example.quant.order.PendingOrderService;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import com.example.quant.tradeplan.CanonicalPlanCompiler;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import com.example.quant.tradeplan.TradeIntent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class AutoTradeLifecycleServiceTest {

    @Test
    void holdingTimeoutUsesFirstEntryFillInsteadOfSubmitTime() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("filled");
        TradeFillEntity fill = new TradeFillEntity();
        fill.setOrdId("entry-ord-1");
        fill.setFillTime(Instant.parse("2026-06-18T01:00:00Z"));
        TradeFillRepository fills = mock(TradeFillRepository.class);
        when(fills.findByPendingOrderId(order.id().toString())).thenReturn(List.of(fill));
        TradeOrderRepository tradeOrders = mock(TradeOrderRepository.class);
        TradeOrderEntity entryOrder = new TradeOrderEntity();
        entryOrder.setOkxOrdId("entry-ord-1");
        entryOrder.setReduceOnly(false);
        when(tradeOrders.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc(
                "local-admin", order.id().toString())).thenReturn(List.of(entryOrder));
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "1", "100", "20", "100"))),
                OkxInstrumentRules::defaultFor, null, tradeOrders, null, null, fills);

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(
                Instant.parse("2026-06-18T03:10:00Z"));

        assertThat(result.maxHoldCloseSubmitted()).isZero();
        assertThat(gateway.closePayload).isNull();
    }

    @Test
    void closeFillCannotResetHoldingAgeWhenEntryFillIsTemporarilyMissing() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("filled");
        TradeFillEntity closeFill = new TradeFillEntity();
        closeFill.setOrdId("close-ord-1");
        closeFill.setFillTime(Instant.parse("2026-06-18T01:00:00Z"));
        TradeFillRepository fills = mock(TradeFillRepository.class);
        when(fills.findByPendingOrderId(order.id().toString())).thenReturn(List.of(closeFill));
        TradeOrderRepository tradeOrders = mock(TradeOrderRepository.class);
        TradeOrderEntity entryOrder = new TradeOrderEntity();
        entryOrder.setOkxOrdId("entry-ord-1");
        entryOrder.setReduceOnly(false);
        TradeOrderEntity closeOrder = new TradeOrderEntity();
        closeOrder.setOkxOrdId("close-ord-1");
        closeOrder.setReduceOnly(true);
        when(tradeOrders.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc(
                "local-admin", order.id().toString())).thenReturn(List.of(entryOrder, closeOrder));
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "1", "100", "20", "100"))),
                OkxInstrumentRules::defaultFor, null, tradeOrders, null, null, fills);

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(
                Instant.parse("2026-06-18T03:10:00Z"));

        assertThat(result.maxHoldCloseSubmitted()).isEqualTo(1);
        assertThat(gateway.closePayload).isNotNull();
    }

    @Test
    void cancelsUnfilledEntryAfterConfiguredTimeoutAndReleasesBudget() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "live";
        gateway.accFillSz = BigDecimal.ZERO;
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:11:00Z"));

        assertThat(result.entryTimeoutCancelled()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.ENTRY_TIMEOUT_CANCELLED);
        assertThat(gateway.cancelPayload).containsEntry("ordId", "entry-ord-1");
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RELEASED);
    }

    @Test
    void ambiguousAlreadyClosedCancelDoesNotReleaseBudgetWithoutTerminalReconciliation() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new AlreadyClosedCancelGateway();
        gateway.orderState = "live";
        gateway.accFillSz = BigDecimal.ZERO;
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:11:00Z"));

        assertThat(result.entryTimeoutCancelled()).isZero();
        assertThat(order.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(gateway.cancelPayload).containsEntry("ordId", "entry-ord-1");
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RESERVED);
    }

    @Test
    void rejectedCancelAcknowledgementDoesNotReleaseBudget() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new RejectedCancelGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of()));

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(
                Instant.parse("2026-06-18T00:11:00Z"));

        assertThat(result.entryTimeoutCancelled()).isZero();
        assertThat(order.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get().extracting(BudgetReservation::status).isEqualTo(BudgetReservationStatus.RESERVED);
    }

    @Test
    void partialFillCancelsRemainderAndSubmitsProtectionForActualFill() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "partially_filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:11:00Z"));

        assertThat(result.partialFillProtected()).isEqualTo(1);
        assertThat(gateway.cancelPayload).containsEntry("ordId", "entry-ord-1");
        assertThat(gateway.algoPayloads).hasSize(4);
        assertThat(gateway.algoPayloads.get(0)).containsEntry("reduceOnly", "true");
        assertThat(gateway.algoPayloads.get(0)).containsEntry("sz", "10");
        assertThat(gateway.algoPayloads.get(0)).containsEntry("slTriggerPx", "98");
        assertThat(gateway.algoPayloads.get(1)).containsEntry("sz", "3").containsEntry("tpTriggerPx", "102");
        assertThat(gateway.algoPayloads.get(2)).containsEntry("sz", "4").containsEntry("tpTriggerPx", "104");
        assertThat(gateway.algoPayloads.get(3)).containsEntry("sz", "3").containsEntry("tpTriggerPx", "106");
        assertThat(gateway.algoPayloads)
                .extracting(payload -> payload.get("algoClOrdId"))
                .doesNotHaveDuplicates()
                .allSatisfy(clOrdId -> assertThat(clOrdId).hasSizeLessThanOrEqualTo(32));
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
    }

    @Test
    void onePointEightRSurvivesPersistenceRestoreLifecycleAndGateway() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService originalService = new PendingOrderService(120);
        PendingOrder order = autoOrder(originalService, budgetService, planWithTarget("BTC-USDT-SWAP", "118"));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-1");

        PendingOrderEntity persisted = PendingOrderEntity.from(order);
        PendingOrderRepository repository = mock(PendingOrderRepository.class);
        when(repository.findAll()).thenReturn(List.of(persisted));
        when(repository.saveAndFlush(any(PendingOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PendingOrderService restoredService = new PendingOrderService(120, repository);
        PendingOrder restored = restoredService.get(order.id());
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");

        AutoTradeLifecycleService lifecycle = new AutoTradeLifecycleService(
                restoredService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "0", "1000"))));
        lifecycle.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(restored.canonicalExecutablePlan().takeProfitLegs())
                .extracting(CanonicalExecutablePlan.ExitLeg::executableTargetPrice)
                .containsExactly(new BigDecimal("110"), new BigDecimal("118"));
        assertThat(gateway.algoPayloads.stream().filter(payload -> payload.containsKey("tpTriggerPx")).toList())
                .extracting(payload -> payload.get("tpTriggerPx"))
                .containsExactly("110", "118");
        assertThat(gateway.algoPayloads).noneMatch(payload -> "130".equals(payload.get("tpTriggerPx")));
    }

    @Test
    void sidewaysReplacementReusesOnePersistedIntentInsteadOfDuplicateClOrdRows() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        List<TradeOrderEntity> rows = new ArrayList<>();
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findByPendingOrderIdOrderByCreatedAtAsc(order.id().toString()))
                .thenAnswer(invocation -> List.copyOf(rows));
        when(repository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> {
            TradeOrderEntity row = invocation.getArgument(0);
            if (!rows.contains(row)) {
                rows.add(row);
            }
            return row;
        });
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "0", "1000"))),
                null, repository);

        service.runOnce(Instant.parse("2026-06-18T02:59:00Z"));

        assertThat(rows).filteredOn(row -> "SIDEWAYS_TP".equals(row.getOrderRole())).hasSize(1)
                .allSatisfy(row -> assertThat(row.getClOrdId()).endsWith("SWT"));
    }

    @Test
    void unexpressiblePostEntryStopRequestsTerminalSafetyReduction() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        CanonicalExecutablePlan plan = order.canonicalExecutablePlan();
        order.setCanonicalPlanSnapshot(new CanonicalExecutablePlan(
                plan.planId(), plan.instId(), plan.action(), plan.strategyVersion(), plan.compilerVersion(),
                plan.entry(), plan.stopLoss(), plan.takeProfitLegs(),
                new CanonicalExecutablePlan.Risk(new BigDecimal("0.01"), plan.risk().executableStopLossAmount()),
                plan.instrument(), plan.status(), plan.decisions()));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionRulesStale("PROTECTION_RULES_STALE");
        assertThat(new CanonicalPlanCompiler().matchesSnapshot(order.canonicalExecutablePlan(),
                OkxInstrumentRules.defaultFor("BTC-USDT-SWAP"))).isTrue();
        assertThat(order.canonicalExecutablePlan().risk().riskBudget()).isEqualByComparingTo("0.01");
        LifecycleGateway gateway = new LifecycleGateway();
        PositionCloseService safetyClose = mock(PositionCloseService.class);
        when(safetyClose.closePositionForSafety(any(), any(), any()))
                .thenReturn(OrderExecutionResult.submitted("safety-close-1", "submitted"));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "0", "1000"))));
        service.setPositionCloseService(safetyClose);

        service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        verify(safetyClose).closePositionForSafety(any(), any(),
                org.mockito.ArgumentMatchers.eq("CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY"));
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
    }

    @Test
    void activePositionWithoutCanonicalPlanUsesExistingSlOrSafetyCloses() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pending = new PendingOrderService(120);
        PendingOrder order = autoOrder(pending, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        try {
            var field = PendingOrder.class.getDeclaredField("canonicalExecutablePlan");
            field.setAccessible(true);
            field.set(order, null);
            field = PendingOrder.class.getDeclaredField("canonicalPlanJson");
            field.setAccessible(true);
            field.set(order, null);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        LifecycleGateway gateway = new LifecycleGateway();
        PositionCloseService close = mock(PositionCloseService.class);
        when(close.closePositionForSafety(any(), any(), any()))
                .thenReturn(OrderExecutionResult.submitted("safe-1", "submitted"));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pending, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "0", "1000"))));
        service.setPositionCloseService(close);

        service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        verify(close).closePositionForSafety(any(), any(),
                org.mockito.ArgumentMatchers.eq("CANONICAL_PLAN_UNAVAILABLE_AFTER_ENTRY"));
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
    }

    @Test
    void activePositionWithoutCanonicalPlanKeepsValidExchangeStopAndBlocksNormalLifecycle() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pending = new PendingOrderService(120);
        PendingOrder order = autoOrder(pending, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        try {
            var field = PendingOrder.class.getDeclaredField("canonicalExecutablePlan");
            field.setAccessible(true);
            field.set(order, null);
            field = PendingOrder.class.getDeclaredField("canonicalPlanJson");
            field.setAccessible(true);
            field.set(order, null);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.attachedStopLossClientId = OkxTradeAdapter.attachedStopLossClientOrderId(order);
        PositionCloseService close = mock(PositionCloseService.class);
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pending, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "0", "1000"))));
        service.setPositionCloseService(close);

        service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(order.status()).isEqualTo(OrderStatus.EMERGENCY_ATTENTION_REQUIRED);
        org.mockito.Mockito.verifyNoInteractions(close);
        assertThat(gateway.algoPayloads).isEmpty();
    }

    @Test
    void postEntryRuleChangeThatStillExpressesStopReconcilesWithoutClosingPosition() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        budgetService.markUsed(order.budgetReservationId());
        LifecycleGateway gateway = new LifecycleGateway();
        OkxInstrumentRules changed = new OkxInstrumentRules("BTC-USDT-SWAP", BigDecimal.ONE,
                "BTC", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("1"));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "0", "1000"))),
                ignored -> changed, null, null);

        service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(order.status()).isNotEqualTo(OrderStatus.CLOSE_SUBMITTED);
        assertThat(gateway.closePayload).isNull();
        assertThat(gateway.algoPayloads).isNotEmpty();
        assertThat(gateway.algoPayloads).anyMatch(payload -> payload.containsKey("slTriggerPx"));
    }

    @Test
    void fullFillWithChangedRulesUsesCurrentExpressibilityWithoutStaleLoop() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        OkxInstrumentRules changed = new OkxInstrumentRules("BTC-USDT-SWAP", BigDecimal.ONE,
                "BTC", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("1"));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "0", "1000"))),
                ignored -> changed, null, null);

        service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
        assertThat(gateway.algoPayloads.stream().filter(payload -> payload.containsKey("tpTriggerPx")).toList())
                .extracting(payload -> payload.get("tpTriggerPx"))
                .containsExactly("102", "104", "106");
    }

    @Test
    void fullFillWithChangedMinimumSizeTriggersTerminalSafetyReduction() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        PositionCloseService safetyClose = mock(PositionCloseService.class);
        when(safetyClose.closePositionForSafety(any(), any(), any()))
                .thenReturn(OrderExecutionResult.submitted("safety-close-1", "submitted"));
        OkxInstrumentRules changed = new OkxInstrumentRules("BTC-USDT-SWAP", BigDecimal.ONE,
                "BTC", BigDecimal.ONE, new BigDecimal("20"), BigDecimal.ONE);
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "0", "1000"))),
                ignored -> changed, null, null);
        service.setPositionCloseService(safetyClose);

        service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        verify(safetyClose).closePositionForSafety(any(), any(), org.mockito.ArgumentMatchers.eq(
                "CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY"));
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
    }

    @Test
    void sidewaysPlacementFailureDoesNotCancelExistingProfitProtectionFirst() {
        AgentProperties properties = new AgentProperties();
        properties.lifecycle().setSidewaysPositionHours(1);
        properties.lifecycle().setMaxHoldMinutes(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-09-15T00:00:00Z"), "entry-ord-1");
        SidewaysFailingGateway gateway = new SidewaysFailingGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        MutablePositionSnapshotService positions = new MutablePositionSnapshotService(
                position("BTC-USDT-SWAP", "long", "10", new BigDecimal("100")));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway, positions);

        service.runOnce(Instant.parse("2026-09-15T00:01:00Z"));
        gateway.cancelAlgoPayloads.clear();

        service.runOnce(Instant.parse("2026-09-15T01:02:00Z"));

        assertThat(gateway.cancelAlgoPayloads).hasSize(1);
        assertThat(order.status()).isNotEqualTo(OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED);
    }

    @Test
    void laterEntryFillReconcilesProtectionToCurrentPositionWithoutChangingTargets() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        MutablePositionSnapshotService positions = new MutablePositionSnapshotService(
                position("BTC-USDT-SWAP", "long", "4", new BigDecimal("100")));
        LifecycleGateway gateway = new LifecycleGateway() {
            @Override
            public JsonNode cancelOrder(Map<String, String> payload) {
                accFillSz = new BigDecimal("7");
                positions.replace(position("BTC-USDT-SWAP", "long", "7", new BigDecimal("100")));
                return super.cancelOrder(payload);
            }
        };
        gateway.orderState = "partially_filled";
        gateway.accFillSz = new BigDecimal("4");
        gateway.avgPx = new BigDecimal("100");
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway, positions);

        service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));
        assertThat(gateway.algoPayloads).extracting(payload -> payload.get("sz"))
                .containsExactly("4", "1", "1", "2");

        service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway, positions);

        service.runOnce(Instant.parse("2026-06-18T00:11:00Z"));
        assertThat(gateway.cancelPayload).containsEntry("clOrdId", order.clientOrderId());

        assertThat(gateway.algoPayloads).extracting(payload -> payload.get("sz"))
                .containsExactly("4", "1", "1", "2", "7", "1", "1", "1");
        assertThat(gateway.algoPayloads.stream().filter(payload -> payload.containsKey("tpTriggerPx"))
                .map(payload -> new BigDecimal(payload.get("sz")))
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("7");
        assertThat(gateway.algoPayloads).extracting(payload -> payload.get("tpTriggerPx"))
                .filteredOn(java.util.Objects::nonNull)
                .containsExactly("102", "104", "106", "102", "104", "106");
    }

    @Test
    void statefulSidewaysReplacementConvergesWithoutProfitOverAllocation() {
        AgentProperties properties = new AgentProperties();
        properties.lifecycle().setSidewaysPositionHours(1);
        properties.lifecycle().setMaxHoldMinutes(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-09-15T00:00:00Z"), "entry-ord-1");
        StatefulProtectionGateway gateway = new StatefulProtectionGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        MutablePositionSnapshotService positions = new MutablePositionSnapshotService(
                position("BTC-USDT-SWAP", "long", "10", new BigDecimal("100")));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway, positions);

        service.runOnce(Instant.parse("2026-09-15T00:01:00Z"));
        for (int minute = 62; minute <= 66; minute++) {
            service.runOnce(Instant.parse("2026-09-15T01:00:00Z").plusSeconds((minute - 60L) * 60L));
        }

        BigDecimal profitSize = gateway.live.values().stream()
                .filter(item -> item.containsKey("tpTriggerPx"))
                .map(item -> new BigDecimal(item.get("sz")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(profitSize).isEqualByComparingTo("10");
        assertThat(gateway.live.values()).noneMatch(item ->
                "TP1".equals(item.get("orderRole")) || "TP2".equals(item.get("orderRole"))
                        || "TP3".equals(item.get("orderRole")));
        assertThat(gateway.live.values()).anyMatch(item -> item.getOrDefault("algoClOrdId", "").endsWith("SWT"));
        assertThat(gateway.live.values()).anyMatch(item -> item.containsKey("slTriggerPx")
                && "10".equals(item.get("sz")));
    }

    @Test
    void sidewaysReplacementUsesRemainingPositionAfterTpAlreadyFilled() {
        AgentProperties properties = new AgentProperties();
        properties.lifecycle().setSidewaysPositionHours(1);
        properties.lifecycle().setMaxHoldMinutes(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-09-15T00:00:00Z"), "entry-ord-1");
        StatefulProtectionGateway gateway = new StatefulProtectionGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        MutablePositionSnapshotService positions = new MutablePositionSnapshotService(
                position("BTC-USDT-SWAP", "long", "10", new BigDecimal("100")));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway, positions);

        service.runOnce(Instant.parse("2026-09-15T00:01:00Z"));
        gateway.live.keySet().stream().filter(id -> id.endsWith("TP1")).findFirst()
                .ifPresent(gateway.live::remove);
        positions.replace(position("BTC-USDT-SWAP", "long", "7", new BigDecimal("100")));
        for (int minute = 62; minute <= 70; minute++) {
            service.runOnce(Instant.parse("2026-09-15T01:00:00Z").plusSeconds((minute - 60L) * 60L));
        }

        BigDecimal profitSize = gateway.live.values().stream()
                .filter(item -> item.containsKey("tpTriggerPx")
                        && item.getOrDefault("algoClOrdId", "").endsWith("SWT"))
                .map(item -> new BigDecimal(item.get("sz")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(profitSize).isEqualByComparingTo("7");
        assertThat(gateway.live.values()).noneMatch(item -> {
            String id = item.getOrDefault("algoClOrdId", "");
            return id.endsWith("TP1") || id.endsWith("TP2") || id.endsWith("TP3");
        });
        assertThat(gateway.live.values()).anyMatch(item -> item.containsKey("slTriggerPx")
                && "7".equals(item.get("sz")));
    }

    @Test
    void missingAttachedStopLossFallsBackToCanonicalStandaloneStopBeforeTp() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of()));

        service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(gateway.algoPayloads).hasSize(4);
        assertThat(gateway.algoPayloads.get(0)).containsEntry("sz", "10")
                .containsEntry("slTriggerPx", "98");
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
    }

    @Test
    void lifecycleSubmitsProtectionAfterFullEntryFill() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        gateway.attachedStopLossClientId = OkxTradeAdapter.attachedStopLossClientOrderId(order);
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(result.entryFilledProtected()).isEqualTo(1);
        assertThat(gateway.cancelPayload).isNull();
        assertThat(gateway.algoPayloads).hasSize(3);
        assertThat(gateway.algoPayloads).noneMatch(payload -> payload.containsKey("slTriggerPx"));
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
    }

    @Test
    void fullFillRecoversMissingAttachedStopLossBeforeSubmittingTakeProfits() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(
                Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(result.entryFilledProtected()).isEqualTo(1);
        assertThat(gateway.algoPayloads).hasSize(4);
        assertThat(gateway.algoPayloads.get(0)).containsEntry("slTriggerPx", "98")
                .containsEntry("sz", "10");
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
    }

    @Test
    void missingInstrumentRulesNeverSubmitsProtectionWithInventedPrecision() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of()),
                instId -> { throw new IllegalStateException("instrument metadata unavailable"); },
                null,
                null
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(
                Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(result.entryFilledProtected()).isZero();
        assertThat(gateway.algoPayloads).isEmpty();
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_FAILED);
        assertThat(order.rejectReason()).contains("instrument metadata unavailable");
    }

    @Test
    void lifecycleRecordsSubmittedProtectionOrdersWithPendingOrderBinding() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        gateway.attachedStopLossClientId = OkxTradeAdapter.attachedStopLossClientOrderId(order);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        when(tradeOrderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of()),
                OkxInstrumentRules::defaultFor,
                null,
                tradeOrderRepository
        );

        service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        ArgumentCaptor<TradeOrderEntity> captor = ArgumentCaptor.forClass(TradeOrderEntity.class);
        verify(tradeOrderRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TradeOrderEntity::getPendingOrderId)
                .containsOnly(order.id().toString());
        assertThat(captor.getAllValues())
                .extracting(TradeOrderEntity::getOrderRole)
                .containsExactly("TP1", "TP2", "TP3");
        assertThat(captor.getAllValues())
                .extracting(TradeOrderEntity::isReduceOnly)
                .containsOnly(true);
    }

    @Test
    void marksBudgetUsedWhenEntryFilledEvenIfProtectionSubmitFails() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new FailingProtectionGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        gateway.attachedStopLossClientId = OkxTradeAdapter.attachedStopLossClientOrderId(order);
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of())
        );

        service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_FAILED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
    }

    @Test
    void lifecycleQueriesEntryFillAndSubmitsProtectionWhenPositionAlreadyExists() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        gateway.attachedStopLossClientId = OkxTradeAdapter.attachedStopLossClientOrderId(order);
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "5", "1000")))
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(result.entryFilledProtected()).isEqualTo(1);
        assertThat(gateway.queryOrderPayload).containsEntry("ordId", "entry-ord-1");
        assertThat(gateway.algoPayloads).hasSize(3);
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
    }

    @Test
    void lifecycleSubmitsProtectionFromPositionWhenEntryQueryIsNotFilled() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "live";
        gateway.accFillSz = BigDecimal.ZERO;
        gateway.avgPx = BigDecimal.ZERO;
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "5", "1000")))
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(result.entryFilledProtected()).isEqualTo(1);
        assertThat(gateway.cancelPayload).isNull();
        assertThat(gateway.algoPayloads).hasSize(4);
        assertThat(gateway.algoPayloads.get(0)).containsEntry("sz", "10").containsEntry("slTriggerPx", "98");
        assertThat(gateway.algoPayloads.get(1)).containsEntry("tpTriggerPx", "102");
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
    }

    @Test
    void recoversMissingProtectionForTimedOutEntryWithOpenPosition() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markEntryTimeoutCancelled("ENTRY_TIMEOUT_CANCELLED");
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        when(tradeOrderRepository.findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(
                order.id().toString(),
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of());
        when(tradeOrderRepository.findByInstIdAndReduceOnlyTrueAndStatusIn(
                "BTC-USDT-SWAP",
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of());
        when(tradeOrderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "5", "1000"))),
                null,
                tradeOrderRepository
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:12:00Z"));

        assertThat(result.entryFilledProtected()).isEqualTo(1);
        assertThat(gateway.algoPayloads).hasSize(4);
        assertThat(gateway.algoPayloads.get(0)).containsEntry("sz", "10").containsEntry("slTriggerPx", "98");
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
    }

    @Test
    void retriesMissingProtectionAfterProtectionFailureWithOpenPosition() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionFailed("PROTECTION_FAILED_AFTER_FULL_FILL");
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        when(tradeOrderRepository.findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(
                order.id().toString(),
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of());
        when(tradeOrderRepository.findByInstIdAndReduceOnlyTrueAndStatusIn(
                "BTC-USDT-SWAP",
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of());
        when(tradeOrderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "5", "1000"))),
                null,
                tradeOrderRepository
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:12:00Z"));

        assertThat(result.entryFilledProtected()).isEqualTo(1);
        assertThat(gateway.algoPayloads).hasSize(4);
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
    }

    @Test
    void doesNotTakeOwnershipOfExternalPositionWithoutLocalPendingOrder() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        when(tradeOrderRepository.findByInstIdAndReduceOnlyTrueAndStatusIn(
                "BTC-USDT-SWAP",
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of());
        when(tradeOrderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "10", "100", "5", "1000"))),
                null,
                tradeOrderRepository
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:12:00Z"));

        assertThat(result.orphanPositionProtected()).isZero();
        assertThat(gateway.algoPayloads).isEmpty();
    }

    @Test
    void positionSnapshotFailureDoesNotCancelReleaseOrClose() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FailingPositionSnapshotService()
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T08:01:00Z"));

        assertThat(result.positionSyncUnavailable()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(gateway.cancelPayload).isNull();
        assertThat(gateway.closePayload).isNull();
        assertThat(gateway.algoPayloads).isEmpty();
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RESERVED);
        assertThat(service.snapshots().get(0).lifecycleStatus()).isEqualTo("POSITION_SYNC_UNAVAILABLE");
        assertThat(service.snapshots().get(0).positionLifecycle().positionSyncAvailable()).isFalse();
    }

    @Test
    void partialFillProtectionUsesInstrumentLotRulesForActualFilledSize() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        OkxInstrumentRules lotRules = new OkxInstrumentRules("BTC-USDT-SWAP", BigDecimal.ONE, "BTC",
                new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.5"));
        order.setCanonicalExecutablePlan(new CanonicalPlanCompiler().compile(
                TradeIntent.fromPendingOrder(order, new BigDecimal("0.30"), new BigDecimal("0.40"),
                        new BigDecimal("0.30"), "trade-plan-v1"), lotRules));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "partially_filled";
        gateway.accFillSz = new BigDecimal("2.7");
        gateway.avgPx = new BigDecimal("100");
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of()),
                instId -> lotRules,
                null,
                null
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:11:00Z"));

        assertThat(result.partialFillProtected()).isEqualTo(1);
        assertThat(gateway.algoPayloads.get(0)).containsEntry("sz", "2.5");
        assertThat(gateway.algoPayloads.get(1)).containsEntry("sz", "0.5");
        assertThat(gateway.algoPayloads.get(2)).containsEntry("sz", "1");
        assertThat(gateway.algoPayloads.get(3)).containsEntry("sz", "1");
    }

    @Test
    void netModeProtectionPayloadOmitsPosSide() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        gateway.attachedStopLossClientId = OkxTradeAdapter.attachedStopLossClientOrderId(order);
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of()),
                OkxInstrumentRules::defaultFor,
                null,
                null,
                () -> OkxPositionMode.NET
        );

        service.runOnce(Instant.parse("2026-06-18T00:01:00Z"));

        assertThat(gateway.algoPayloads).isNotEmpty();
        assertThat(gateway.algoPayloads).allSatisfy(payload -> assertThat(payload).doesNotContainKey("posSide"));
    }

    @Test
    void profitablePositionBeforeThreeHoursDoesNotSubmitMaxHoldClose() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(
                        position("BTC-USDT-SWAP", "long", "2", "100", "20", "1000")))
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(
                Instant.parse("2026-06-18T02:59:00Z"));

        assertThat(result.maxHoldCloseSubmitted()).isZero();
        assertThat(order.status()).isNotEqualTo(OrderStatus.CLOSE_SUBMITTED);
        assertThat(gateway.closePayload).isNull();
    }

    @Test
    void maxHoldTimeoutSubmitsClosePositionAfterThreeHoursAndKeepsBudgetUntilClosedSync() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        budgetService.markUsed(order.budgetReservationId());
        LifecycleGateway gateway = new LifecycleGateway();
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        when(closeRepository.saveAndFlush(any(ClosePositionRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(closeRepository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "2", "100", "20", "1000"))),
                closeRepository
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T03:00:00Z"));

        assertThat(result.maxHoldCloseSubmitted()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
        assertThat(gateway.closePayload).containsEntry("instId", "BTC-USDT-SWAP");
        ArgumentCaptor<ClosePositionRecordEntity> closeRecord = ArgumentCaptor.forClass(ClosePositionRecordEntity.class);
        verify(closeRepository).saveAndFlush(closeRecord.capture());
        verify(closeRepository).save(closeRecord.capture());
        ClosePositionRecordEntity latest = closeRecord.getAllValues().get(1);
        assertThat(latest.getStatus()).isEqualTo("CLOSE_SUBMITTED");
        assertThat(latest.getSource()).isEqualTo("MAX_HOLD_TIMEOUT");
        assertThat(latest.getPendingOrderId()).isEqualTo(order.id().toString());
        assertThat(latest.getCloseOrderId()).isEqualTo("close-1");
        assertThat(latest.getSize()).isEqualByComparingTo("2");
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void netLongAndShortMaxHoldCloseUseNetPayloadAndRetainBudget(boolean shortSide) {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budget = new AutoTradeBudgetService(properties);
        PendingOrderService orders = new PendingOrderService(120);
        PendingOrder order = autoOrder(orders, budget, samplePlan("BTC-USDT-SWAP",
                shortSide ? TradePlanType.OPEN_SHORT : TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        budget.markUsed(order.budgetReservationId());
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(orders, budget, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "net",
                        shortSide ? "-2" : "2", "100", "20", "1000"))));

        assertThat(service.runOnce(Instant.parse("2026-06-18T03:00:00Z")).maxHoldCloseSubmitted()).isEqualTo(1);
        assertThat(gateway.closePayload).containsEntry("instId", "BTC-USDT-SWAP").doesNotContainKey("posSide");
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
        assertThat(budget.reservation(order.budgetReservationId()).orElseThrow().status()).isEqualTo(BudgetReservationStatus.USED);
    }

    @Test
    void sidewaysAdjustedPositionStillSubmitsMaxHoldClose() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        order.markSidewaysTimeoutTpAdjusted("SIDEWAYS_TIMEOUT_TP_ADJUSTED");
        LifecycleGateway gateway = new FailingProtectionGateway();
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        when(closeRepository.saveAndFlush(any(ClosePositionRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(closeRepository.save(any(ClosePositionRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "2", "100", "20", "1000"))),
                closeRepository
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(
                Instant.parse("2026-06-18T03:01:00Z"));

        assertThat(result.maxHoldCloseSubmitted()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
        assertThat(gateway.closePayload).containsEntry("instId", "BTC-USDT-SWAP");
    }

    @Test
    void unknownMaxHoldCloseIsOwnedByRecoveryAndSchedulerDoesNotSubmitAgain() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        UnknownCloseGateway gateway = new UnknownCloseGateway();
        ClosePositionRecordRepository closes = mock(ClosePositionRecordRepository.class);
        List<ClosePositionRecordEntity> stored = new ArrayList<>();
        when(closes.saveAndFlush(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> {
            ClosePositionRecordEntity value = invocation.getArgument(0);
            if (!stored.contains(value)) stored.add(value);
            return value;
        });
        when(closes.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(closes.findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
                "local-admin", order.id().toString())).thenAnswer(ignored -> List.copyOf(stored));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "1", "100", "20", "100"))),
                closes);

        service.runOnce(Instant.parse("2026-06-18T03:01:00Z"));
        service.runOnce(Instant.parse("2026-06-18T03:02:00Z"));

        assertThat(gateway.closeCalls).isEqualTo(1);
        assertThat(stored).hasSize(1);
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
    }

    @Test
    void concurrentMaxHoldIntentUniqueCollisionIsRecoveredWithoutSecondCloseSubmit() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        LifecycleGateway gateway = new LifecycleGateway();
        ClosePositionRecordRepository closes = mock(ClosePositionRecordRepository.class);
        ClosePositionRecordEntity winner = new ClosePositionRecordEntity();
        winner.setCloseClOrdId("MAXC-existing");
        winner.setStatus("CLOSE_SUBMITTING");
        when(closes.saveAndFlush(any(ClosePositionRecordEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate close_cl_ord_id"));
        when(closes.findByUserNameAndCloseClOrdId(any(), any()))
                .thenReturn(java.util.Optional.empty());
        when(closes.findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
                "local-admin", order.id().toString())).thenReturn(List.of(winner));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "1", "100", "20", "100"))),
                closes);

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(
                Instant.parse("2026-06-18T03:01:00Z"));

        assertThat(result.maxHoldCloseSubmitted()).isZero();
        assertThat(gateway.closePayload).isNull();
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
    }

    @Test
    void failedBeforeSendCloseIntentCanBeAtomicallyReclaimedAndRetried() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        LifecycleGateway gateway = new LifecycleGateway();
        ClosePositionRecordRepository closes = mock(ClosePositionRecordRepository.class);
        ClosePositionRecordEntity failed = new ClosePositionRecordEntity();
        failed.setStatus("CLOSE_FAILED_BEFORE_SEND");
        when(closes.saveAndFlush(any(ClosePositionRecordEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate pending_order_id"));
        when(closes.findByUserNameAndCloseClOrdId(any(), any()))
                .thenReturn(java.util.Optional.empty());
        when(closes.findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
                "local-admin", order.id().toString())).thenReturn(List.of(failed));
        when(closes.claimRetry(any(), any())).thenReturn(1);
        when(closes.save(any(ClosePositionRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService, budgetService, properties, gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "1", "100", "20", "100"))),
                closes);

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(
                Instant.parse("2026-06-18T03:01:00Z"));

        assertThat(result.maxHoldCloseSubmitted()).isEqualTo(1);
        assertThat(gateway.closePayload.get("clOrdId")).startsWith("MAXC").hasSize(28);
        assertThat(failed.getStatus()).isEqualTo("CLOSE_SUBMITTED");
    }

    @Test
    void losingPositionBeforeSixtyMinutesDoesNotSubmitClosePosition() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService,
                samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(
                        position("BTC-USDT-SWAP", "long", "2", "100", "-1", "1000")))
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(
                Instant.parse("2026-06-18T00:59:00Z"));

        assertThat(result.maxHoldCloseSubmitted()).isZero();
        assertThat(order.status()).isNotEqualTo(OrderStatus.CLOSE_SUBMITTED);
        assertThat(gateway.closePayload).isNull();
    }

    @Test
    void losingPositionAfterSixtyMinutesSubmitsClosePosition() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        budgetService.markUsed(order.budgetReservationId());
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "2", "100", "-1", "1000")))
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T01:00:00Z"));

        assertThat(result.maxHoldCloseSubmitted()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
        assertThat(gateway.closePayload).containsEntry("instId", "BTC-USDT-SWAP");
    }

    private static PendingOrder autoOrder(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                          TradePlan plan) {
        BudgetAllocation allocation = budgetService.allocate(new BudgetAllocationRequest(
                new BigDecimal("50"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1,
                new BigDecimal("92"),
                new BigDecimal("30"),
                new BigDecimal("2"),
                "LOW",
                "BULLISH",
                BigDecimal.ZERO,
                new BigDecimal("2.2")
        ));
        UUID pendingOrderId = UUID.randomUUID();
        BudgetReservation reservation = budgetService.reserveBudget(
                plan.id(),
                pendingOrderId,
                plan.instId(),
                allocation.finalOrderMarginUsdt(),
                new BigDecimal("50")
        );
        PendingOrder order = pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                plan,
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                reservation.reservationId(),
                allocation,
                "AUTO" + pendingOrderId.toString().replace("-", "").substring(0, 12)
        );
        order.applyMarginAmount(new BigDecimal("500"));
        order.setExecutableContractQuantity(new BigDecimal("10"));
        CanonicalExecutablePlan canonicalPlan = new CanonicalPlanCompiler().compile(
                TradeIntent.fromPendingOrder(order, new BigDecimal("0.30"), new BigDecimal("0.40"),
                        new BigDecimal("0.30"), "trade-plan-v1"),
                OkxInstrumentRules.defaultFor(plan.instId()));
        assertThat(canonicalPlan.accepted()).as(canonicalPlan.decisions().toString()).isTrue();
        order.setCanonicalExecutablePlan(canonicalPlan);
        return order;
    }

    private static PositionSummary position(String instId, String posSide, String size, String avgPx,
                                            String upl, String notionalUsd) {
        return new PositionSummary(
                instId,
                posSide,
                size,
                new BigDecimal(avgPx),
                new BigDecimal(upl),
                BigDecimal.TEN,
                BigDecimal.TEN,
                "cross",
                BigDecimal.valueOf(2),
                BigDecimal.ZERO,
                new BigDecimal(notionalUsd),
                BigDecimal.ZERO,
                "OKX_REAL"
        );
    }

    private static TradePlan samplePlan(String instId, TradePlanType action) {
        boolean shortSide = action == TradePlanType.OPEN_SHORT;
        return new TradePlan(
                UUID.randomUUID(),
                instId,
                action,
                "LIMIT",
                shortSide ? DirectionBias.BEARISH : DirectionBias.BULLISH,
                new BigDecimal("0.9"),
                new BigDecimal("100"),
                shortSide ? new BigDecimal("102") : new BigDecimal("98"),
                shortSide ? new BigDecimal("94") : new BigDecimal("106"),
                2,
                BigDecimal.ONE,
                new BigDecimal("22.5"),
                new BigDecimal("100"),
                new BigDecimal("2"),
                90,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                new BigDecimal("100000000"),
                "cross",
                List.of("test"),
                List.of(),
                "",
                true,
                Instant.now().plusSeconds(120)
        );
    }

    private static TradePlan planWithTarget(String instId, String target) {
        return new TradePlan(
                UUID.randomUUID(), instId, TradePlanType.OPEN_LONG, "LIMIT", DirectionBias.BULLISH,
                new BigDecimal("0.9"), new BigDecimal("100"), new BigDecimal("90"), new BigDecimal(target),
                2, new BigDecimal("10"), new BigDecimal("22.5"), new BigDecimal("100"), new BigDecimal("2"),
                90, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("100000000"), "cross",
                List.of("test"), List.of(), "", true, Instant.now().plusSeconds(120));
    }

    private static TradeOrderEntity protection(PendingOrder order, String role, String clOrdId) {
        TradeOrderEntity entity = new TradeOrderEntity();
        entity.setPendingOrderId(order.id().toString());
        entity.setOrderRole(role);
        entity.setInstId(order.instId());
        entity.setSide("sell");
        entity.setPosSide(order.posSide());
        entity.setOrdType("conditional");
        entity.setTdMode(order.tdMode());
        entity.setSize(BigDecimal.ONE);
        entity.setReduceOnly(true);
        entity.setClOrdId(clOrdId);
        entity.setOkxState("live");
        entity.setStatus("PROTECTION_SUBMITTED");
        entity.setCreatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        return entity;
    }

    private static class FixedPositionSnapshotService extends PositionSnapshotService {
        private final List<PositionSummary> positions;

        FixedPositionSnapshotService(List<PositionSummary> positions) {
            super(null);
            this.positions = positions;
        }

        @Override
        public List<PositionSummary> positions() {
            return positions;
        }
    }

    private static class MutablePositionSnapshotService extends FixedPositionSnapshotService {
        private List<PositionSummary> current;

        MutablePositionSnapshotService(PositionSummary position) {
            super(List.of(position));
            this.current = List.of(position);
        }

        void replace(PositionSummary position) {
            this.current = List.of(position);
        }

        @Override
        public List<PositionSummary> positions() {
            return current;
        }
    }

    private static PositionSummary position(String instId, String posSide, String size, BigDecimal avgPx) {
        return new PositionSummary(instId, posSide, size, avgPx, BigDecimal.ZERO, "LIVE");
    }

    private static class FailingPositionSnapshotService extends PositionSnapshotService {
        FailingPositionSnapshotService() {
            super(null);
        }

        @Override
        public List<PositionSummary> positions() {
            throw new IllegalStateException("OKX positions unavailable");
        }
    }

    private static class LifecycleGateway implements OkxOrderGateway {
        protected String orderState = "live";
        protected BigDecimal accFillSz = BigDecimal.ZERO;
        protected BigDecimal avgPx = BigDecimal.ZERO;
        private Map<String, String> queryOrderPayload;
        protected Map<String, String> cancelPayload;
        private Map<String, String> closePayload;
        protected String attachedStopLossClientId;
        protected final List<Map<String, String>> algoPayloads = new ArrayList<>();
        protected final List<Map<String, String>> cancelAlgoPayloads = new ArrayList<>();

        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            this.queryOrderPayload = payload;
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("ordId", payload.getOrDefault("ordId", "entry-ord-1"));
            item.put("state", orderState);
            item.put("accFillSz", accFillSz.toPlainString());
            item.put("avgPx", avgPx.toPlainString());
            return root;
        }

        @Override
        public JsonNode cancelOrder(Map<String, String> payload) {
            this.cancelPayload = payload;
            this.orderState = "canceled";
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.put("code", "0");
            root.putArray("data").addObject().put("sCode", "0").put("ordId", payload.get("ordId"));
            return root;
        }

        @Override
        public JsonNode placeAlgoOrder(Map<String, String> payload) {
            this.algoPayloads.add(payload);
            return new ObjectMapper().createObjectNode().putArray("data").addObject().put("algoId", "algo-1");
        }

        @Override
        public JsonNode currentAlgoOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            var data = root.putArray("data");
            if (attachedStopLossClientId != null) {
                data.addObject()
                        .put("algoClOrdId", attachedStopLossClientId)
                        .put("instId", "BTC-USDT-SWAP").put("side", "sell").put("posSide", "long")
                        .put("state", "live")
                        .put("slTriggerPx", "98")
                        .put("sz", "10");
            }
            for (Map<String, String> existing : algoPayloads) {
                ObjectNode item = data.addObject()
                        .put("algoClOrdId", existing.getOrDefault("algoClOrdId", ""))
                        .put("instId", existing.getOrDefault("instId", "BTC-USDT-SWAP"))
                        .put("side", existing.getOrDefault("side", "sell"))
                        .put("posSide", existing.getOrDefault("posSide", "net"))
                        .put("state", "live")
                        .put("sz", existing.getOrDefault("sz", "0"));
                if (existing.containsKey("slTriggerPx")) {
                    item.put("slTriggerPx", existing.get("slTriggerPx"));
                }
                if (existing.containsKey("tpTriggerPx")) {
                    item.put("tpTriggerPx", existing.get("tpTriggerPx"));
                }
            }
            return root;
        }

        @Override
        public JsonNode cancelAlgoOrder(Map<String, String> payload) {
            this.cancelAlgoPayloads.add(Map.copyOf(payload));
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.put("code", "0");
            root.putArray("data").addObject().put("sCode", "0");
            return root;
        }

        @Override
        public JsonNode closePosition(Map<String, String> payload) {
            this.closePayload = payload;
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject().put("ordId", "close-1");
            return root;
        }
    }

    private static class FailingProtectionGateway extends LifecycleGateway {
        @Override
        public JsonNode placeAlgoOrder(Map<String, String> payload) {
            throw new IllegalStateException("protection rejected");
        }
    }

    private static class SidewaysFailingGateway extends LifecycleGateway {
        @Override
        public JsonNode placeAlgoOrder(Map<String, String> payload) {
            if (payload.getOrDefault("algoClOrdId", "").endsWith("SWT")) {
                throw new IllegalStateException("sideways protection rejected");
            }
            return super.placeAlgoOrder(payload);
        }
    }

    private static class StatefulProtectionGateway extends LifecycleGateway {
        private final Map<String, Map<String, String>> live = new java.util.LinkedHashMap<>();

        @Override
        public JsonNode placeAlgoOrder(Map<String, String> payload) {
            live.put(payload.get("algoClOrdId"), Map.copyOf(payload));
            return new ObjectMapper().createObjectNode().putArray("data").addObject()
                    .put("algoId", "algo-" + payload.get("algoClOrdId"));
        }

        @Override
        public JsonNode currentAlgoOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            var data = root.putArray("data");
            for (Map<String, String> item : live.values()) {
                ObjectNode node = data.addObject()
                        .put("algoClOrdId", item.getOrDefault("algoClOrdId", ""))
                        .put("instId", item.getOrDefault("instId", "BTC-USDT-SWAP"))
                        .put("side", item.getOrDefault("side", "sell"))
                        .put("posSide", item.getOrDefault("posSide", "net"))
                        .put("state", "live")
                        .put("sz", item.getOrDefault("sz", "0"));
                if (item.containsKey("slTriggerPx")) node.put("slTriggerPx", item.get("slTriggerPx"));
                if (item.containsKey("tpTriggerPx")) node.put("tpTriggerPx", item.get("tpTriggerPx"));
            }
            return root;
        }

        @Override
        public JsonNode cancelAlgoOrder(Map<String, String> payload) {
            live.remove(payload.get("algoClOrdId"));
            ObjectNode root = new ObjectMapper().createObjectNode().put("code", "0");
            root.putArray("data").addObject().put("sCode", "0");
            return root;
        }
    }

    private static class UnknownCloseGateway extends LifecycleGateway {
        private int closeCalls;

        @Override
        public JsonNode closePosition(Map<String, String> payload) {
            closeCalls++;
            throw new IllegalStateException("connection reset after send");
        }
    }

    private static class AlreadyClosedCancelGateway extends LifecycleGateway {
        @Override
        public JsonNode cancelOrder(Map<String, String> payload) {
            super.cancelOrder(payload);
            this.orderState = "live";
            throw new IllegalStateException("OKX code=1, msg=All operations failed, details=[sCode=51400, "
                    + "sMsg=Order cancellation failed as the order has been filled, canceled or does not exist.]");
        }
    }

    private static class RejectedCancelGateway extends LifecycleGateway {
        @Override
        public JsonNode cancelOrder(Map<String, String> payload) {
            this.cancelPayload = payload;
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.put("code", "0");
            root.putArray("data").addObject().put("sCode", "51000").put("sMsg", "invalid request");
            return root;
        }
    }
}

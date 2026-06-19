package com.example.quant.agent.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.ClosePositionRecordEntity;
import com.example.quant.account.ClosePositionRecordRepository;
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
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxPositionMode;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
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

class AutoTradeLifecycleServiceTest {

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
        assertThat(gateway.algoPayloads).hasSize(4);
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
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
        assertThat(gateway.algoPayloads).hasSize(4);
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
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
                instId -> new OkxInstrumentRules(instId, BigDecimal.ONE, "BTC",
                        new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.5")),
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
    void sidewaysLongPositionTightensTakeProfitToSmallProfitExit() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "2", "100", "5", "1000")))
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T03:01:00Z"));

        assertThat(result.sidewaysTpAdjusted()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED);
        assertThat(gateway.algoPayloads.get(0)).containsEntry("tpTriggerPx", "100.3");
    }

    @Test
    void sidewaysTimeoutReplacesExistingTakeProfitsAndRecordsSidewaysProtection() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        TradeOrderEntity tp1 = protection(order, "TP1", "tp1-cl");
        TradeOrderEntity tp2 = protection(order, "TP2", "tp2-cl");
        TradeOrderEntity tp3 = protection(order, "TP3", "tp3-cl");
        TradeOrderEntity stopLoss = protection(order, "STOP_LOSS", "sl-cl");
        when(tradeOrderRepository.findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(
                order.id().toString(),
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of(tp1, tp2, tp3, stopLoss));
        when(tradeOrderRepository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "2", "100", "5", "1000"))),
                null,
                tradeOrderRepository
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T03:01:00Z"));

        assertThat(result.sidewaysTpAdjusted()).isEqualTo(1);
        assertThat(gateway.cancelAlgoPayloads)
                .containsExactly(
                        Map.of("instId", "BTC-USDT-SWAP", "algoClOrdId", "tp1-cl"),
                        Map.of("instId", "BTC-USDT-SWAP", "algoClOrdId", "tp2-cl"),
                        Map.of("instId", "BTC-USDT-SWAP", "algoClOrdId", "tp3-cl")
                );
        assertThat(tp1.getStatus()).isEqualTo("INVALID");
        assertThat(tp2.getStatus()).isEqualTo("INVALID");
        assertThat(tp3.getStatus()).isEqualTo("INVALID");
        assertThat(stopLoss.getStatus()).isEqualTo("PROTECTION_SUBMITTED");
        ArgumentCaptor<TradeOrderEntity> sidewaysRecord = ArgumentCaptor.forClass(TradeOrderEntity.class);
        verify(tradeOrderRepository).save(sidewaysRecord.capture());
        assertThat(sidewaysRecord.getValue().getOrderRole()).isEqualTo("SIDEWAYS_TP");
        assertThat(sidewaysRecord.getValue().getPendingOrderId()).isEqualTo(order.id().toString());
        assertThat(sidewaysRecord.getValue().getStatus()).isEqualTo("PROTECTION_SUBMITTED");
        assertThat(sidewaysRecord.getValue().isReduceOnly()).isTrue();
        assertThat(sidewaysRecord.getValue().getPrice()).isEqualByComparingTo("100.3");
    }

    @Test
    void sidewaysShortPositionTightensTakeProfitBelowEntryPrice() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("ETH-USDT-SWAP", TradePlanType.OPEN_SHORT));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-2");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("ETH-USDT-SWAP", "short", "2", "100", "5", "1000")))
        );

        service.runOnce(Instant.parse("2026-06-18T03:01:00Z"));

        assertThat(gateway.algoPayloads.get(0)).containsEntry("tpTriggerPx", "99.7");
    }

    @Test
    void maxHoldTimeoutSubmitsClosePositionAndKeepsBudgetUntilClosedSync() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        budgetService.markUsed(order.budgetReservationId());
        LifecycleGateway gateway = new LifecycleGateway();
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        when(closeRepository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "2", "100", "20", "1000"))),
                closeRepository
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T08:01:00Z"));

        assertThat(result.maxHoldCloseSubmitted()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
        assertThat(gateway.closePayload).containsEntry("instId", "BTC-USDT-SWAP");
        ArgumentCaptor<ClosePositionRecordEntity> closeRecord = ArgumentCaptor.forClass(ClosePositionRecordEntity.class);
        verify(closeRepository).save(closeRecord.capture());
        assertThat(closeRecord.getValue().getStatus()).isEqualTo("CLOSE_SUBMITTED");
        assertThat(closeRecord.getValue().getSource()).isEqualTo("MAX_HOLD_TIMEOUT");
        assertThat(closeRecord.getValue().getPendingOrderId()).isEqualTo(order.id().toString());
        assertThat(closeRecord.getValue().getCloseOrderId()).isEqualTo("close-1");
        assertThat(closeRecord.getValue().getSize()).isEqualByComparingTo("2");
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
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
        return pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                plan,
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                reservation.reservationId(),
                allocation,
                "AUTO" + pendingOrderId.toString().replace("-", "").substring(0, 12)
        );
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
        private String orderState = "live";
        private BigDecimal accFillSz = BigDecimal.ZERO;
        private BigDecimal avgPx = BigDecimal.ZERO;
        private Map<String, String> queryOrderPayload;
        private Map<String, String> cancelPayload;
        private Map<String, String> closePayload;
        private final List<Map<String, String>> algoPayloads = new ArrayList<>();
        private final List<Map<String, String>> cancelAlgoPayloads = new ArrayList<>();

        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
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
            return new ObjectMapper().createObjectNode().putArray("data").addObject().put("ordId", payload.get("ordId"));
        }

        @Override
        public JsonNode placeAlgoOrder(Map<String, String> payload) {
            this.algoPayloads.add(payload);
            return new ObjectMapper().createObjectNode().putArray("data").addObject().put("algoId", "algo-1");
        }

        @Override
        public JsonNode cancelAlgoOrder(Map<String, String> payload) {
            this.cancelAlgoPayloads.add(Map.copyOf(payload));
            ObjectNode root = new ObjectMapper().createObjectNode();
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
}

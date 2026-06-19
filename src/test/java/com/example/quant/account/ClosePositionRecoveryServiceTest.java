package com.example.quant.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetAllocationRequest;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.budget.BudgetReservationStatus;
import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClosePositionRecoveryServiceTest {

    @Test
    void marksClosedReleasesBudgetAndInvalidatesProtectionOrdersWhenPositionIsGone() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markCloseSubmitted("close-1", "MAX_HOLD_TIMEOUT_CLOSE_SUBMITTED");
        ClosePositionRecordEntity record = closeRecord(order);
        TradeOrderEntity stopLoss = protection(order.id(), "STOP_LOSS");
        TradeOrderEntity tp1 = protection(order.id(), "TP1");
        stopLoss.setOkxOrdId("algo-sl");
        tp1.setClOrdId("algo-tp1-cl");
        CapturingOkxGateway gateway = new CapturingOkxGateway();
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of(record));
        when(closeRepository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(
                order.id().toString(),
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of(stopLoss, tp1));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                pendingOrderService,
                budgetService,
                new FixedPositionSnapshotService(List.of()),
                gateway
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(Instant.parse("2026-06-18T00:10:00Z"));

        assertThat(result.closed()).isEqualTo(1);
        assertThat(record.getStatus()).isEqualTo("CLOSED");
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RELEASED);
        assertThat(stopLoss.getStatus()).isEqualTo("INVALID");
        assertThat(tp1.getStatus()).isEqualTo("INVALID");
        assertThat(gateway.cancelAlgoPayloads)
                .contains(
                        Map.of("instId", "BTC-USDT-SWAP", "algoId", "algo-sl"),
                        Map.of("instId", "BTC-USDT-SWAP", "algoClOrdId", "algo-tp1-cl")
                );
        verify(closeRepository).save(record);
        verify(tradeOrderRepository).saveAll(List.of(stopLoss, tp1));
    }

    @Test
    void positionsQueryFailureDoesNotCloseRecordReleaseBudgetOrInvalidateProtectionOrders() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markCloseSubmitted("close-1", "MAX_HOLD_TIMEOUT_CLOSE_SUBMITTED");
        ClosePositionRecordEntity record = closeRecord(order);
        TradeOrderEntity stopLoss = protection(order.id(), "STOP_LOSS");
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of(record));
        when(closeRepository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(
                order.id().toString(),
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of(stopLoss));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                pendingOrderService,
                budgetService,
                new FailingPositionSnapshotService()
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(Instant.parse("2026-06-18T00:10:00Z"));

        assertThat(result.closed()).isZero();
        assertThat(record.getStatus()).isEqualTo("CLOSE_SUBMITTED");
        assertThat(record.getErrorMessage()).contains("POSITION_QUERY_FAILED");
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
        assertThat(stopLoss.getStatus()).isEqualTo("PROTECTION_SUBMITTED");
        verify(closeRepository).save(record);
    }

    @Test
    void recoversOnlyCurrentOwnersCloseSubmittedRecords() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        ClosePositionRecordEntity userARecord = closeRecord(autoOrder(pendingOrderService, budgetService));
        userARecord.setUserName("userA");
        ClosePositionRecordEntity userBRecord = closeRecord(autoOrder(pendingOrderService, budgetService));
        userBRecord.setUserName("userB");
        when(closeRepository.findByUserNameAndStatus("userA", "CLOSE_SUBMITTED")).thenReturn(List.of(userARecord));
        when(closeRepository.findByUserNameAndStatus("userB", "CLOSE_SUBMITTED")).thenReturn(List.of(userBRecord));
        when(closeRepository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                pendingOrderService,
                budgetService,
                new FixedPositionSnapshotService(List.of())
        );

        ClosePositionRecoveryService.CloseRecoveryResult result =
                AuthUserContext.callAs("userA", () -> service.runOnce(Instant.parse("2026-06-18T00:10:00Z")));

        assertThat(result.closed()).isEqualTo(1);
        assertThat(userARecord.getStatus()).isEqualTo("CLOSED");
        assertThat(userBRecord.getStatus()).isEqualTo("CLOSE_SUBMITTED");
        verify(closeRepository).findByUserNameAndStatus("userA", "CLOSE_SUBMITTED");
    }

    @Test
    void protectionCancelFailureIsPersistedForManualAttention() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markCloseSubmitted("close-1", "MANUAL_CLOSE_SUBMITTED");
        ClosePositionRecordEntity record = closeRecord(order);
        TradeOrderEntity tp1 = protection(order.id(), "TP1");
        tp1.setClOrdId("algo-tp1-cl");
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of(record));
        when(closeRepository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(
                order.id().toString(),
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of(tp1));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                pendingOrderService,
                budgetService,
                new FixedPositionSnapshotService(List.of()),
                new ThrowingCancelGateway()
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(Instant.parse("2026-06-18T00:10:00Z"));

        assertThat(result.closed()).isEqualTo(1);
        assertThat(tp1.getStatus()).isEqualTo("PROTECTION_CANCEL_FAILED");
        assertThat(tp1.getOkxState()).isEqualTo("CANCEL_FAILED");
        assertThat(tp1.getErrorMessage()).contains("PROTECTION_CANCEL_FAILED").contains("OKX cancel rejected");
        verify(tradeOrderRepository).saveAll(List.of(tp1));
    }

    @Test
    void retriesFailedProtectionCancelAndInvalidatesWhenOkxAccepts() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        TradeOrderEntity tp1 = protection(UUID.randomUUID(), "TP1");
        tp1.setClOrdId("algo-tp1-cl");
        tp1.setStatus("PROTECTION_CANCEL_FAILED");
        tp1.setErrorMessage("PROTECTION_CANCEL_FAILED: cancelRetry=1");
        CapturingOkxGateway gateway = new CapturingOkxGateway();
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of());
        when(tradeOrderRepository.findByStatus("PROTECTION_CANCEL_FAILED")).thenReturn(List.of(tp1));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                new PendingOrderService(120),
                new AutoTradeBudgetService(new AgentProperties()),
                new FixedPositionSnapshotService(List.of()),
                gateway
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(Instant.parse("2026-06-18T00:20:00Z"));

        assertThat(result.protectionCancelRetried()).isEqualTo(1);
        assertThat(tp1.getStatus()).isEqualTo("INVALID");
        assertThat(tp1.getOkxState()).isEqualTo("CANCELLED");
        assertThat(tp1.getErrorMessage()).contains("PROTECTION_CANCEL_RETRY_SUCCEEDED");
        assertThat(gateway.cancelAlgoPayloads).contains(Map.of("instId", "BTC-USDT-SWAP", "algoClOrdId", "algo-tp1-cl"));
        verify(tradeOrderRepository).saveAll(List.of(tp1));
    }

    @Test
    void escalatesProtectionCancelFailureAfterRetryLimit() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        TradeOrderEntity tp1 = protection(UUID.randomUUID(), "TP1");
        tp1.setClOrdId("algo-tp1-cl");
        tp1.setStatus("PROTECTION_CANCEL_FAILED");
        tp1.setErrorMessage("PROTECTION_CANCEL_FAILED: cancelRetry=2");
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of());
        when(tradeOrderRepository.findByStatus("PROTECTION_CANCEL_FAILED")).thenReturn(List.of(tp1));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                new PendingOrderService(120),
                new AutoTradeBudgetService(new AgentProperties()),
                new FixedPositionSnapshotService(List.of()),
                new ThrowingCancelGateway()
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(Instant.parse("2026-06-18T00:20:00Z"));

        assertThat(result.protectionCancelAttentionRequired()).isEqualTo(1);
        assertThat(tp1.getStatus()).isEqualTo("EMERGENCY_ATTENTION_REQUIRED");
        assertThat(tp1.getOkxState()).isEqualTo("CANCEL_FAILED");
        assertThat(tp1.getErrorMessage()).contains("PROTECTION_CANCEL_RETRY_LIMIT").contains("cancelRetry=3");
        verify(tradeOrderRepository).saveAll(List.of(tp1));
    }


    private static PendingOrder autoOrder(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService) {
        TradePlan plan = samplePlan();
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
        budgetService.markUsed(order.budgetReservationId());
        return order;
    }

    private static ClosePositionRecordEntity closeRecord(PendingOrder order) {
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName("local-admin");
        record.setInstId(order.instId());
        record.setPosSide(order.posSide());
        record.setMarginMode(order.tdMode());
        record.setCloseOrderId("close-1");
        record.setCloseClOrdId("CLOSE123");
        record.setPendingOrderId(order.id().toString());
        record.setSource("AUTO");
        record.setStatus("CLOSE_SUBMITTED");
        record.setCreatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        return record;
    }

    private static TradeOrderEntity protection(UUID pendingOrderId, String role) {
        TradeOrderEntity entity = new TradeOrderEntity();
        entity.setPendingOrderId(pendingOrderId.toString());
        entity.setOrderRole(role);
        entity.setInstId("BTC-USDT-SWAP");
        entity.setSide("sell");
        entity.setPosSide("long");
        entity.setOrdType("conditional");
        entity.setTdMode("cross");
        entity.setReduceOnly(true);
        entity.setStatus("PROTECTION_SUBMITTED");
        entity.setOkxState("live");
        entity.setCreatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        return entity;
    }

    private static class CapturingOkxGateway implements OkxOrderGateway {
        private final List<Map<String, String>> cancelAlgoPayloads = new ArrayList<>();

        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode cancelAlgoOrder(Map<String, String> payload) {
            cancelAlgoPayloads.add(Map.copyOf(payload));
            return new ObjectMapper().createObjectNode().putArray("data").addObject().put("sCode", "0");
        }
    }

    private static class ThrowingCancelGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode cancelAlgoOrder(Map<String, String> payload) {
            throw new IllegalStateException("OKX cancel rejected");
        }
    }

    private static TradePlan samplePlan() {
        return new TradePlan(
                UUID.randomUUID(),
                "BTC-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "LIMIT",
                DirectionBias.BULLISH,
                BigDecimal.ONE,
                new BigDecimal("100"),
                new BigDecimal("98"),
                new BigDecimal("106"),
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

    private static class FixedPositionSnapshotService extends PositionSnapshotService {
        private final List<PositionSummary> positions;

        FixedPositionSnapshotService(List<PositionSummary> positions) {
            super(null);
            this.positions = new ArrayList<>(positions);
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
}

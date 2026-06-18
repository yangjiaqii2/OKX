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
import com.example.quant.config.AgentProperties;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        when(closeRepository.findByStatus("CLOSE_SUBMITTED")).thenReturn(List.of(record));
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
                new FixedPositionSnapshotService(List.of())
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
        verify(closeRepository).save(record);
        verify(tradeOrderRepository).saveAll(List.of(stopLoss, tp1));
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
        record.setUserName("alice");
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
}

package com.example.quant.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetAllocationRequest;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.budget.BudgetReservationStatus;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutoTradeRecoveryTaskTest {

    @Test
    void releasesReservedBudgetForFailedPendingOrder() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        BudgetAllocation allocation = budgetService.allocate(request());
        UUID pendingOrderId = UUID.randomUUID();
        BudgetReservation reservation = budgetService.reserveBudget(
                samplePlan().id(),
                pendingOrderId,
                "BTC-USDT-SWAP",
                allocation.finalOrderMarginUsdt(),
                new BigDecimal("50")
        );
        PendingOrder order = pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                samplePlan(),
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                reservation.reservationId(),
                allocation,
                "AUTO_plan_pending_test"
        );
        order.markRejected("OKX拒绝");

        AutoTradeRecoveryTask task = new AutoTradeRecoveryTask(pendingOrderService, budgetService, properties);
        AutoTradeRecoveryTask.RecoveryResult result = task.runOnce();

        assertThat(result.releasedReservations()).isEqualTo(1);
        assertThat(budgetService.reservation(reservation.reservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RELEASED);
    }

    @Test
    void marksExpiredReservedPendingOrderAndReleasesBudget() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(0);
        BudgetAllocation allocation = budgetService.allocate(request());
        UUID pendingOrderId = UUID.randomUUID();
        BudgetReservation reservation = budgetService.reserveBudget(
                samplePlan().id(),
                pendingOrderId,
                "ETH-USDT-SWAP",
                allocation.finalOrderMarginUsdt(),
                new BigDecimal("50")
        );
        PendingOrder order = pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                samplePlan(),
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                reservation.reservationId(),
                allocation,
                "AUTO_plan_pending_expired"
        );

        AutoTradeRecoveryTask task = new AutoTradeRecoveryTask(pendingOrderService, budgetService, properties);
        AutoTradeRecoveryTask.RecoveryResult result = task.runOnce();

        assertThat(result.expiredOrders()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(budgetService.reservation(reservation.reservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RELEASED);
    }

    private static BudgetAllocationRequest request() {
        return new BudgetAllocationRequest(
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
        );
    }

    private static TradePlan samplePlan() {
        return new TradePlan(
                UUID.randomUUID(),
                "BTC-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "MARKET",
                DirectionBias.BULLISH,
                new BigDecimal("0.9"),
                new BigDecimal("100"),
                new BigDecimal("98"),
                new BigDecimal("104"),
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
}

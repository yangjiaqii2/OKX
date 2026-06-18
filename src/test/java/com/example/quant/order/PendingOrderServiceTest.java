package com.example.quant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.budget.BudgetReservationStatus;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingOrderServiceTest {

    @Test
    void rejectsPendingOrderForAShareMarket() {
        PendingOrderService service = new PendingOrderService(120);

        assertThatThrownBy(() -> service.createPendingOrder(MarketType.A_SHARE, samplePlan()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A股");
    }

    @Test
    void createsPendingOrderForOkxContractOnly() {
        PendingOrderService service = new PendingOrderService(120);

        PendingOrder order = service.createPendingOrder(MarketType.OKX_SWAP, samplePlan());

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRM);
        assertThat(order.userConfirmed()).isFalse();
        assertThat(order.leverage()).isEqualTo(2);
        assertThat(order.maxLossAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    void createsAutoPendingOrderWithReservedBudgetAllocation() {
        PendingOrderService service = new PendingOrderService(120);
        BudgetReservation reservation = new BudgetReservation(
                UUID.randomUUID(),
                samplePlan().id(),
                UUID.randomUUID(),
                "BTC-USDT-SWAP",
                new BigDecimal("22.5"),
                BudgetReservationStatus.RESERVED,
                Instant.now(),
                Instant.now(),
                "RESERVED"
        );

        PendingOrder order = service.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                samplePlan(),
                new BigDecimal("22.5"),
                reservation.reservationId(),
                allocation("22.5"),
                "AUTO_plan_pending_abc"
        );

        assertThat(order.status()).isEqualTo(OrderStatus.BUDGET_RESERVED);
        assertThat(order.marginAmount()).isEqualByComparingTo("22.5000");
        assertThat(order.budgetReservationId()).isEqualTo(reservation.reservationId());
        assertThat(order.clientOrderId()).isEqualTo("AUTO_plan_pending_abc");
        assertThat(order.budgetAllocationJson()).contains("\"finalOrderMarginUsdt\":22.5");
        assertThat(order.size()).isEqualByComparingTo("0.45000000");
    }

    @Test
    void compareAndSetAllowsOnlyOneConfirmingTransition() {
        PendingOrderService service = new PendingOrderService(120);
        PendingOrder order = service.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                samplePlan(),
                new BigDecimal("20"),
                UUID.randomUUID(),
                allocation("20"),
                "AUTO_plan_pending_abc"
        );

        assertThat(order.transition(OrderStatus.BUDGET_RESERVED, OrderStatus.CONFIRMING)).isTrue();
        assertThat(order.transition(OrderStatus.BUDGET_RESERVED, OrderStatus.CONFIRMING)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMING);
    }

    @Test
    void restoresUnfinishedPendingOrdersAndPersistsStatusTransitions() {
        PendingOrderRepository repository = mock(PendingOrderRepository.class);
        PendingOrderEntity saved = PendingOrderEntity.from(autoReservedOrder("AUTO_restore_abc"));
        saved.setStatus(OrderStatus.SUBMITTED.name());
        saved.setSubmittedAt(Instant.parse("2026-06-18T00:00:00Z"));
        saved.setExternalOrderId("okx-ord-1");
        when(repository.findAll()).thenReturn(List.of(saved));
        when(repository.save(any(PendingOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PendingOrderService service = new PendingOrderService(120, repository);

        PendingOrder restored = service.get(UUID.fromString(saved.getId()));
        assertThat(restored.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(restored.clientOrderId()).isEqualTo("AUTO_restore_abc");
        assertThat(restored.budgetReservationId()).isEqualTo(UUID.fromString(saved.getBudgetReservationId()));
        assertThat(restored.externalOrderId()).isEqualTo("okx-ord-1");

        restored.markEntryTimeoutCancelled("ENTRY_TIMEOUT_CANCELLED");

        verify(repository, atLeast(1)).save(any(PendingOrderEntity.class));
    }

    static TradePlan samplePlan() {
        return new TradePlan(
                UUID.randomUUID(),
                "BTC-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "LIMIT",
                DirectionBias.BULLISH,
                BigDecimal.valueOf(0.78),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(104),
                2,
                BigDecimal.valueOf(5),
                BigDecimal.valueOf(250),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(2),
                76,
                BigDecimal.valueOf(0.0004),
                BigDecimal.valueOf(0.025),
                BigDecimal.valueOf(25000000),
                "cross",
                List.of("test plan"),
                List.of("test risk"),
                "test invalid condition",
                true,
                Instant.now().plusSeconds(120)
        );
    }

    private static BudgetAllocation allocation(String finalMargin) {
        return new BudgetAllocation(
                new BigDecimal("50"),
                new BigDecimal("45"),
                new BigDecimal("40"),
                new BigDecimal("50"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("50"),
                1,
                new BigDecimal("0.45"),
                new BigDecimal("22.5"),
                BigDecimal.ONE,
                new BigDecimal("22.5"),
                new BigDecimal("30"),
                new BigDecimal("25"),
                BigDecimal.ZERO,
                new BigDecimal(finalMargin),
                new BigDecimal("45"),
                "TARGET_UTILIZATION",
                "OK",
                List.of(),
                "test budget allocation"
        );
    }

    private static PendingOrder autoReservedOrder(String clientOrderId) {
        PendingOrder order = new PendingOrderService(120).createAutoPendingOrder(
                MarketType.OKX_SWAP,
                samplePlan(),
                new BigDecimal("22.5"),
                UUID.randomUUID(),
                allocation("22.5"),
                clientOrderId
        );
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "okx-ord-1");
        return order;
    }
}

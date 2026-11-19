package com.example.quant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.auth.AuthUserContext;
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
        assertThat(order.clientOrderId()).startsWith("qa").hasSize(32);
    }

    @Test
    void rejectsWatchPlanBeforeItCanEnterOrderLifecycle() {
        PendingOrderService service = new PendingOrderService(120);
        TradePlan executable = samplePlan();
        TradePlan watch = new TradePlan(
                executable.id(), executable.instId(), TradePlanType.WATCH, "NO_ENTRY", DirectionBias.NEUTRAL,
                BigDecimal.ZERO, executable.entryPrice(), executable.stopLossPrice(), executable.takeProfitPrice(),
                0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                executable.signalScore(), executable.fundingRate(), executable.volatility(), executable.volume24h(),
                executable.tdMode(), executable.reasonList(), executable.riskList(), executable.invalidCondition(),
                false, executable.expireTime());

        assertThatThrownBy(() -> service.createPendingOrder(MarketType.OKX_SWAP, watch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("观察计划");
    }

    @Test
    void pendingOrdersAreOnlyVisibleAndMutableByTheirOwner() {
        PendingOrderService service = new PendingOrderService(120);
        PendingOrder aliceOrder = AuthUserContext.callAs("alice", () ->
                service.createPendingOrder(MarketType.OKX_SWAP, samplePlan()));

        AuthUserContext.runAs("bob", () -> {
            assertThat(service.pendingOrders()).isEmpty();
            assertThatThrownBy(() -> service.get(aliceOrder.id()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("订单不存在");
        });
        AuthUserContext.runAs("alice", () ->
                assertThat(service.get(aliceOrder.id())).isSameAs(aliceOrder));
    }

    @Test
    void createsAutoPendingOrderWithReservedBudgetAllocation() {
        PendingOrderService service = new PendingOrderService(120);
        BudgetReservation reservation = new BudgetReservation(
                UUID.randomUUID(),
                samplePlan().id(),
                UUID.randomUUID(),
                "local-admin",
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
    void autoOrderMarginNeverRoundsAboveReservedRiskCap() {
        PendingOrderService service = new PendingOrderService(120);

        PendingOrder order = service.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                samplePlan(),
                new BigDecimal("9.99237297"),
                UUID.randomUUID(),
                allocation("9.99237297"),
                "AUTO_cap_rounding_abc"
        );

        assertThat(order.marginAmount()).isEqualByComparingTo("9.9923");
        assertThat(order.marginAmount()).isLessThanOrEqualTo(new BigDecimal("9.99237297"));
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
        when(repository.saveAndFlush(any(PendingOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PendingOrderService service = new PendingOrderService(120, repository);

        PendingOrder restored = service.get(UUID.fromString(saved.getId()));
        assertThat(restored.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(restored.clientOrderId()).isEqualTo("AUTO_restore_abc");
        assertThat(restored.budgetReservationId()).isEqualTo(UUID.fromString(saved.getBudgetReservationId()));
        assertThat(restored.externalOrderId()).isEqualTo("okx-ord-1");

        restored.markEntryTimeoutCancelled("ENTRY_TIMEOUT_CANCELLED");

        verify(repository, atLeast(1)).saveAndFlush(any(PendingOrderEntity.class));
    }

    @Test
    void restoresTimedOutSubmittedEntryForProtectionRecovery() {
        PendingOrderRepository repository = mock(PendingOrderRepository.class);
        PendingOrderEntity saved = PendingOrderEntity.from(autoReservedOrder("AUTO_timeout_restore"));
        saved.setStatus(OrderStatus.ENTRY_TIMEOUT_CANCELLED.name());
        saved.setSubmittedAt(Instant.parse("2026-06-18T00:00:00Z"));
        saved.setExternalOrderId("okx-ord-timeout");
        when(repository.findAll()).thenReturn(List.of(saved));
        when(repository.saveAndFlush(any(PendingOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PendingOrderService service = new PendingOrderService(120, repository);

        PendingOrder restored = service.get(UUID.fromString(saved.getId()));
        assertThat(restored.status()).isEqualTo(OrderStatus.ENTRY_TIMEOUT_CANCELLED);
        assertThat(restored.submittedAt()).isEqualTo(Instant.parse("2026-06-18T00:00:00Z"));
        assertThat(restored.externalOrderId()).isEqualTo("okx-ord-timeout");
    }

    @Test
    void restoresExecutableRiskFactsWithoutRecalculation() {
        PendingOrder original = autoReservedOrder("AUTO_executable_restore");
        original.replaceExecutableSize(new BigDecimal("0.12345678"));
        original.setExecutableContractQuantity(new BigDecimal("123.4"));
        PendingOrderEntity saved = PendingOrderEntity.from(original);
        PendingOrderRepository repository = mock(PendingOrderRepository.class);
        when(repository.findAll()).thenReturn(List.of(saved));
        when(repository.saveAndFlush(any(PendingOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PendingOrder restored = new PendingOrderService(120, repository).get(original.id());

        assertThat(restored.executableContractQuantity()).isEqualByComparingTo("123.4");
        assertThat(restored.executableStopLossAmount()).isEqualByComparingTo("0.2469");
    }

    @Test
    void closedHistoricalOrderWithUnknownCanonicalSchemaRemainsReadable() {
        PendingOrder order = autoReservedOrder("AUTO_unknown_schema");
        String unknown = "{\"schemaVersion\":\"canonical-plan-schema-v99\",\"plan\":{}}";
        order.restoreCanonicalPlanJson(unknown);
        order.markClosed("CLOSED");

        assertThat(order.canonicalExecutablePlan()).isNull();
    }

    @Test
    void activeOrderWithUnknownCanonicalSchemaFailsClosed() {
        PendingOrder order = autoReservedOrder("AUTO_unknown_schema_active");
        order.restoreCanonicalPlanJson("{\"schemaVersion\":\"canonical-plan-schema-v99\",\"plan\":{}}");

        assertThatThrownBy(order::canonicalExecutablePlan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知 canonical plan schemaVersion");
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
                BigDecimal.valueOf(120_000_000),
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

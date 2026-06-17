package com.example.quant.agent.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.config.AgentProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AutoTradeBudgetServiceTest {

    @Test
    void treatsInputMarginAsTotalBudgetAndComputesTargetUtilization() {
        AutoTradeBudgetService service = new AutoTradeBudgetService(new AgentProperties());

        BudgetAllocation allocation = service.allocate(request("50", 1, "92", "30"));

        assertThat(allocation.totalBudgetUsdt()).isEqualByComparingTo("50");
        assertThat(allocation.targetUsedBudgetUsdt()).isEqualByComparingTo("45");
        assertThat(allocation.minTargetUsedBudgetUsdt()).isEqualByComparingTo("40");
        assertThat(allocation.maxUtilizationUsdt()).isEqualByComparingTo("50");
        assertThat(allocation.allocationMode()).isEqualTo("TARGET_UTILIZATION");
    }

    @Test
    void allocatesFullSlotBudgetsWithoutTreatingBudgetAsPerOrderMargin() {
        AutoTradeBudgetService service = new AutoTradeBudgetService(new AgentProperties());

        BudgetAllocation first = service.allocate(request("50", 1, "92", "30"));
        service.reserveBudget(UUID.randomUUID(), UUID.randomUUID(), "BTC-USDT-SWAP", first.finalOrderMarginUsdt(), new BigDecimal("50"));
        BudgetAllocation second = service.allocate(request("50", 2, "86", "30"));
        service.reserveBudget(UUID.randomUUID(), UUID.randomUUID(), "ETH-USDT-SWAP", second.finalOrderMarginUsdt(), new BigDecimal("50"));
        BudgetAllocation third = service.allocate(request("50", 3, "82", "30"));

        assertThat(first.slotWeight()).isEqualByComparingTo("0.45");
        assertThat(first.finalOrderMarginUsdt()).isEqualByComparingTo("22.5");
        assertThat(second.finalOrderMarginUsdt()).isEqualByComparingTo("15.0");
        assertThat(third.finalOrderMarginUsdt()).isEqualByComparingTo("12.5");
        assertThat(first.finalOrderMarginUsdt().add(second.finalOrderMarginUsdt()).add(third.finalOrderMarginUsdt()))
                .isEqualByComparingTo("50.0");
    }

    @Test
    void scoreFactorDoesNotReduceSpendableSlotBudget() {
        AutoTradeBudgetService service = new AutoTradeBudgetService(new AgentProperties());

        BudgetAllocation allocation = service.allocate(request("50", 1, "69", "30"));

        assertThat(allocation.scoreFactor()).isEqualByComparingTo("0.6");
        assertThat(allocation.slotBudgetUsdt()).isEqualByComparingTo("22.5");
        assertThat(allocation.qualityAdjustedBudgetUsdt()).isEqualByComparingTo("13.5");
        assertThat(allocation.finalOrderMarginUsdt()).isEqualByComparingTo("22.5");
    }

    @Test
    void capsSinglePositionAtConfiguredBudgetPercent() {
        AutoTradeBudgetService service = new AutoTradeBudgetService(new AgentProperties());

        BudgetAllocation allocation = service.allocate(request("50", 1, "95", "100"));

        assertThat(allocation.maxSinglePositionBudgetUsdt()).isEqualByComparingTo("25");
        assertThat(allocation.finalOrderMarginUsdt()).isLessThanOrEqualTo(new BigDecimal("25"));
    }

    @Test
    void reportsUnderUtilizedWithReasonsWhenAllowedSlotsAreFullButUsageTooLow() {
        AutoTradeBudgetService service = new AutoTradeBudgetService(new AgentProperties());
        service.reserveBudget(UUID.randomUUID(), UUID.randomUUID(), "BTC-USDT-SWAP", new BigDecimal("5"), new BigDecimal("50"));
        service.reserveBudget(UUID.randomUUID(), UUID.randomUUID(), "ETH-USDT-SWAP", new BigDecimal("5"), new BigDecimal("50"));
        service.reserveBudget(UUID.randomUUID(), UUID.randomUUID(), "SOL-USDT-SWAP", new BigDecimal("5"), new BigDecimal("50"));

        BudgetStatusSnapshot snapshot = service.snapshot(new BigDecimal("50"), 3, List.of("LOW_SIGNAL_SCORE", "RISK_BASED_MARGIN_LIMIT"));

        assertThat(snapshot.status()).isEqualTo("UNDER_UTILIZED");
        assertThat(snapshot.budgetUtilizationPct()).isEqualByComparingTo("30.00000000");
        assertThat(snapshot.underUtilizedReasons()).contains("LOW_SIGNAL_SCORE", "RISK_BASED_MARGIN_LIMIT");
    }

    @Test
    void concurrentReservationsCannotExceedTotalBudget() throws Exception {
        AutoTradeBudgetService service = new AutoTradeBudgetService(new AgentProperties());
        var executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<BudgetReservation> accepted = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 20; i++) {
            int index = i;
            executor.submit(() -> {
                start.await();
                BudgetReservation reservation = service.reserveBudget(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "TEST-" + index + "-USDT-SWAP",
                        new BigDecimal("5"),
                        new BigDecimal("50")
                );
                if (reservation.reserved()) {
                    accepted.add(reservation);
                }
                return null;
            });
        }

        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(accepted).hasSize(10);
        assertThat(service.snapshot(new BigDecimal("50"), 3, List.of()).inFlightReservedBudgetUsdt())
                .isEqualByComparingTo("50");
    }

    @Test
    void releaseIsIdempotentAndDoesNotIncreaseAvailableBudgetTwice() {
        AutoTradeBudgetService service = new AutoTradeBudgetService(new AgentProperties());
        BudgetReservation reservation = service.reserveBudget(
                UUID.randomUUID(), UUID.randomUUID(), "BTC-USDT-SWAP", new BigDecimal("20"), new BigDecimal("50"));

        service.release(reservation.reservationId(), "test_release");
        service.release(reservation.reservationId(), "repeat_release");

        BudgetStatusSnapshot snapshot = service.snapshot(new BigDecimal("50"), 3, List.of());
        assertThat(snapshot.inFlightReservedBudgetUsdt()).isEqualByComparingTo("0");
        assertThat(snapshot.remainingBudgetUsdt()).isEqualByComparingTo("50");
    }

    private static BudgetAllocationRequest request(String totalBudget, int slotIndex, String score, String riskMax) {
        return new BudgetAllocationRequest(
                new BigDecimal(totalBudget),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                slotIndex,
                new BigDecimal(score),
                new BigDecimal(riskMax),
                new BigDecimal("2"),
                "LOW",
                "BULLISH",
                BigDecimal.ZERO,
                new BigDecimal("2.2")
        );
    }
}

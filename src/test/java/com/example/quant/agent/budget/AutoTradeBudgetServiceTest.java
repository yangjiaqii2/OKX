package com.example.quant.agent.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoTradeBudgetServiceTest {

    @Test
    void persistsReservationAndRestoresReservedBudgetInFreshServiceInstance() {
        BudgetReservationRepository repository = mock(BudgetReservationRepository.class);
        when(repository.findByUserNameAndStatusIn("local-admin", java.util.List.of("RESERVED", "USED")))
                .thenReturn(java.util.List.of());
        when(repository.save(any(BudgetReservationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AutoTradeBudgetService service = new AutoTradeBudgetService(new AgentProperties(), repository);

        BudgetReservation reservation = service.reserveBudget(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "BTC-USDT-SWAP",
                new BigDecimal("20"),
                new BigDecimal("50")
        );

        ArgumentCaptor<BudgetReservationEntity> captor = ArgumentCaptor.forClass(BudgetReservationEntity.class);
        verify(repository).save(captor.capture());
        BudgetReservationEntity saved = captor.getValue();
        assertThat(reservation.status()).isEqualTo(BudgetReservationStatus.RESERVED);
        assertThat(saved.getReservationId()).isEqualTo(reservation.reservationId().toString());
        assertThat(saved.getUserName()).isEqualTo("local-admin");
        assertThat(saved.getStatus()).isEqualTo("RESERVED");
        assertThat(saved.getAmount()).isEqualByComparingTo("20");

        when(repository.findByUserNameAndStatusIn("local-admin", java.util.List.of("RESERVED", "USED")))
                .thenReturn(java.util.List.of(saved));
        AutoTradeBudgetService restarted = new AutoTradeBudgetService(new AgentProperties(), repository);

        assertThat(restarted.reservedBudget()).isEqualByComparingTo("20");
        assertThat(restarted.snapshot(new BigDecimal("50"), 3, java.util.List.of()).remainingBudgetUsdt())
                .isEqualByComparingTo("30");
    }

    @Test
    void persistedReservationsAreScopedByCurrentUser() {
        BudgetReservationEntity alice = persistedReservation("alice", "10");
        BudgetReservationRepository repository = mock(BudgetReservationRepository.class);
        when(repository.findByUserNameAndStatusIn("alice", java.util.List.of("RESERVED", "USED")))
                .thenReturn(java.util.List.of(alice));
        when(repository.findByUserNameAndReservationId(any(), any())).thenReturn(java.util.Optional.empty());
        when(repository.save(any(BudgetReservationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AutoTradeBudgetService service = AuthUserContext.callAs("alice",
                () -> new AutoTradeBudgetService(new AgentProperties(), repository));
        BudgetReservation reservation = AuthUserContext.callAs("alice", () -> service.reserveBudget(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ETH-USDT-SWAP",
                new BigDecimal("5"),
                new BigDecimal("50")
        ));

        ArgumentCaptor<BudgetReservationEntity> captor = ArgumentCaptor.forClass(BudgetReservationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(AuthUserContext.callAs("alice", service::reservedBudget)).isEqualByComparingTo("15");
        assertThat(reservation.reserved()).isTrue();
        assertThat(captor.getValue().getUserName()).isEqualTo("alice");
    }

    @Test
    void persistedMarkUsedAndReleaseAreIdempotent() {
        BudgetReservationEntity entity = new BudgetReservationEntity();
        entity.setReservationId(UUID.randomUUID().toString());
        entity.setUserName("local-admin");
        entity.setPlanId(UUID.randomUUID().toString());
        entity.setPendingOrderId(UUID.randomUUID().toString());
        entity.setSymbol("BTC-USDT-SWAP");
        entity.setAmount(new BigDecimal("15"));
        entity.setStatus("RESERVED");
        entity.setReason("RESERVED");
        entity.setCreatedAt(java.time.Instant.now());
        entity.setUpdatedAt(java.time.Instant.now());
        BudgetReservationRepository repository = mock(BudgetReservationRepository.class);
        when(repository.findByUserNameAndReservationId("local-admin", entity.getReservationId()))
                .thenReturn(java.util.Optional.of(entity));
        when(repository.save(any(BudgetReservationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AutoTradeBudgetService service = new AutoTradeBudgetService(new AgentProperties(), repository);

        BudgetReservation used = service.markUsed(UUID.fromString(entity.getReservationId()));
        BudgetReservation released = service.release(UUID.fromString(entity.getReservationId()), "POSITION_CLOSED");
        BudgetReservation releasedAgain = service.release(UUID.fromString(entity.getReservationId()), "SECOND_CALL");

        assertThat(used.status()).isEqualTo(BudgetReservationStatus.USED);
        assertThat(released.status()).isEqualTo(BudgetReservationStatus.RELEASED);
        assertThat(releasedAgain.status()).isEqualTo(BudgetReservationStatus.RELEASED);
        assertThat(entity.getStatus()).isEqualTo("RELEASED");
        assertThat(entity.getReason()).isEqualTo("POSITION_CLOSED");
    }

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

    private static BudgetReservationEntity persistedReservation(String userName, String amount) {
        BudgetReservationEntity entity = new BudgetReservationEntity();
        entity.setReservationId(UUID.randomUUID().toString());
        entity.setUserName(userName);
        entity.setPlanId(UUID.randomUUID().toString());
        entity.setPendingOrderId(UUID.randomUUID().toString());
        entity.setSymbol("BTC-USDT-SWAP");
        entity.setAmount(new BigDecimal(amount));
        entity.setStatus("RESERVED");
        entity.setReason("RESERVED");
        entity.setCreatedAt(java.time.Instant.now());
        entity.setUpdatedAt(java.time.Instant.now());
        return entity;
    }
}

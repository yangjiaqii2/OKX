package com.example.quant.strategydecision;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class StrategyDecisionServiceTest {
    @Autowired
    private StrategyDecisionRepository repository;

    @Test
    void sameOwnerStrategyAndClosedCandleCanOnlyBeClaimedOnceAcrossServiceRestart() {
        String instId = "IDEMP-" + UUID.randomUUID() + "-USDT-SWAP";
        Instant candle = Instant.parse("2026-08-09T12:00:00Z");
        StrategyDecisionService firstProcess = new StrategyDecisionService(repository, Duration.ofMinutes(5));
        StrategyDecisionService restartedProcess = new StrategyDecisionService(repository, Duration.ofMinutes(5));

        StrategyDecisionService.DecisionClaim first = firstProcess.claim(
                "alice", "AUTO_CONTRACT_V1", instId, "5m", candle, candle.plusSeconds(10));
        StrategyDecisionService.DecisionClaim duplicate = restartedProcess.claim(
                "alice", "AUTO_CONTRACT_V1", instId, "5m", candle, candle.plusSeconds(20));

        assertThat(first.claimed()).isTrue();
        assertThat(first.clientOrderId()).hasSizeLessThanOrEqualTo(32);
        assertThat(duplicate.claimed()).isFalse();
        assertThat(duplicate.decisionId()).isEqualTo(first.decisionId());
    }

    @Test
    void staleProcessingDecisionBecomesReconcilingAndIsNeverAutomaticallyReclaimed() {
        String instId = "CRASH-" + UUID.randomUUID() + "-USDT-SWAP";
        Instant started = Instant.parse("2026-08-09T12:00:00Z");
        StrategyDecisionService service = new StrategyDecisionService(repository, Duration.ofMinutes(5));
        StrategyDecisionService.DecisionClaim claim = service.claim(
                "alice", "AUTO_CONTRACT_V1", instId, "5m", started, started);

        int recovered = service.recoverStaleProcessing(started.plus(Duration.ofMinutes(6)));
        StrategyDecisionService.DecisionClaim afterRecovery = service.claim(
                "alice", "AUTO_CONTRACT_V1", instId, "5m", started, started.plus(Duration.ofMinutes(7)));

        assertThat(recovered).isEqualTo(1);
        assertThat(repository.findById(claim.decisionId()).orElseThrow().getStatus())
                .isEqualTo(StrategyDecisionStatus.RECONCILING);
        assertThat(afterRecovery.claimed()).isFalse();
    }

    @Test
    void historicalTimezoneShiftStillDeduplicatesByStableClientOrderId() {
        String instId = "TIMEZONE-" + UUID.randomUUID() + "-USDT-SWAP";
        Instant candle = Instant.parse("2026-08-09T12:00:00Z");
        StrategyDecisionService service = new StrategyDecisionService(repository, Duration.ofMinutes(5));
        StrategyDecisionService.DecisionClaim original = service.claim(
                "alice", "AUTO_CONTRACT_V1", instId, "5m", candle, candle);
        StrategyDecisionEntity historical = repository.findById(original.decisionId()).orElseThrow();
        historical.setCandleTimestamp(candle.plus(Duration.ofHours(8)));
        repository.saveAndFlush(historical);

        StrategyDecisionService.DecisionClaim duplicate = service.claim(
                "alice", "AUTO_CONTRACT_V1", instId, "5m", candle, candle.plusSeconds(30));

        assertThat(duplicate.claimed()).isFalse();
        assertThat(duplicate.decisionId()).isEqualTo(original.decisionId());
    }

    @Test
    void concurrentScansStillClaimOneDecision() throws Exception {
        String instId = "CONCURRENT-" + UUID.randomUUID() + "-USDT-SWAP";
        Instant candle = Instant.parse("2026-08-09T12:05:00Z");
        StrategyDecisionService service = new StrategyDecisionService(repository, Duration.ofMinutes(5));
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return service.claim("alice", "AUTO_CONTRACT_V1", instId, "5m", candle, candle);
            });
            var second = pool.submit(() -> {
                start.await();
                return service.claim("alice", "AUTO_CONTRACT_V1", instId, "5m", candle, candle);
            });
            start.countDown();

            long claimed = java.util.stream.Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .filter(StrategyDecisionService.DecisionClaim::claimed)
                    .count();
            assertThat(claimed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}

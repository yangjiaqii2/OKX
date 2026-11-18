package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenInterestServiceTest {

    @Test
    void persistsObservationAndReturnsPreviousValueAcrossServiceInstances() {
        OpenInterestSnapshotRepository repository = mock(OpenInterestSnapshotRepository.class);
        AtomicReference<OpenInterestSnapshotEntity> row = new AtomicReference<>();
        when(repository.findById("BTC-USDT-SWAP")).thenAnswer(ignored -> Optional.ofNullable(row.get()));
        when(repository.save(any(OpenInterestSnapshotEntity.class))).thenAnswer(invocation -> {
            OpenInterestSnapshotEntity saved = invocation.getArgument(0);
            row.set(saved);
            return saved;
        });

        OpenInterestService firstProcess = new OpenInterestService(repository);
        OpenInterestService.Observation first = firstProcess.observe(
                "BTC-USDT-SWAP", new BigDecimal("100"), Instant.parse("2026-08-10T00:00:00Z"));
        OpenInterestService secondProcess = new OpenInterestService(repository);
        OpenInterestService.Observation second = secondProcess.observe(
                "BTC-USDT-SWAP", new BigDecimal("110"), Instant.parse("2026-08-10T00:05:00Z"));

        assertThat(first.previous()).isNull();
        assertThat(second.previous()).isEqualByComparingTo("100");
        assertThat(second.current()).isEqualByComparingTo("110");
    }
}

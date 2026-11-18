package com.example.quant.crypto.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractCandidateRelativeStrengthTest {
    @Test
    void withRelativeStrengthAggregatesSnapshotWithoutChangingOtherFields() {
        ContractCandidate candidate = new ContractCandidate(
                null, "BTC-USDT-SWAP", "BTC", "USDT", BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, null, BigDecimal.ONE,
                50, new ContractFactorScore(1, 1, 1, 1, 1, 1, 1), BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 1, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null, null,
                List.of(), List.of(), Instant.now());
        RelativeStrengthSnapshot snapshot = new RelativeStrengthSnapshot("BTC-USDT-SWAP",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, DataQuality.FRESH);

        ContractCandidate enriched = candidate.withRelativeStrength(snapshot);

        assertThat(enriched.relativeStrength()).isSameAs(snapshot);
        assertThat(enriched.score()).isEqualTo(candidate.score());
        assertThat(enriched.finalRankScore()).isEqualTo(candidate.finalRankScore());
    }
}

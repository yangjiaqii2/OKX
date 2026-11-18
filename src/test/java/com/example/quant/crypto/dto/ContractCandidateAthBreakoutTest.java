package com.example.quant.crypto.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.dto.AthBreakoutSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractCandidateAthBreakoutTest {
    @Test
    void withAthBreakoutAggregatesWithoutChangingRankOrScore() {
        ContractCandidate candidate = new ContractCandidate(
                null, "X-USDT-SWAP", "X", "USDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, null,
                BigDecimal.ONE, 50, new ContractFactorScore(1, 1, 1, 1, 1, 1, 1), BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, null, null, null, List.of(), List.of(), Instant.now());
        AthBreakoutSnapshot snapshot = new AthBreakoutSnapshot("X-USDT-SWAP", BigDecimal.TEN,
                new BigDecimal("9"), new BigDecimal("-10"), "NEAR_ATH", DataQuality.FRESH, 2, false);

        ContractCandidate enriched = candidate.withAthBreakout(snapshot);

        assertThat(enriched.athBreakout()).isEqualTo(snapshot);
        assertThat(enriched.score()).isEqualTo(candidate.score());
        assertThat(enriched.finalRankScore()).isEqualTo(candidate.finalRankScore());
    }
}

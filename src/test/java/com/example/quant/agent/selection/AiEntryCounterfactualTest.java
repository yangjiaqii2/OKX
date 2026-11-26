package com.example.quant.agent.selection;

import static org.assertj.core.api.Assertions.assertThat;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.market.DirectionBias;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiEntryCounterfactualTest {
    private static final Instant MINUTE = Instant.parse("2026-11-20T07:12:00Z");

    @Test
    void calculatesLongAndShortAndExcludesPartialBoundaryCandles() {
        var candles = List.of(bar(0, 140, 60, 80), bar(1, 110, 92, 102), bar(2, 107, 96, 102),
                bar(3, 108, 96, 103), bar(4, 107, 95, 104), bar(5, 200, 20, 150));
        var skipped = MINUTE.plusSeconds(23);
        var entry = BigDecimal.valueOf(100);
        var longResult = AiEntryCounterfactual.measure(skipped, 5, entry, DirectionBias.BULLISH, candles).orElseThrow();
        var shortResult = AiEntryCounterfactual.measure(skipped, 5, entry, DirectionBias.BEARISH, candles).orElseThrow();
        assertThat(longResult.returnPct()).isEqualByComparingTo("4");
        assertThat(longResult.mfePct()).isEqualByComparingTo("10");
        assertThat(longResult.maePct()).isEqualByComparingTo("-8");
        assertThat(shortResult.returnPct()).isEqualByComparingTo("-4");
        assertThat(shortResult.mfePct()).isEqualByComparingTo("8");
        assertThat(shortResult.maePct()).isEqualByComparingTo("-10");
        assertThat(longResult.priceAt()).isEqualTo(MINUTE.plusSeconds(300));
    }

    @Test
    void leavesGapUnmeasured() {
        assertThat(AiEntryCounterfactual.measure(MINUTE, 5, BigDecimal.valueOf(100), DirectionBias.BULLISH,
                List.of(bar(4, 120, 80, 103)))).isEmpty();
    }

    private static ContractCandle bar(long minute, int high, int low, int close) {
        return new ContractCandle(MINUTE.plusSeconds(minute * 60), BigDecimal.valueOf(100),
                BigDecimal.valueOf(high), BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.ONE);
    }
}

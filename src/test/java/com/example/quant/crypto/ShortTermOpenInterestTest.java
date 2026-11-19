package com.example.quant.crypto;

import com.example.quant.market.DirectionBias;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ShortTermOpenInterestTest {
    @Test
    void risingInterestConfirmsBothDirectionsOnlyWhenShortTermPriceAgrees() {
        var up = BigDecimal.ONE;
        var down = up.negate();
        var zero = BigDecimal.ZERO;
        assertThat(ContractSignalAnalyzer.oiConsistencyScore(DirectionBias.BULLISH, up, up, up)).isEqualTo(85);
        assertThat(ContractSignalAnalyzer.oiConsistencyScore(DirectionBias.BEARISH, down, up, up)).isEqualTo(85);
        assertThat(ContractSignalAnalyzer.oiConsistencyScore(DirectionBias.BEARISH, up, up, up)).isLessThan(85);
        assertThat(ContractSignalAnalyzer.oiConsistencyScore(DirectionBias.BULLISH, up, up, zero)).isEqualTo(50);
        assertThat(ContractSignalAnalyzer.oiConsistencyScore(DirectionBias.BULLISH, null, up, up)).isEqualTo(50);
    }

    @Test
    void selectsNearestComparableSnapshotAndRejectsStaleOrUnrelatedData() {
        var history = new ShortTermOpenInterest();
        long start = 1_000_000;
        long now = start + 300_000;
        assertThat(history.previous("ETH", start, BigDecimal.TEN, start)).isNull();
        assertThat(history.previous("ETH", start + 60_000, BigDecimal.ONE, start + 60_000)).isNull();
        assertThat(history.previous("BTC", now, BigDecimal.ONE, now)).isNull();
        assertThat(history.previous("ETH", now, BigDecimal.ONE, now)).isEqualTo(BigDecimal.TEN);
        assertThat(history.previous("ETH", now - 1, BigDecimal.ONE, now)).isNull();
        assertThat(history.previous("ETH", now, BigDecimal.ONE, now + 65_000)).isNull();
        assertThat(history.previous("ETH", now + 600_000, BigDecimal.ONE, now + 600_000)).isNull();
    }
}

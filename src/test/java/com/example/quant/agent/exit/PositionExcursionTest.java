package com.example.quant.agent.exit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.exit.PositionExcursion.TradeSide;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionExcursionTest {

    private static final Instant T0 = Instant.parse("2026-09-17T00:00:00Z");
    private static final BigDecimal ENTRY = new BigDecimal("1.00");
    private static final BigDecimal RISK = new BigDecimal("0.05");

    @Test
    void longTracksTheHighAsMfeAndTheLowAsMae() {
        PositionExcursion excursion = PositionExcursion.seed(TradeSide.LONG, ENTRY, RISK, new BigDecimal("1.00"), T0)
                .observe(TradeSide.LONG, ENTRY, RISK, new BigDecimal("1.90"), T0.plusSeconds(1))
                .observe(TradeSide.LONG, ENTRY, RISK, new BigDecimal("0.95"), T0.plusSeconds(2));

        assertThat(excursion.highestSinceEntry()).isEqualByComparingTo("1.90");
        assertThat(excursion.lowestSinceEntry()).isEqualByComparingTo("0.95");
        assertThat(excursion.mfePrice()).isEqualByComparingTo("1.90");
        assertThat(excursion.maePrice()).isEqualByComparingTo("0.95");
        assertThat(excursion.mfeR()).isEqualByComparingTo("18");
        assertThat(excursion.maeR()).isEqualByComparingTo("1");
        assertThat(excursion.mfePct()).isEqualByComparingTo("90");
        assertThat(excursion.mfeAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void shortIsTheExactMirrorOfLong() {
        PositionExcursion excursion = PositionExcursion.seed(TradeSide.SHORT, ENTRY, RISK, new BigDecimal("1.00"), T0)
                .observe(TradeSide.SHORT, ENTRY, RISK, new BigDecimal("0.10"), T0.plusSeconds(1))
                .observe(TradeSide.SHORT, ENTRY, RISK, new BigDecimal("1.05"), T0.plusSeconds(2));

        assertThat(excursion.highestSinceEntry()).isEqualByComparingTo("1.05");
        assertThat(excursion.lowestSinceEntry()).isEqualByComparingTo("0.10");
        assertThat(excursion.mfePrice()).isEqualByComparingTo("0.10");
        assertThat(excursion.maePrice()).isEqualByComparingTo("1.05");
        assertThat(excursion.mfeR()).isEqualByComparingTo("18");
        assertThat(excursion.maeR()).isEqualByComparingTo("1");
    }

    @Test
    void percentageAndRMoveIndependently() {
        // A wide 1R makes a large percentage move a small R move.
        PositionExcursion excursion = PositionExcursion.seed(TradeSide.LONG, ENTRY, new BigDecimal("0.50"),
                new BigDecimal("1.10"), T0);

        assertThat(excursion.mfePct()).isEqualByComparingTo("10");
        assertThat(excursion.mfeR()).isEqualByComparingTo("0.2");
    }

    @Test
    void extremesNeverMoveBackwards() {
        PositionExcursion excursion = PositionExcursion.seed(TradeSide.LONG, ENTRY, RISK, new BigDecimal("1.50"), T0)
                .observe(TradeSide.LONG, ENTRY, RISK, new BigDecimal("1.10"), T0.plusSeconds(1));

        assertThat(excursion.highestSinceEntry()).isEqualByComparingTo("1.50");
        assertThat(excursion.mfeR()).isEqualByComparingTo("10");
        assertThat(excursion.mfeAt()).isEqualTo(T0);
    }

    @Test
    void ignoresInvalidObservationsRatherThanCorruptingThePeak() {
        PositionExcursion excursion = PositionExcursion.seed(TradeSide.LONG, ENTRY, RISK, new BigDecimal("1.50"), T0);

        assertThat(excursion.observe(TradeSide.LONG, ENTRY, RISK, null, T0)).isSameAs(excursion);
        assertThat(excursion.observe(TradeSide.LONG, ENTRY, RISK, BigDecimal.ZERO, T0)).isSameAs(excursion);
    }

    @Test
    void rejectsFactsThatWouldMakeRUndefined() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> PositionExcursion.seed(TradeSide.LONG, ENTRY, BigDecimal.ZERO,
                        new BigDecimal("1.10"), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INITIAL_RISK");
    }
}

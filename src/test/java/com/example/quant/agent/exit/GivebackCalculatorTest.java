package com.example.quant.agent.exit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GivebackCalculatorTest {

    @Test
    void recordsGivebackWhenProfitWasMostlyReturned() {
        GivebackCalculator.Giveback result =
                GivebackCalculator.calculate(new BigDecimal("5"), new BigDecimal("0.2"));

        assertThat(result.givebackR()).isEqualByComparingTo("4.8");
        assertThat(result.givebackRatio()).isEqualByComparingTo("0.96");
        assertThat(result.complete()).isTrue();
    }

    @Test
    void flagsTheSevereCaseWhereAThreeRWinnerEndedNegative() {
        GivebackCalculator.Giveback result =
                GivebackCalculator.calculate(new BigDecimal("3"), new BigDecimal("-1"));

        assertThat(result.givebackR()).isEqualByComparingTo("4");
        assertThat(result.givebackRatio()).isEqualByComparingTo("1.33333333");
    }

    @Test
    void neverDividesByZeroWhenThereWasNoFavourableExcursion() {
        GivebackCalculator.Giveback result =
                GivebackCalculator.calculate(BigDecimal.ZERO, new BigDecimal("-1"));

        assertThat(result.givebackR()).isEqualByComparingTo("0");
        assertThat(result.givebackRatio()).isNull();
        assertThat(result.status()).isEqualTo("MFE_NOT_POSITIVE");
    }

    @Test
    void negativeMfeIsTreatedAsNoExcursion() {
        GivebackCalculator.Giveback result =
                GivebackCalculator.calculate(new BigDecimal("-0.5"), new BigDecimal("-1"));

        assertThat(result.givebackR()).isEqualByComparingTo("0");
        assertThat(result.givebackRatio()).isNull();
    }

    @Test
    void realisedResultBetterThanObservedPeakIsNotNegativeGiveback() {
        // Sampling can miss the true peak; never report a negative giveback.
        GivebackCalculator.Giveback result =
                GivebackCalculator.calculate(new BigDecimal("2"), new BigDecimal("2.5"));

        assertThat(result.givebackR()).isEqualByComparingTo("0");
        assertThat(result.givebackRatio()).isEqualByComparingTo("0");
        assertThat(result.status()).isEqualTo("NO_GIVEBACK");
    }

    @Test
    void unknownRealisedResultKeepsGivebackEmptyRatherThanGuessing() {
        GivebackCalculator.Giveback result = GivebackCalculator.calculate(new BigDecimal("3"), null);

        assertThat(result.givebackR()).isNull();
        assertThat(result.givebackRatio()).isNull();
        assertThat(result.status()).isEqualTo("REALIZED_R_UNKNOWN");
    }
}

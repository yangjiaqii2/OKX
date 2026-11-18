package com.example.quant.position;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PositionSizingServiceTest {

    @Test
    void xauDiagnosticFixtureSeparatesBudgetFromPlannedStopLoss() {
        PositionSizingResult result = new PositionSizingService().calculate(new PositionSizingRequest(
                new BigDecimal("63300"), new BigDecimal("63300"), new BigDecimal("4415"),
                new BigDecimal("4384"), new BigDecimal("4477"), 1,
                new BigDecimal("0.01"), new BigDecimal("0.05"), new BigDecimal("1.5"),
                new BigDecimal("0.02"), RiskLevel.LOW));
        assertThat(result.accepted()).isTrue();
        assertThat(result.riskBudgetAmount()).isEqualByComparingTo("633");
        assertThat(result.estimatedStopLossAmount()).isBetween(new BigDecimal("21"), new BigDecimal("23"));
        assertThat(result.estimatedStopLossAmount()).isLessThan(result.riskBudgetAmount());
    }

    @Test
    void btcDiagnosticFixtureSeparatesBudgetFromPlannedStopLoss() {
        PositionSizingResult result = new PositionSizingService().calculate(new PositionSizingRequest(
                new BigDecimal("63300"), new BigDecimal("63300"), new BigDecimal("63460"),
                new BigDecimal("64401"), new BigDecimal("61578"), 1,
                new BigDecimal("0.01"), new BigDecimal("0.05"), new BigDecimal("1.5"),
                new BigDecimal("0.02"), RiskLevel.LOW));
        assertThat(result.accepted()).isTrue();
        assertThat(result.riskBudgetAmount()).isEqualByComparingTo("633");
        assertThat(result.estimatedStopLossAmount()).isBetween(new BigDecimal("46"), new BigDecimal("48"));
        assertThat(result.estimatedStopLossAmount()).isLessThan(result.riskBudgetAmount());
    }

    @Test
    void shrinksPositionWhenMarginExceedsConfiguredLimit() {
        PositionSizingService service = new PositionSizingService();

        PositionSizingResult result = service.calculate(new PositionSizingRequest(
                BigDecimal.valueOf(10000),
                BigDecimal.valueOf(9000),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(99),
                BigDecimal.valueOf(102),
                2,
                BigDecimal.valueOf(0.01),
                BigDecimal.valueOf(0.05),
                BigDecimal.valueOf(1.5),
                BigDecimal.valueOf(0.02),
                RiskLevel.MEDIUM
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.suggestedMargin()).isLessThanOrEqualTo(BigDecimal.valueOf(500));
        assertThat(result.warningList()).contains("保证金超过单笔上限，已自动缩小仓位");
    }

    @Test
    void rejectsWhenRiskRewardIsTooLow() {
        PositionSizingService service = new PositionSizingService();

        PositionSizingResult result = service.calculate(new PositionSizingRequest(
                BigDecimal.valueOf(10000),
                BigDecimal.valueOf(9000),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(99),
                BigDecimal.valueOf(100.5),
                2,
                BigDecimal.valueOf(0.01),
                BigDecimal.valueOf(0.05),
                BigDecimal.valueOf(1.5),
                BigDecimal.valueOf(0.02),
                RiskLevel.MEDIUM
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).contains("盈亏比");
    }

    @Test
    void rejectsLongGeometryWhenStopIsAboveEntry() {
        PositionSizingService service = new PositionSizingService();

        PositionSizingResult result = service.calculate(new PositionSizingRequest(
                BigDecimal.valueOf(10000), BigDecimal.valueOf(9000), BigDecimal.valueOf(100),
                BigDecimal.valueOf(102), BigDecimal.valueOf(105), 2, BigDecimal.valueOf(0.01),
                BigDecimal.valueOf(0.05), BigDecimal.valueOf(1.5), BigDecimal.valueOf(0.02), RiskLevel.LOW
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).contains("价格方向");
    }

    @Test
    void acceptsValidShortGeometry() {
        PositionSizingService service = new PositionSizingService();

        PositionSizingResult result = service.calculate(new PositionSizingRequest(
                BigDecimal.valueOf(10000), BigDecimal.valueOf(9000), BigDecimal.valueOf(100),
                BigDecimal.valueOf(102), BigDecimal.valueOf(96), 2, BigDecimal.valueOf(0.01),
                BigDecimal.valueOf(0.05), BigDecimal.valueOf(1.5), BigDecimal.valueOf(0.02), RiskLevel.LOW
        ));

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void roundedExecutableMarginDoesNotExceedSingleTradeLossBudget() {
        PositionSizingResult result = new PositionSizingService().calculate(new PositionSizingRequest(
                new BigDecimal("199.84340791903654"), new BigDecimal("199.86627646345272"),
                new BigDecimal("1493.87"), new BigDecimal("1532.10742857"), new BigDecimal("1425"), 8,
                new BigDecimal("0.01"), new BigDecimal("0.33333334"), new BigDecimal("1.5"),
                new BigDecimal("0.02"), RiskLevel.LOW));

        BigDecimal executableMargin = result.suggestedMargin().setScale(4, java.math.RoundingMode.DOWN);
        BigDecimal stopDistance = new BigDecimal("1532.10742857").subtract(new BigDecimal("1493.87"))
                .divide(new BigDecimal("1493.87"), 8, java.math.RoundingMode.HALF_UP);
        BigDecimal executableLoss = executableMargin.multiply(BigDecimal.valueOf(8)).multiply(stopDistance)
                .setScale(8, java.math.RoundingMode.HALF_UP);

        assertThat(result.accepted()).isTrue();
        assertThat(executableLoss).isLessThanOrEqualTo(result.riskBudgetAmount().setScale(8, java.math.RoundingMode.HALF_UP));
    }
}

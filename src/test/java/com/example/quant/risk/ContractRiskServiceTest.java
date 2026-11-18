package com.example.quant.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ContractRiskServiceTest {

    @Test
    void rejectsWhenEmergencyStopIsEnabled() {
        ContractRiskService service = new ContractRiskService();

        RiskCheckResult result = service.check(ContractRiskRequest.safeDefaults()
                .withEmergencyStop(true));

        assertThat(result.passed()).isFalse();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.BLOCKED);
        assertThat(result.rejectCode()).isEqualTo("EMERGENCY_STOP");
    }

    @Test
    void rejectsWithoutStopLoss() {
        ContractRiskService service = new ContractRiskService();

        RiskCheckResult result = service.check(ContractRiskRequest.safeDefaults()
                .withStopLossPrice(null));

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectCode()).isEqualTo("MISSING_STOP_LOSS");
    }

    @Test
    void rejectsWhenLeverageExceedsMaxConfig() {
        ContractRiskService service = new ContractRiskService();

        RiskCheckResult result = service.check(ContractRiskRequest.safeDefaults()
                .withLeverage(5)
                .withMaxLeverage(3));

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectCode()).isEqualTo("LEVERAGE_TOO_HIGH");
    }

    @Test
    void rejectsWhenMarketDataIsDelayed() {
        ContractRiskService service = new ContractRiskService();

        RiskCheckResult result = service.check(ContractRiskRequest.safeDefaults()
                .withMarketDelayMs(6000)
                .withMaxMarketDelayMs(5000));

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectCode()).isEqualTo("MARKET_DELAY");
    }

    @Test
    void passesConservativeSetupWithWarnings() {
        ContractRiskService service = new ContractRiskService();

        RiskCheckResult result = service.check(ContractRiskRequest.safeDefaults()
                .withFundingRate(BigDecimal.valueOf(0.0009)));

        assertThat(result.passed()).isTrue();
        assertThat(result.riskLevel()).isIn(RiskLevel.LOW, RiskLevel.MEDIUM);
    }

    @Test
    void volatileCrowdedSetupMustUseReducedDynamicLeverage() {
        ContractRiskService service = new ContractRiskService();

        RiskCheckResult result = service.check(ContractRiskRequest.safeDefaults()
                .withLeverage(3)
                .withVolatility(BigDecimal.valueOf(0.07))
                .withFundingRate(BigDecimal.valueOf(0.00095))
                .withSignalScore(64));

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectCode()).isEqualTo("LEVERAGE_ABOVE_DYNAMIC_CAP");
        assertThat(result.adjustedLeverage()).isEqualTo(1);
    }

    @Test
    void highRiskSetupCanPassOnlyWithOneXAndSmallerLossBudget() {
        ContractRiskService service = new ContractRiskService();

        RiskCheckResult result = service.check(ContractRiskRequest.safeDefaults()
                .withLeverage(1)
                .withVolatility(BigDecimal.valueOf(0.085))
                .withFundingRate(BigDecimal.valueOf(0.00085))
                .withSignalScore(68));

        assertThat(result.passed()).isTrue();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.adjustedLeverage()).isEqualTo(1);
        assertThat(result.maxLossAmount()).isEqualByComparingTo("25.00000000");
        assertThat(result.warningList()).anyMatch(item -> item.contains("volatility"));
    }

    @Test
    void exactlyFivePercentStopRemainsAllowedAndIsNotClassifiedAsAboveFive() {
        RiskCheckResult result = new ContractRiskService().check(ContractRiskRequest.safeDefaults()
                .withLeverage(1)
                .withStopLossPrice(new BigDecimal("95")));

        assertThat(result.passed()).isTrue();
        assertThat(result.warningList()).noneMatch(item -> item.contains("wide stop distance"));
    }

    @Test
    void rejectsWhenEstimatedStopLossExceedsSingleTradeLossBudget() {
        ContractRiskService service = new ContractRiskService();
        ContractRiskRequest defaults = ContractRiskRequest.safeDefaults();
        ContractRiskRequest request = new ContractRiskRequest(
                false, true, 1000, 5000,
                new BigDecimal("10000"), new BigDecimal("3000"), BigDecimal.ONE,
                1, 3, new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("2"),
                new BigDecimal("1.5"), defaults.fundingRate(), defaults.maxAbsFundingRate(),
                defaults.volume24h(), defaults.minVolume24h(), RiskLevel.LOW, new BigDecimal("0.02"),
                new BigDecimal("0.08"), 75, 58, new BigDecimal("0.01")
        );

        RiskCheckResult result = service.check(request);

        assertThat(result.passed()).isFalse();
        assertThat(result.rejectCode()).isEqualTo("MAX_LOSS_EXCEEDED");
    }
}

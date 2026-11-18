package com.example.quant.leverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LeverageDecisionServiceTest {

    @Test
    void capsLeverageByConfiguration() {
        LeverageDecisionService service = new LeverageDecisionService();

        LeverageDecisionResult result = service.decide(new LeverageDecisionRequest(
                "BTC-USDT-SWAP",
                90,
                BigDecimal.valueOf(0.01),
                BigDecimal.valueOf(0.0001),
                BigDecimal.valueOf(0.03),
                BigDecimal.valueOf(0.01),
                BigDecimal.valueOf(10000),
                3,
                RiskLevel.LOW,
                BigDecimal.valueOf(0.9)
        ));

        assertThat(result.suggestedLeverage()).isLessThanOrEqualTo(3);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void highRiskReducesLeverageToOne() {
        LeverageDecisionService service = new LeverageDecisionService();

        LeverageDecisionResult result = service.decide(new LeverageDecisionRequest(
                "BTC-USDT-SWAP",
                60,
                BigDecimal.valueOf(0.12),
                BigDecimal.valueOf(0.002),
                BigDecimal.valueOf(0.15),
                BigDecimal.valueOf(0.08),
                BigDecimal.valueOf(10000),
                3,
                RiskLevel.HIGH,
                BigDecimal.valueOf(0.5)
        ));

        assertThat(result.suggestedLeverage()).isEqualTo(1);
        assertThat(result.leverageRiskList()).isNotEmpty();
    }

    @Test
    void clampsConfiguredLeverageToSystemMaximumTen() {
        LeverageDecisionService service = new LeverageDecisionService();

        LeverageDecisionResult result = service.decide(new LeverageDecisionRequest(
                "BTC-USDT-SWAP", 95, new BigDecimal("0.01"), new BigDecimal("0.0001"),
                new BigDecimal("0.05"), new BigDecimal("0.01"), new BigDecimal("10000"),
                20, RiskLevel.LOW, new BigDecimal("0.90")
        ));

        assertThat(result.suggestedLeverage()).isEqualTo(10);
        assertThat(result.maxAllowedLeverage()).isEqualTo(10);
    }

    @Test
    void nonMajorContractCanUseTenWhenHardRiskAllowsIt() {
        LeverageDecisionService service = new LeverageDecisionService();

        LeverageDecisionResult result = service.decide(new LeverageDecisionRequest(
                "XAU-USDT-SWAP", 95, new BigDecimal("0.01"), new BigDecimal("0.0001"),
                new BigDecimal("0.05"), new BigDecimal("0.01"), new BigDecimal("10000"),
                10, RiskLevel.LOW, new BigDecimal("0.90")
        ));

        assertThat(result.suggestedLeverage()).isEqualTo(10);
    }

    @Test
    void blockedRiskProducesNoLeverage() {
        LeverageDecisionService service = new LeverageDecisionService();

        LeverageDecisionResult result = service.decide(new LeverageDecisionRequest(
                "BTC-USDT-SWAP",
                40,
                BigDecimal.valueOf(0.2),
                BigDecimal.valueOf(0.01),
                BigDecimal.valueOf(0.3),
                BigDecimal.valueOf(0.1),
                BigDecimal.valueOf(10000),
                3,
                RiskLevel.BLOCKED,
                BigDecimal.valueOf(0.2)
        ));

        assertThat(result.suggestedLeverage()).isZero();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.BLOCKED);
    }
}

package com.example.quant.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ContractScoreCalculatorTest {

    @Test
    void downgradesContractWhenRiskSignalsAreHigh() {
        ContractScoreCalculator calculator = new ContractScoreCalculator();

        ScoreDetail result = calculator.calculate(new ContractScoreInput(
                20,
                BigDecimal.valueOf(0.4),
                BigDecimal.valueOf(0.003),
                BigDecimal.valueOf(0.08),
                0,
                5
        ));

        assertThat(result.score()).isLessThan(50);
        assertThat(result.riskList()).contains("资金费率异常", "波动率过高");
    }

    @Test
    void scoresHealthyContractSetup() {
        ContractScoreCalculator calculator = new ContractScoreCalculator();

        ScoreDetail result = calculator.calculate(new ContractScoreInput(
                80,
                BigDecimal.valueOf(2.2),
                BigDecimal.valueOf(0.0001),
                BigDecimal.valueOf(0.02),
                2,
                0
        ));

        assertThat(result.score()).isGreaterThanOrEqualTo(70);
        assertThat(result.reasonList()).contains("趋势强度较好", "成交量放大");
    }
}

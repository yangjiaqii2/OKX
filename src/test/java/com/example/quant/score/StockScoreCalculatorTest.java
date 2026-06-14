package com.example.quant.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StockScoreCalculatorTest {

    @Test
    void excludesStStockAndReturnsHighRiskSuggestion() {
        StockScoreCalculator calculator = new StockScoreCalculator();

        ScoreDetail result = calculator.calculate(new StockScoreInput(
                true,
                false,
                true,
                true,
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(1.7),
                true,
                3,
                1,
                0
        ));

        assertThat(result.score()).isLessThanOrEqualTo(20);
        assertThat(result.recommendLevel()).isEqualTo("暂不推荐");
        assertThat(result.riskList()).contains("ST风险");
    }

    @Test
    void scoresStrongCandidateWithoutForbiddenTradingWords() {
        StockScoreCalculator calculator = new StockScoreCalculator();

        ScoreDetail result = calculator.calculate(new StockScoreInput(
                false,
                false,
                true,
                true,
                BigDecimal.valueOf(4),
                BigDecimal.valueOf(2),
                false,
                8,
                5,
                0
        ));

        assertThat(result.score()).isGreaterThanOrEqualTo(70);
        assertThat(result.recommendLevel()).isIn("重点观察", "可以观察");
        assertThat(result.summary()).doesNotContain("买入", "卖出", "下单", "满仓", "梭哈", "必涨");
    }
}

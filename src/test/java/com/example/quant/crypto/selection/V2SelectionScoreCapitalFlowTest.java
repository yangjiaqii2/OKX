package com.example.quant.crypto.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 资金流维度：OI 必须用 USD/USDT 名义价值；各子项有界；缺失不得伪装成零覆盖。
 */
class V2SelectionScoreCapitalFlowTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("不同币价、币数量但相同名义价值的 OI，对资金流 OI 子项贡献相同")
    void sameNotionalOpenInterestProducesSameOiContribution() {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        // 1000 币 × $50 = $50,000；200 币 × $250 = $50,000
        ContractCandidate lowPrice = capitalFlowCandidate(
                new BigDecimal("50000000"), new BigDecimal("1000"), new BigDecimal("50000"),
                new BigDecimal("100000"), new BigDecimal("100000"));
        ContractCandidate highPrice = capitalFlowCandidate(
                new BigDecimal("50000000"), new BigDecimal("200"), new BigDecimal("50000"),
                new BigDecimal("100000"), new BigDecimal("100000"));

        BigDecimal left = calculator.calculate(lowPrice).dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW);
        BigDecimal right = calculator.calculate(highPrice).dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW);

        assertThat(left).isEqualByComparingTo(right);
    }

    @Test
    @DisplayName("OI 名义价值缺失时资金流维度退出，覆盖率与排名分同步下降")
    void missingOpenInterestUsdExcludesCapitalFlowFromCoverage() {
        ContractCandidate withUsd = capitalFlowCandidate(
                new BigDecimal("500000000"), new BigDecimal("1000"), new BigDecimal("50000000"),
                new BigDecimal("200000"), new BigDecimal("200000"));
        ContractCandidate missing = capitalFlowCandidate(
                new BigDecimal("500000000"), new BigDecimal("1000"), null,
                new BigDecimal("200000"), new BigDecimal("200000"));

        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        V2SelectionScoreResult present = calculator.calculate(withUsd);
        V2SelectionScoreResult absent = calculator.calculate(missing);

        assertThat(present.dimensionScores()).containsKey(V2ScoreDimension.CAPITAL_FLOW);
        assertThat(absent.dimensionScores()).doesNotContainKey(V2ScoreDimension.CAPITAL_FLOW);
        assertThat(absent.missingDimensions()).contains(V2ScoreDimension.CAPITAL_FLOW);
        assertThat(absent.availableWeight()).isEqualByComparingTo(
                present.availableWeight().subtract(V2ScoreDimension.CAPITAL_FLOW.weight()));
        assertThat(absent.coverageRatio()).isLessThan(present.coverageRatio());
        assertThat(absent.rankingScore()).isLessThan(absent.normalizedScore());
    }

    @Test
    @DisplayName("openInterest 币数量存在但 openInterestUsd 缺失时仍退出资金流——不得用历史 OI 或币数量冒充美元")
    void coinOpenInterestWithoutUsdDoesNotScoreCapitalFlow() {
        ContractCandidate coinOnly = capitalFlowCandidate(
                new BigDecimal("500000000"), new BigDecimal("999999"), null,
                new BigDecimal("200000"), new BigDecimal("200000"));

        V2SelectionScoreResult result = new V2SelectionScoreCalculator().calculate(coinOnly);

        assertThat(result.dimensionScores()).doesNotContainKey(V2ScoreDimension.CAPITAL_FLOW);
        assertThat(coinOnly.openInterest()).isEqualByComparingTo("999999");
    }

    @Test
    @DisplayName("真实零名义 OI 仍参与评分（贡献 0），与缺失不可用区分")
    void realZeroOpenInterestUsdStillScoresWithZeroOiContribution() {
        ContractCandidate zeroOi = capitalFlowCandidate(
                new BigDecimal("500000000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("200000"), new BigDecimal("200000"));
        ContractCandidate missingOi = capitalFlowCandidate(
                new BigDecimal("500000000"), BigDecimal.ZERO, null,
                new BigDecimal("200000"), new BigDecimal("200000"));

        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        assertThat(calculator.calculate(zeroOi).dimensionScores()).containsKey(V2ScoreDimension.CAPITAL_FLOW);
        assertThat(calculator.calculate(missingOi).dimensionScores()).doesNotContainKey(V2ScoreDimension.CAPITAL_FLOW);
    }

    @Test
    @DisplayName("成交额 20 亿不能仅靠成交量把资金流维度推到满分")
    void twoBillionVolumeCannotAlonePushCapitalFlowToPerfectScore() {
        ContractCandidate hugeVolumeWeakRest = capitalFlowCandidate(
                new BigDecimal("2000000000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);

        BigDecimal score = new V2SelectionScoreCalculator().calculate(hugeVolumeWeakRest)
                .dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW);

        assertThat(score).isNotNull();
        assertThat(score).isLessThan(BigDecimal.valueOf(100));
        // 基础 35 + 成交量上限 35 + OI≈0 + 深度≈0 = 70
        assertThat(score).isEqualByComparingTo("70.000000");
    }

    @Test
    @DisplayName("各子项上下限：超过映射上界不再加分，负值非法退出")
    void capitalFlowSubComponentsAreBoundedAndRejectNegativeInputs() {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        ContractCandidate atCap = capitalFlowCandidate(
                new BigDecimal("1000000000"), BigDecimal.ONE, new BigDecimal("1000000000"),
                new BigDecimal("500000"), new BigDecimal("500000"));
        ContractCandidate aboveCap = capitalFlowCandidate(
                new BigDecimal("5000000000"), BigDecimal.ONE, new BigDecimal("5000000000"),
                new BigDecimal("5000000"), new BigDecimal("5000000"));
        ContractCandidate negativeVolume = capitalFlowCandidate(
                new BigDecimal("-1"), BigDecimal.ONE, new BigDecimal("1000"),
                new BigDecimal("100000"), new BigDecimal("100000"));

        BigDecimal capped = calculator.calculate(atCap).dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW);
        BigDecimal overflow = calculator.calculate(aboveCap).dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW);

        assertThat(overflow).isEqualByComparingTo(capped);
        assertThat(capped).isEqualByComparingTo("100");
        assertThat(calculator.calculate(negativeVolume).dimensionScores())
                .doesNotContainKey(V2ScoreDimension.CAPITAL_FLOW);
    }

    @Test
    @DisplayName("成交量子项在映射区间内单调，超大值封顶")
    void volumeContributionIsMonotonicThenCapped() {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        BigDecimal low = calculator.calculate(capitalFlowCandidate(
                new BigDecimal("100000000"), BigDecimal.ONE, new BigDecimal("1000"),
                new BigDecimal("100000"), new BigDecimal("100000")))
                .dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW);
        BigDecimal mid = calculator.calculate(capitalFlowCandidate(
                new BigDecimal("500000000"), BigDecimal.ONE, new BigDecimal("1000"),
                new BigDecimal("100000"), new BigDecimal("100000")))
                .dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW);
        BigDecimal high = calculator.calculate(capitalFlowCandidate(
                new BigDecimal("1000000000"), BigDecimal.ONE, new BigDecimal("1000"),
                new BigDecimal("100000"), new BigDecimal("100000")))
                .dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW);
        BigDecimal huge = calculator.calculate(capitalFlowCandidate(
                new BigDecimal("9000000000"), BigDecimal.ONE, new BigDecimal("1000"),
                new BigDecimal("100000"), new BigDecimal("100000")))
                .dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW);

        assertThat(low).isLessThan(mid).isLessThan(high);
        assertThat(huge).isEqualByComparingTo(high);
    }

    /**
     * @param openInterestCoin 保留币数量语义的公共字段（可与 USD 不同）
     * @param openInterestUsd  资金流专用 USD/USDT 名义价值；null = 不可用
     */
    private static ContractCandidate capitalFlowCandidate(BigDecimal volume24h,
                                                          BigDecimal openInterestCoin,
                                                          BigDecimal openInterestUsd,
                                                          BigDecimal bidDepth,
                                                          BigDecimal askDepth) {
        ContractKlineAnalysis kline = new ContractKlineAnalysis(
                "UP", "UP", "UP", "BULLISH", "STRENGTHENING", BigDecimal.valueOf(60),
                BigDecimal.valueOf(25), "HIGHER_HIGH_HIGHER_LOW", "READY", BigDecimal.valueOf(1.5),
                BigDecimal.valueOf(2), BigDecimal.ONE, true);
        ContractCandidate base = new ContractCandidate(
                MarketType.OKX_SWAP, "FLOW-USDT-SWAP", "FLOW", "USDT",
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, volume24h,
                new BigDecimal("2"), new BigDecimal("0.0001"),
                openInterestCoin, BigDecimal.ZERO, DirectionBias.BULLISH, new BigDecimal("0.02"),
                80, new ContractFactorScore(80, 80, 80, 80, 80, 80, 80),
                BigDecimal.TEN, new BigDecimal("9.7"), new BigDecimal("12"), 3, new BigDecimal("2.5"),
                new BigDecimal("2"), bidDepth, askDepth, BigDecimal.ONE,
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW, List.of(), List.of(), NOW,
                ContractSignalType.STRONG_LONG, "AUTO_TRADE_ALLOWED", "LIMIT",
                BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("0.02"), BigDecimal.valueOf(80),
                new ContractScoreBreakdown(80, 80, 80, 80, 80, 80, 80), kline,
                ContractNewsRiskAnalysis.low(),
                null, null, null, null, null, null, null, null, null);
        return base.withOpenInterestUsd(openInterestUsd);
    }
}

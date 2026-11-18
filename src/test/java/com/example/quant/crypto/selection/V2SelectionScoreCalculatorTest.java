package com.example.quant.crypto.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.dto.AthBreakoutSnapshot;
import com.example.quant.crypto.provider.dto.FundingHistorySnapshot;
import com.example.quant.crypto.provider.dto.FundingTrend;
import com.example.quant.crypto.provider.dto.OIDirection;
import com.example.quant.crypto.provider.dto.OpenInterestHistorySnapshot;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import com.example.quant.market.DirectionBias;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class V2SelectionScoreCalculatorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void completeCandidateProducesNormalizedScoreWithAllWeights() {
        V2SelectionScoreResult result = new V2SelectionScoreCalculator().calculate(completeCandidate());

        assertThat(result.normalizedScore()).isNotNull().isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
        assertThat(result.rankingScore()).isEqualByComparingTo(result.normalizedScore());
        assertThat(result.availableWeight()).isEqualByComparingTo("100");
        assertThat(result.coverageRatio()).isEqualByComparingTo("1");
        assertThat(result.missingDimensions()).isEmpty();
    }

    @Test
    void unavailableDimensionsAreExcludedAndRenormalized() {
        ContractCandidate candidate = completeCandidate().withRelativeStrength(null).withAthBreakout(null);

        V2SelectionScoreResult result = new V2SelectionScoreCalculator().calculate(candidate);

        assertThat(result.availableWeight()).isEqualByComparingTo("75");
        assertThat(result.coverageRatio()).isEqualByComparingTo("0.75");
        assertThat(result.missingDimensions())
                .contains(V2ScoreDimension.RELATIVE_STRENGTH, V2ScoreDimension.BREAKOUT);
        assertThat(result.normalizedScore()).isNotNull().isGreaterThan(BigDecimal.ZERO);
        assertThat(result.rankingScore()).isLessThan(result.normalizedScore());
    }

    @Test
    void allDimensionsUnavailableReturnsNoScoreInsteadOfZeroOrNan() {
        ContractCandidate candidate = unavailableCandidate();

        V2SelectionScoreResult result = new V2SelectionScoreCalculator().calculate(candidate);

        assertThat(result.normalizedScore()).isNull();
        assertThat(result.rankingScore()).isNull();
        assertThat(result.availableWeight()).isZero();
        assertThat(result.coverageRatio()).isZero();
        assertThat(result.missingDimensions()).containsExactlyInAnyOrder(V2ScoreDimension.values());
    }

    @Test
    void v2ScoreIsIndependentFromV1FiftyFiveCeiling() {
        V2SelectionScoreResult result = new V2SelectionScoreCalculator().calculate(completeCandidate());

        assertThat(result.normalizedScore()).isGreaterThan(BigDecimal.valueOf(55));
    }

    @Test
    void derivativesUnavailableWhenOpenInterestHistoryMissing() {
        ContractCandidate withoutOi = candidate("X-USDT-SWAP", new BigDecimal("8"), DirectionBias.BULLISH,
                "HIGHER_HIGH_HIGHER_LOW", RiskLevel.LOW,
                new BigDecimal("500000000"), new BigDecimal("2"), new BigDecimal("0.5"),
                new BigDecimal("2"), new BigDecimal("200000"), new BigDecimal("200000"),
                null, funding("X-USDT-SWAP", FundingHistorySnapshot.DataQuality.FRESH),
                relativeStrength("10"),
                new AthBreakoutSnapshot("X-USDT-SWAP", BigDecimal.TEN, new BigDecimal("9.8"),
                        new BigDecimal("-2"), "AT_ATH", DataQuality.FRESH, 2, false),
                ContractNewsRiskAnalysis.low());

        V2SelectionScoreResult result = new V2SelectionScoreCalculator().calculate(withoutOi);

        assertThat(result.dimensionScores()).doesNotContainKey(V2ScoreDimension.DERIVATIVES);
        assertThat(result.missingDimensions()).contains(V2ScoreDimension.DERIVATIVES);
        assertThat(result.availableWeight()).isLessThan(BigDecimal.valueOf(100));
    }

    @Test
    void derivativesUnavailableWhenOiChangeMissingDoesNotDefaultToZero() {
        OpenInterestHistorySnapshot oiWithoutChanges = new OpenInterestHistorySnapshot(
                "DERIVATIVE-USDT-SWAP",
                new BigDecimal("1000"),
                null,
                null,
                null,
                NOW,
                NOW,
                "test");
        ContractCandidate candidate = completeCandidate().withDerivatives(oiWithoutChanges, completeCandidate().fundingHistory());

        V2SelectionScoreResult result = new V2SelectionScoreCalculator().calculate(candidate);

        assertThat(result.dimensionScores()).doesNotContainKey(V2ScoreDimension.DERIVATIVES);
    }

    @Test
    void derivativesUsesExistingOiDirectionAndFundingTrend() {
        ContractCandidate candidate = completeCandidate();
        V2SelectionScoreResult result = new V2SelectionScoreCalculator().calculate(candidate);

        assertThat(result.dimensionScores()).containsKey(V2ScoreDimension.DERIVATIVES);
        assertThat(result.dimensionScores().get(V2ScoreDimension.DERIVATIVES)).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void lowCoverageDoesNotRankAboveAnInformationRichCandidate() {
        ContractCandidate relativeStrengthOnly = candidate("RS-ONLY-USDT-SWAP", BigDecimal.ONE,
                DirectionBias.NEUTRAL, null, RiskLevel.LOW,
                null, null, null, null, null, null,
                null, null, relativeStrength("8"), null, null);

        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        V2SelectionScoreResult result = calculator.calculate(relativeStrengthOnly);

        assertThat(result.availableWeight()).isEqualByComparingTo("15");
        assertThat(result.coverageRatio()).isEqualByComparingTo("0.15");
        assertThat(result.normalizedScore()).isEqualByComparingTo("95");
        assertThat(result.rankingScore()).isLessThan(BigDecimal.valueOf(60));
        assertThat(V2SelectionScoreCalculator.compareForRanking(completeCandidate(), relativeStrengthOnly))
                .isLessThan(0);

        ContractCandidate newsOnly = candidate("NEWS-ONLY-USDT-SWAP", BigDecimal.ONE,
                DirectionBias.NEUTRAL, null, RiskLevel.LOW,
                null, null, null, null, null, null,
                null, null, null, null, ContractNewsRiskAnalysis.low());
        V2SelectionScoreResult newsResult = calculator.calculate(newsOnly);
        assertThat(newsResult.availableWeight()).isEqualByComparingTo("5");
        assertThat(newsResult.rankingScore()).isLessThan(result.rankingScore());
    }

    @Test
    void derivativesRewardsPositiveOiOnlyForLeadingAndDoesNotRewardOiContraction() {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        BigDecimal leading20 = calculator.calculate(candidateWithDerivatives("8", "20")).dimensionScores()
                .get(V2ScoreDimension.DERIVATIVES);
        BigDecimal leading5 = calculator.calculate(candidateWithDerivatives("8", "5")).dimensionScores()
                .get(V2ScoreDimension.DERIVATIVES);
        BigDecimal followingWeakMinus20 = calculator.calculate(candidateWithDerivatives("8", "-20"))
                .dimensionScores().get(V2ScoreDimension.DERIVATIVES);
        BigDecimal followingWeakMinus5 = calculator.calculate(candidateWithDerivatives("8", "-5"))
                .dimensionScores().get(V2ScoreDimension.DERIVATIVES);
        BigDecimal followingWeakZero = calculator.calculate(candidateWithDerivatives("8", "0"))
                .dimensionScores().get(V2ScoreDimension.DERIVATIVES);

        assertThat(leading20).isGreaterThan(leading5);
        assertThat(followingWeakMinus20).isLessThan(followingWeakMinus5).isLessThan(followingWeakZero);
        assertThat(followingWeakMinus20).isLessThan(leading20);
    }

    @Test
    void fundingTrendStillAdjustsDerivativeBaseAfterOiCalibration() {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        BigDecimal stable = calculator.calculate(candidateWithFunding("8", "5", FundingTrend.STABLE, false))
                .dimensionScores().get(V2ScoreDimension.DERIVATIVES);
        BigDecimal heatingExtreme = calculator.calculate(
                candidateWithFunding("8", "5", FundingTrend.HEATING, true))
                .dimensionScores().get(V2ScoreDimension.DERIVATIVES);
        BigDecimal cooling = calculator.calculate(candidateWithFunding("8", "5", FundingTrend.COOLING, false))
                .dimensionScores().get(V2ScoreDimension.DERIVATIVES);

        assertThat(heatingExtreme).isLessThan(stable);
        assertThat(cooling).isGreaterThan(stable);
    }

    @Test
    void derivativesTreatsCapitulationAndWeakDownMagnitudeAsRiskNotBonus() {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        BigDecimal capitulation20 = calculator.calculate(candidateWithDerivatives("-8", "20"))
                .dimensionScores().get(V2ScoreDimension.DERIVATIVES);
        BigDecimal capitulation5 = calculator.calculate(candidateWithDerivatives("-8", "5"))
                .dimensionScores().get(V2ScoreDimension.DERIVATIVES);
        BigDecimal weakDown20 = calculator.calculate(candidateWithDerivatives("-8", "-20"))
                .dimensionScores().get(V2ScoreDimension.DERIVATIVES);
        BigDecimal weakDown5 = calculator.calculate(candidateWithDerivatives("-8", "-5"))
                .dimensionScores().get(V2ScoreDimension.DERIVATIVES);

        assertThat(capitulation20).isLessThan(capitulation5);
        assertThat(weakDown20).isLessThan(weakDown5);
    }

    @Test
    void marketStructureScoresStructureQualityWithoutLongBias() {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        BigDecimal bullish = calculator.calculate(structureCandidate(DirectionBias.BULLISH,
                "HIGHER_HIGH_HIGHER_LOW", RiskLevel.LOW)).dimensionScores().get(V2ScoreDimension.MARKET_STRUCTURE);
        BigDecimal bearish = calculator.calculate(structureCandidate(DirectionBias.BEARISH,
                "LOWER_HIGH_LOWER_LOW", RiskLevel.LOW)).dimensionScores().get(V2ScoreDimension.MARKET_STRUCTURE);
        BigDecimal watch = calculator.calculate(structureCandidate(DirectionBias.WATCH,
                "RANGE", RiskLevel.LOW)).dimensionScores().get(V2ScoreDimension.MARKET_STRUCTURE);
        BigDecimal neutral = calculator.calculate(structureCandidate(DirectionBias.NEUTRAL,
                "RANGE", RiskLevel.LOW)).dimensionScores().get(V2ScoreDimension.MARKET_STRUCTURE);

        assertThat(bullish).isEqualByComparingTo(bearish);
        assertThat(watch).isEqualByComparingTo(neutral);
        assertThat(watch).isLessThan(bullish);
    }

    @Test
    void marketStructurePenalizesHighRiskAndRecognizesRealTrendStructureNames() {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        BigDecimal clear = calculator.calculate(structureCandidate(DirectionBias.BULLISH,
                "HIGHER_HIGH_HIGHER_LOW", RiskLevel.LOW)).dimensionScores().get(V2ScoreDimension.MARKET_STRUCTURE);
        BigDecimal range = calculator.calculate(structureCandidate(DirectionBias.BULLISH,
                "RANGE", RiskLevel.LOW)).dimensionScores().get(V2ScoreDimension.MARKET_STRUCTURE);
        BigDecimal highRisk = calculator.calculate(structureCandidate(DirectionBias.BULLISH,
                "HIGHER_HIGH_HIGHER_LOW", RiskLevel.HIGH)).dimensionScores().get(V2ScoreDimension.MARKET_STRUCTURE);

        assertThat(clear).isGreaterThan(range);
        assertThat(highRisk).isLessThan(clear);
    }

    @Test
    void partialBreakoutUsesQualityConfidenceWithoutFixedSubtraction() {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        BigDecimal freshAtAth = calculator.calculate(breakoutCandidate("-1", "AT_ATH", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.BREAKOUT);
        BigDecimal partialAtAth = calculator.calculate(breakoutCandidate("-2", "AT_ATH", DataQuality.PARTIAL))
                .dimensionScores().get(V2ScoreDimension.BREAKOUT);
        BigDecimal staleAtAth = calculator.calculate(breakoutCandidate("-1", "AT_ATH", DataQuality.STALE))
                .dimensionScores().get(V2ScoreDimension.BREAKOUT);
        BigDecimal freshNearAth = calculator.calculate(breakoutCandidate("-5", "NEAR_ATH", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.BREAKOUT);
        BigDecimal partialNearAth = calculator.calculate(breakoutCandidate("-10", "NEAR_ATH", DataQuality.PARTIAL))
                .dimensionScores().get(V2ScoreDimension.BREAKOUT);
        BigDecimal freshMidRange = calculator.calculate(breakoutCandidate("-20", "MID_RANGE", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.BREAKOUT);
        BigDecimal freshMidRangeBoundary = calculator.calculate(
                breakoutCandidate("-40", "MID_RANGE", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.BREAKOUT);
        BigDecimal freshDeepBelow = calculator.calculate(breakoutCandidate("-50", "DEEP_BELOW", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.BREAKOUT);

        assertThat(freshAtAth).isEqualByComparingTo("90");
        assertThat(partialAtAth).isLessThan(freshAtAth).isGreaterThan(BigDecimal.ZERO);
        assertThat(partialAtAth.subtract(freshAtAth)).isNotEqualByComparingTo("-15");
        assertThat(staleAtAth).isLessThan(partialAtAth);
        assertThat(freshNearAth).isEqualByComparingTo("80");
        assertThat(partialNearAth).isLessThan(freshNearAth);
        assertThat(freshMidRange).isEqualByComparingTo("55");
        assertThat(freshMidRangeBoundary).isEqualByComparingTo("55");
        assertThat(freshDeepBelow).isEqualByComparingTo("25");
    }

    @Test
    void unavailableBreakoutDoesNotEnterScore() {
        V2SelectionScoreResult result = new V2SelectionScoreCalculator().calculate(
                completeCandidate().withAthBreakout(new AthBreakoutSnapshot("X-USDT-SWAP", BigDecimal.TEN,
                        BigDecimal.TEN, BigDecimal.ZERO, null, DataQuality.UNAVAILABLE, 0, true)));

        assertThat(result.dimensionScores()).doesNotContainKey(V2ScoreDimension.BREAKOUT);
        assertThat(result.missingDimensions()).contains(V2ScoreDimension.BREAKOUT);
    }

    @Test
    void invalidNegativeSpreadMakesVolumeQualityUnavailable() {
        ContractCandidate invalid = candidate("INVALID-SPREAD-USDT-SWAP", BigDecimal.ONE,
                DirectionBias.BULLISH, "HIGHER_HIGH_HIGHER_LOW", RiskLevel.LOW,
                new BigDecimal("500000000"), new BigDecimal("2"), new BigDecimal("0.5"),
                new BigDecimal("-1"), new BigDecimal("200000"), new BigDecimal("200000"),
                null, null, null, null, null);

        V2SelectionScoreResult result = new V2SelectionScoreCalculator().calculate(invalid);

        assertThat(result.dimensionScores()).doesNotContainKey(V2ScoreDimension.VOLUME_QUALITY);
        assertThat(result.missingDimensions()).contains(V2ScoreDimension.VOLUME_QUALITY);
    }

    @Test
    void volumeQualityCapsExtremeRatiosAtTheirConfiguredRange() {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        ContractCandidate normal = candidate("NORMAL-USDT-SWAP", BigDecimal.ONE,
                DirectionBias.BULLISH, "HIGHER_HIGH_HIGHER_LOW", RiskLevel.LOW,
                new BigDecimal("500000000"), new BigDecimal("3"), BigDecimal.ONE,
                BigDecimal.ZERO, new BigDecimal("200000"), new BigDecimal("200000"),
                null, null, null, null, null);
        ContractCandidate extreme = candidate("EXTREME-USDT-SWAP", BigDecimal.ONE,
                DirectionBias.BULLISH, "HIGHER_HIGH_HIGHER_LOW", RiskLevel.LOW,
                new BigDecimal("500000000"), new BigDecimal("30"), new BigDecimal("20"),
                BigDecimal.ZERO, new BigDecimal("200000"), new BigDecimal("200000"),
                null, null, null, null, null);

        BigDecimal normalScore = calculator.calculate(normal).dimensionScores().get(V2ScoreDimension.VOLUME_QUALITY);
        BigDecimal extremeScore = calculator.calculate(extreme).dimensionScores().get(V2ScoreDimension.VOLUME_QUALITY);

        assertThat(extremeScore).isEqualByComparingTo(normalScore);
        assertThat(extremeScore).isLessThanOrEqualTo(BigDecimal.valueOf(100));
    }

    @Test
    void relativeStrengthBoundariesRemainMonotonicAndUnavailableIsExcluded() {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        BigDecimal positiveNearZero = calculator.calculate(relativeStrengthCandidate("0.1", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.RELATIVE_STRENGTH);
        BigDecimal positiveOne = calculator.calculate(relativeStrengthCandidate("1", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.RELATIVE_STRENGTH);
        BigDecimal positiveFive = calculator.calculate(relativeStrengthCandidate("5", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.RELATIVE_STRENGTH);
        BigDecimal positiveTwenty = calculator.calculate(relativeStrengthCandidate("20", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.RELATIVE_STRENGTH);
        BigDecimal negativeNearZero = calculator.calculate(relativeStrengthCandidate("-0.1", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.RELATIVE_STRENGTH);
        BigDecimal negativeOne = calculator.calculate(relativeStrengthCandidate("-1", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.RELATIVE_STRENGTH);
        BigDecimal negativeFive = calculator.calculate(relativeStrengthCandidate("-5", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.RELATIVE_STRENGTH);
        BigDecimal negativeTwenty = calculator.calculate(relativeStrengthCandidate("-20", DataQuality.FRESH))
                .dimensionScores().get(V2ScoreDimension.RELATIVE_STRENGTH);

        assertThat(positiveNearZero).isLessThan(positiveOne).isLessThan(positiveFive).isLessThanOrEqualTo(positiveTwenty);
        assertThat(negativeTwenty).isLessThan(negativeFive).isLessThan(negativeOne).isLessThan(negativeNearZero);
        assertThat(calculator.calculate(relativeStrengthCandidate("5", DataQuality.STALE))
                .dimensionScores()).containsKey(V2ScoreDimension.RELATIVE_STRENGTH);
        assertThat(calculator.calculate(relativeStrengthCandidate("5", DataQuality.PARTIAL))
                .dimensionScores()).containsKey(V2ScoreDimension.RELATIVE_STRENGTH);
        assertThat(calculator.calculate(relativeStrengthCandidate("5", DataQuality.UNAVAILABLE))
                .dimensionScores()).doesNotContainKey(V2ScoreDimension.RELATIVE_STRENGTH);
    }

    @Test
    void stableTieBreakUsesInstIdAscending() {
        ContractCandidate z = completeCandidate("Z-USDT-SWAP");
        ContractCandidate a = completeCandidate("A-USDT-SWAP");

        assertThat(V2SelectionScoreCalculator.compareForRanking(z, a)).isGreaterThan(0);
        assertThat(V2SelectionScoreCalculator.compareForRanking(a, z)).isLessThan(0);
    }

    private static ContractCandidate completeCandidate() {
        return completeCandidate("X-USDT-SWAP");
    }

    private static ContractCandidate completeCandidate(String instId) {
        return candidate(instId, new BigDecimal("8"), DirectionBias.BULLISH,
                "HIGHER_HIGH_HIGHER_LOW", RiskLevel.LOW,
                new BigDecimal("500000000"), new BigDecimal("2"), new BigDecimal("0.5"),
                new BigDecimal("2"), new BigDecimal("200000"), new BigDecimal("200000"),
                oi(instId, "12"), funding(instId, FundingHistorySnapshot.DataQuality.FRESH),
                relativeStrength("10"),
                new AthBreakoutSnapshot(instId, BigDecimal.TEN, new BigDecimal("9.8"),
                        new BigDecimal("-2"), "AT_ATH", DataQuality.FRESH, 2, false),
                ContractNewsRiskAnalysis.low());
    }

    private static ContractCandidate candidateWithDerivatives(String priceChange, String oiChange) {
        return candidateWithFunding(priceChange, oiChange, FundingTrend.STABLE, false);
    }

    /**
     * 做多方向候选。本类的衍生品用例验证的是<b>做多侧</b>的价格×OI 象限数学
     * （LEADING / FOLLOWING_WEAK / CAPITULATION / WEAK_DOWN 四个基准分及其幅度调整）。
     *
     * <p>方向在此必须显式给出：原先由价格正负推导方向，会把"价格下跌"隐式当成"做空候选"，
     * 从而把做多侧的逆势象限和做空侧的顺势象限混为一谈。做空侧的对照行为由
     * {@code V2SelectionScoreDirectionalTest} 单独覆盖。
     */
    private static ContractCandidate candidateWithFunding(String priceChange, String oiChange,
                                                           FundingTrend trend, boolean extreme) {
        BigDecimal price = new BigDecimal(priceChange);
        return candidate("DERIVATIVE-USDT-SWAP", price,
                DirectionBias.BULLISH,
                "HIGHER_HIGH_HIGHER_LOW", RiskLevel.LOW,
                null, null, null, null, null, null,
                oi("DERIVATIVE-USDT-SWAP", oiChange),
                funding("DERIVATIVE-USDT-SWAP", FundingHistorySnapshot.DataQuality.FRESH, trend, extreme),
                null, null, null);
    }

    private static ContractCandidate structureCandidate(DirectionBias direction, String structure, RiskLevel risk) {
        return candidate("STRUCTURE-USDT-SWAP", BigDecimal.ONE, direction, structure, risk,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ContractCandidate breakoutCandidate(String distance, String level, DataQuality quality) {
        return completeCandidate().withAthBreakout(new AthBreakoutSnapshot("X-USDT-SWAP", BigDecimal.TEN,
                BigDecimal.TEN, new BigDecimal(distance), level, quality, 2, quality == DataQuality.PARTIAL));
    }

    private static ContractCandidate relativeStrengthCandidate(String value, DataQuality quality) {
        return candidate("RS-USDT-SWAP", BigDecimal.ONE, DirectionBias.NEUTRAL, null, RiskLevel.LOW,
                null, null, null, null, null, null, null, null,
                relativeStrength(value, quality), null, null);
    }

    private static RelativeStrengthSnapshot relativeStrength(String value) {
        return relativeStrength(value, DataQuality.FRESH);
    }

    private static RelativeStrengthSnapshot relativeStrength(String value, DataQuality quality) {
        BigDecimal v = new BigDecimal(value);
        return new RelativeStrengthSnapshot("X-USDT-SWAP", v, v, v, v, v, v, v, v, v, v, v, v, quality);
    }

    private static OpenInterestHistorySnapshot oi(String instId, String value) {
        BigDecimal v = new BigDecimal(value);
        return new OpenInterestHistorySnapshot(instId, BigDecimal.ONE, v, v, v, NOW, NOW, "okx");
    }

    private static FundingHistorySnapshot funding(String instId, FundingHistorySnapshot.DataQuality quality) {
        return funding(instId, quality, FundingTrend.STABLE, false);
    }

    private static FundingHistorySnapshot funding(String instId, FundingHistorySnapshot.DataQuality quality,
                                                  FundingTrend trend, boolean extreme) {
        return new FundingHistorySnapshot(instId, new BigDecimal("0.0001"), new BigDecimal("0.0001"),
                0, trend, extreme, List.of(new BigDecimal("0.0001")), quality, NOW, NOW, "okx");
    }

    private static ContractCandidate unavailableCandidate() {
        return candidate("EMPTY-USDT-SWAP", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ContractCandidate candidate(String instId, BigDecimal priceChange, DirectionBias direction,
                                               String structure, RiskLevel risk, BigDecimal volume24h,
                                               BigDecimal volumeSpikeRatio, BigDecimal volumeMcapRatio,
                                               BigDecimal spreadBps, BigDecimal bidDepthUsdt,
                                               BigDecimal askDepthUsdt, OpenInterestHistorySnapshot oi,
                                               FundingHistorySnapshot funding, RelativeStrengthSnapshot rs,
                                               AthBreakoutSnapshot ath, ContractNewsRiskAnalysis news) {
        ContractKlineAnalysis kline = structure == null ? null : new ContractKlineAnalysis(
                "UP", "UP", "UP", "BULLISH", "STRENGTHENING", BigDecimal.valueOf(60),
                BigDecimal.valueOf(25), structure, "READY", BigDecimal.valueOf(1.5),
                BigDecimal.valueOf(2), BigDecimal.ONE, true);
        ContractCandidate built = new ContractCandidate(
                null, instId, "X", "USDT", BigDecimal.TEN,
                priceChange, BigDecimal.valueOf(2), volume24h, volumeSpikeRatio, new BigDecimal("0.0001"),
                BigDecimal.ONE, BigDecimal.ONE, direction, new BigDecimal("0.02"), 90,
                new ContractFactorScore(1, 1, 1, 1, 1, 1, 1), BigDecimal.TEN, BigDecimal.ONE,
                new BigDecimal("12"), 3, new BigDecimal("2"), spreadBps, bidDepthUsdt, askDepthUsdt,
                BigDecimal.ONE, null, null, risk, List.of(), List.of(), NOW,
                ContractSignalType.STRONG_LONG, "WAIT_CONFIRM", "LIMIT", priceChange, new BigDecimal("0.02"),
                BigDecimal.ZERO, BigDecimal.valueOf(70), new ContractScoreBreakdown(90, 90, 90, 90, 90, 90, 90),
                kline, news, null, null, null, volumeMcapRatio, "FRESH", oi, funding, rs, ath);
        // 资金流需要明确的 USD 名义 OI；测试夹具用币数量 1 对应名义 1，保持旧贡献量级。
        if (volume24h != null && bidDepthUsdt != null && askDepthUsdt != null) {
            return built.withOpenInterestUsd(BigDecimal.ONE);
        }
        return built;
    }
}

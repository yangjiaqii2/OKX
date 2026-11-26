package com.example.quant.crypto.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.dto.AthBreakoutSnapshot;
import com.example.quant.crypto.provider.dto.FundingHistorySnapshot;
import com.example.quant.crypto.provider.dto.FundingTrend;
import com.example.quant.crypto.provider.dto.OpenInterestHistorySnapshot;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import com.example.quant.market.DirectionBias;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 机会排名的方向语义。
 *
 * <p>排名回答的问题是"该候选在 Java 已确认方向上的机会质量"，而不是"它涨得多不多"。
 * 这里只验证数学一致性（同一市场数据在 LONG / SHORT 下的行为符合各因子的明确定义），
 * <b>不</b>验证收益，也不假设排名更高就更赚钱。
 *
 * <p>关键反例约束：任何因子都不得用 {@code 100 - score} 简单翻转得到做空分。
 */
class V2SelectionScoreDirectionalTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final V2SelectionScoreCalculator CALC = new V2SelectionScoreCalculator();

    // ------------------------------------------------------------------
    // relativeStrength：顺势相对表现
    // ------------------------------------------------------------------

    @Test
    @DisplayName("relativeStrength：做空候选的相对弱势是机会，不是扣分项")
    void relativeStrengthTreatsUnderperformanceAsShortSideOpportunity() {
        BigDecimal shortWeak = dimension(relativeStrengthCandidate(DirectionBias.BEARISH, "-5"),
                V2ScoreDimension.RELATIVE_STRENGTH);
        BigDecimal shortStrong = dimension(relativeStrengthCandidate(DirectionBias.BEARISH, "5"),
                V2ScoreDimension.RELATIVE_STRENGTH);

        assertThat(shortWeak)
                .as("相对弱势的做空候选机会分应高于相对强势的做空候选")
                .isGreaterThan(shortStrong);
    }

    @Test
    @DisplayName("relativeStrength：LONG/SHORT 对照在镜像输入下取得相同分数")
    void relativeStrengthIsMirroredBetweenLongAndShort() {
        BigDecimal longStrong = dimension(relativeStrengthCandidate(DirectionBias.BULLISH, "5"),
                V2ScoreDimension.RELATIVE_STRENGTH);
        BigDecimal shortWeak = dimension(relativeStrengthCandidate(DirectionBias.BEARISH, "-5"),
                V2ScoreDimension.RELATIVE_STRENGTH);

        assertThat(shortWeak).isEqualByComparingTo(longStrong);
    }

    @Test
    void neutralRelativePerformanceScoresFiftyForBothDirections() {
        for (DirectionBias direction : List.of(DirectionBias.BULLISH, DirectionBias.BEARISH)) {
            assertThat(dimension(relativeStrengthCandidate(direction, "0"), V2ScoreDimension.RELATIVE_STRENGTH))
                    .isEqualByComparingTo("50");
        }
    }

    @Test
    @DisplayName("relativeStrength：无确认方向时保持既有非方向映射，不伪造方向判断")
    void relativeStrengthKeepsExistingMappingWhenDirectionIsNotConfirmed() {
        BigDecimal neutral = dimension(relativeStrengthCandidate(DirectionBias.NEUTRAL, "5"),
                V2ScoreDimension.RELATIVE_STRENGTH);
        BigDecimal bullish = dimension(relativeStrengthCandidate(DirectionBias.BULLISH, "5"),
                V2ScoreDimension.RELATIVE_STRENGTH);

        assertThat(neutral).isEqualByComparingTo(bullish);
    }

    // ------------------------------------------------------------------
    // derivatives：顺势持仓扩张 / 收缩（观察事实，不推断资金意图）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("derivatives：价格下跌伴随 OI 增加对做空候选不再被机械压低")
    void derivativesDoesNotMechanicallyPenalizeShortWithRisingOpenInterest() {
        BigDecimal shortTrendExpansion = dimension(
                derivativesCandidate(DirectionBias.BEARISH, "-8", "0.20"), V2ScoreDimension.DERIVATIVES);
        BigDecimal shortTrendContraction = dimension(
                derivativesCandidate(DirectionBias.BEARISH, "-8", "-0.20"), V2ScoreDimension.DERIVATIVES);

        assertThat(shortTrendExpansion)
                .as("顺势下跌 + 持仓扩张应优于顺势下跌 + 持仓收缩")
                .isGreaterThan(shortTrendContraction);
    }

    @Test
    @DisplayName("derivatives：LONG/SHORT 在镜像的价格与 OI 输入下取得相同分数")
    void derivativesIsMirroredBetweenLongAndShort() {
        BigDecimal longTrendExpansion = dimension(
                derivativesCandidate(DirectionBias.BULLISH, "8", "0.20"), V2ScoreDimension.DERIVATIVES);
        BigDecimal shortTrendExpansion = dimension(
                derivativesCandidate(DirectionBias.BEARISH, "-8", "0.20"), V2ScoreDimension.DERIVATIVES);

        assertThat(shortTrendExpansion).isEqualByComparingTo(longTrendExpansion);
    }

    @Test
    @DisplayName("derivatives：OI 增加不被当作所有方向的最佳状态")
    void risingOpenInterestIsNotUnconditionallyBestForEveryDirection() {
        BigDecimal shortAgainstTrend = dimension(
                derivativesCandidate(DirectionBias.BEARISH, "8", "0.20"), V2ScoreDimension.DERIVATIVES);
        BigDecimal shortWithTrend = dimension(
                derivativesCandidate(DirectionBias.BEARISH, "-8", "0.20"), V2ScoreDimension.DERIVATIVES);

        assertThat(shortAgainstTrend)
                .as("价格逆着做空方向上行时，OI 增加不构成做空机会")
                .isLessThan(shortWithTrend);
    }

    @Test
    @DisplayName("derivatives：同一行情下做空分不是 100 减去做多分")
    void derivativesShortScoreIsNotAnArithmeticInversionOfLongScore() {
        BigDecimal longScore = dimension(
                derivativesCandidate(DirectionBias.BULLISH, "8", "0.20"), V2ScoreDimension.DERIVATIVES);
        BigDecimal shortScore = dimension(
                derivativesCandidate(DirectionBias.BEARISH, "8", "0.20"), V2ScoreDimension.DERIVATIVES);

        assertThat(shortScore).isNotEqualByComparingTo(BigDecimal.valueOf(100).subtract(longScore));
    }

    @Test
    @DisplayName("derivatives：做多路径分数与方向化改造前完全一致")
    void derivativesLongPathScoresAreUnchanged() {
        assertThat(dimension(derivativesCandidate(DirectionBias.BULLISH, "8", "0"),
                V2ScoreDimension.DERIVATIVES))
                .as("价格上行 + OI 无变化 = 既有 FOLLOWING_WEAK 基准 45")
                .isEqualByComparingTo("45");
        assertThat(dimension(derivativesCandidate(DirectionBias.BULLISH, "-8", "0"),
                V2ScoreDimension.DERIVATIVES))
                .as("价格下行 + OI 无变化 = 既有 WEAK_DOWN 基准 60")
                .isEqualByComparingTo("60");
    }

    // ------------------------------------------------------------------
    // breakout / ATH：只有做多侧有可用的方向证据
    // ------------------------------------------------------------------

    @Test
    @DisplayName("breakout：接近 ATH 不会无条件抬高做空机会分")
    void nearAthDoesNotRaiseShortOpportunityScore() {
        V2SelectionScoreResult shortAtAth = CALC.calculate(
                breakoutCandidate(DirectionBias.BEARISH, "-1", "AT_ATH"));

        assertThat(shortAtAth.dimensionScores())
                .as("做空候选不得因为贴近 ATH 而获得 breakout 机会分")
                .doesNotContainKey(V2ScoreDimension.BREAKOUT);
    }

    @Test
    @DisplayName("breakout：远离 ATH 也不能自动证明做空机会好（无 ATL 数据可用）")
    void deepBelowAthDoesNotProveShortOpportunity() {
        V2SelectionScoreResult shortDeepBelow = CALC.calculate(
                breakoutCandidate(DirectionBias.BEARISH, "-60", "DEEP_BELOW"));

        assertThat(shortDeepBelow.dimensionScores()).doesNotContainKey(V2ScoreDimension.BREAKOUT);
        assertThat(shortDeepBelow.missingDimensions()).doesNotContain(V2ScoreDimension.BREAKOUT);
    }

    @Test
    @DisplayName("breakout：做空侧无需 ATH 数据即可达到完整覆盖率")
    void completeShortHasNoCoveragePenaltyForInapplicableBreakout() {
        V2SelectionScoreResult longResult = CALC.calculate(breakoutCandidate(DirectionBias.BULLISH, "-1", "AT_ATH"));
        V2SelectionScoreResult shortResult = CALC.calculate(breakoutCandidate(DirectionBias.BEARISH, "-1", "AT_ATH"));

        assertThat(longResult.availableWeight()).isEqualByComparingTo("100");
        assertThat(shortResult.availableWeight())
                .as("BREAKOUT 权重 10 必须真实从可用权重中扣除")
                .isEqualByComparingTo("90");
        assertThat(shortResult.coverageRatio()).isEqualByComparingTo("1");
        assertThat(shortResult.rankingScore()).isEqualByComparingTo(shortResult.normalizedScore());
    }

    @Test
    @DisplayName("breakout：退出评分不等于丢弃数据，athBreakout 仍作为描述信息保留")
    void excludedBreakoutRemainsAvailableAsDescriptiveData() {
        ContractCandidate shortCandidate = breakoutCandidate(DirectionBias.BEARISH, "-1", "AT_ATH");

        assertThat(shortCandidate.athBreakout()).isNotNull();
        assertThat(shortCandidate.athBreakout().breakoutLevel()).isEqualTo("AT_ATH");
    }

    // ------------------------------------------------------------------
    // marketStructure：结构必须与候选方向一致才算清晰结构
    // ------------------------------------------------------------------

    @Test
    @DisplayName("marketStructure：与候选方向一致的清晰结构在 LONG/SHORT 下得分相同")
    void alignedStructureScoresEquallyForLongAndShort() {
        BigDecimal longAligned = dimension(
                structureCandidate(DirectionBias.BULLISH, "HIGHER_HIGH_HIGHER_LOW"),
                V2ScoreDimension.MARKET_STRUCTURE);
        BigDecimal shortAligned = dimension(
                structureCandidate(DirectionBias.BEARISH, "LOWER_HIGH_LOWER_LOW"),
                V2ScoreDimension.MARKET_STRUCTURE);

        assertThat(shortAligned).isEqualByComparingTo(longAligned);
    }

    @Test
    @DisplayName("marketStructure：与候选方向相反的结构不再获得清晰结构加分")
    void counterAlignedStructureLosesTheClearStructureBonus() {
        BigDecimal shortAligned = dimension(
                structureCandidate(DirectionBias.BEARISH, "LOWER_HIGH_LOWER_LOW"),
                V2ScoreDimension.MARKET_STRUCTURE);
        BigDecimal shortCounterAligned = dimension(
                structureCandidate(DirectionBias.BEARISH, "HIGHER_HIGH_HIGHER_LOW"),
                V2ScoreDimension.MARKET_STRUCTURE);

        assertThat(shortCounterAligned).isLessThan(shortAligned);
    }

    @Test
    @DisplayName("marketStructure：方向不可判定的结构名称保持既有行为，不擅自归类")
    void ambiguousStructureNamesKeepExistingBehaviour() {
        BigDecimal bullishTrend = dimension(structureCandidate(DirectionBias.BULLISH, "TREND_CONTINUATION"),
                V2ScoreDimension.MARKET_STRUCTURE);
        BigDecimal bearishTrend = dimension(structureCandidate(DirectionBias.BEARISH, "TREND_CONTINUATION"),
                V2ScoreDimension.MARKET_STRUCTURE);

        assertThat(bearishTrend).isEqualByComparingTo(bullishTrend);
    }

    // ------------------------------------------------------------------
    // 整体：排名确定性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("排名确定性：相同输入重复计算结果完全一致")
    void rankingIsDeterministicForIdenticalInput() {
        ContractCandidate candidate = breakoutCandidate(DirectionBias.BEARISH, "-1", "AT_ATH");

        V2SelectionScoreResult first = CALC.calculate(candidate);
        V2SelectionScoreResult second = new V2SelectionScoreCalculator().calculate(candidate);

        assertThat(second.rankingScore()).isEqualByComparingTo(first.rankingScore());
        assertThat(second.availableWeight()).isEqualByComparingTo(first.availableWeight());
        assertThat(second.dimensionScores()).isEqualTo(first.dimensionScores());
    }

    @Test
    @DisplayName("排名确定性：同分候选按 instId 升序稳定 tie-break")
    void tieBreakIsStableAcrossDirections() {
        ContractCandidate a = breakoutCandidate(DirectionBias.BEARISH, "-1", "AT_ATH", "AAA-USDT-SWAP");
        ContractCandidate z = breakoutCandidate(DirectionBias.BEARISH, "-1", "AT_ATH", "ZZZ-USDT-SWAP");

        assertThat(V2SelectionScoreCalculator.compareForRanking(a, z)).isLessThan(0);
        assertThat(V2SelectionScoreCalculator.compareForRanking(z, a)).isGreaterThan(0);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static BigDecimal dimension(ContractCandidate candidate, V2ScoreDimension dimension) {
        BigDecimal score = CALC.calculate(candidate).dimensionScores().get(dimension);
        assertThat(score).as("维度 %s 必须参与评分", dimension).isNotNull();
        return score;
    }

    @Test
    void missingScoreCannotOutrankAScoredCandidateInEitherDirection() {
        var scored = breakoutCandidate(DirectionBias.BULLISH, "-2", "NEAR_ATH", "Z-USDT-SWAP");
        var missing = breakoutCandidate(DirectionBias.BEARISH, "-2", "NEAR_ATH", "A-USDT-SWAP");
        var snapshot = java.util.Map.of(scored.instId(), CALC.calculate(scored));
        assertThat(V2SelectionScoreCalculator.compareForRanking(scored, missing, snapshot)).isNegative();
        assertThat(V2SelectionScoreCalculator.compareForRanking(missing, scored, snapshot)).isPositive();
    }

    private static ContractCandidate relativeStrengthCandidate(DirectionBias direction, String rs) {
        return candidate("RS-USDT-SWAP", BigDecimal.ONE, direction, null, RiskLevel.LOW,
                null, null, null, null, null, null, null, null,
                relativeStrength(rs), null, null);
    }

    private static ContractCandidate derivativesCandidate(DirectionBias direction, String priceChange,
                                                          String oiChange) {
        return candidate("DERIV-USDT-SWAP", new BigDecimal(priceChange), direction,
                null, RiskLevel.LOW, null, null, null, null, null, null,
                oi("DERIV-USDT-SWAP", oiChange),
                funding("DERIV-USDT-SWAP", FundingTrend.STABLE, false),
                null, null, null);
    }

    private static ContractCandidate breakoutCandidate(DirectionBias direction, String distance, String level) {
        return breakoutCandidate(direction, distance, level, "BRK-USDT-SWAP");
    }

    private static ContractCandidate breakoutCandidate(DirectionBias direction, String distance, String level,
                                                       String instId) {
        String structure = direction == DirectionBias.BEARISH ? "LOWER_HIGH_LOWER_LOW" : "HIGHER_HIGH_HIGHER_LOW";
        BigDecimal priceChange = direction == DirectionBias.BEARISH ? new BigDecimal("-8") : new BigDecimal("8");
        String oiChange = "0.12";
        String rs = direction == DirectionBias.BEARISH ? "-10" : "10";
        return candidate(instId, priceChange, direction, structure, RiskLevel.LOW,
                new BigDecimal("500000000"), new BigDecimal("2"), new BigDecimal("0.5"),
                new BigDecimal("2"), new BigDecimal("200000"), new BigDecimal("200000"),
                oi(instId, oiChange), funding(instId, FundingTrend.STABLE, false),
                relativeStrength(rs),
                new AthBreakoutSnapshot(instId, BigDecimal.TEN, new BigDecimal("9.8"),
                        new BigDecimal(distance), level, DataQuality.FRESH, 2, false),
                ContractNewsRiskAnalysis.low());
    }

    private static ContractCandidate structureCandidate(DirectionBias direction, String structure) {
        return candidate("STRUCT-USDT-SWAP", BigDecimal.ONE, direction, structure, RiskLevel.LOW,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static RelativeStrengthSnapshot relativeStrength(String value) {
        BigDecimal v = new BigDecimal(value);
        return new RelativeStrengthSnapshot("RS-USDT-SWAP", v, v, v, v, v, v, v, v, v, v, v, v, DataQuality.FRESH);
    }

    private static OpenInterestHistorySnapshot oi(String instId, String value) {
        BigDecimal v = new BigDecimal(value);
        return new OpenInterestHistorySnapshot(instId, BigDecimal.ONE, v, v, v, NOW, NOW, "okx");
    }

    private static FundingHistorySnapshot funding(String instId, FundingTrend trend, boolean extreme) {
        return new FundingHistorySnapshot(instId, new BigDecimal("0.0001"), new BigDecimal("0.0001"),
                0, trend, extreme, List.of(new BigDecimal("0.0001")),
                FundingHistorySnapshot.DataQuality.FRESH, NOW, NOW, "okx");
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
        if (volume24h != null && bidDepthUsdt != null && askDepthUsdt != null) {
            return built.withOpenInterestUsd(BigDecimal.ONE);
        }
        return built;
    }
}

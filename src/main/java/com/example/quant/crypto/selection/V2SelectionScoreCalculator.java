package com.example.quant.crypto.selection;

import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.dto.AthBreakoutSnapshot;
import com.example.quant.crypto.provider.dto.FundingHistorySnapshot;
import com.example.quant.crypto.provider.dto.FundingTrend;
import com.example.quant.crypto.provider.dto.OIDirection;
import com.example.quant.crypto.provider.dto.OpenInterestHistorySnapshot;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import com.example.quant.market.DirectionBias;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class V2SelectionScoreCalculator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    // Weight-derived evidence bands: 50 = CapitalFlow + Derivatives + News;
    // 85 = the five largest dimensions. This avoids treating one arbitrary
    // dimension count as information coverage.
    private static final BigDecimal MEDIUM_COVERAGE_WEIGHT = weightOf(
            V2ScoreDimension.CAPITAL_FLOW, V2ScoreDimension.DERIVATIVES, V2ScoreDimension.NEWS);
    private static final BigDecimal HIGH_COVERAGE_WEIGHT = weightOf(
            V2ScoreDimension.CAPITAL_FLOW, V2ScoreDimension.DERIVATIVES,
            V2ScoreDimension.RELATIVE_STRENGTH, V2ScoreDimension.MARKET_STRUCTURE,
            V2ScoreDimension.VOLUME_QUALITY);

    public V2SelectionScoreResult calculate(ContractCandidate c) {
        Map<V2ScoreDimension, BigDecimal> scores = new EnumMap<>(V2ScoreDimension.class);
        for (V2ScoreDimension dimension : V2ScoreDimension.values()) {
            score(dimension, c).ifPresent(value -> scores.put(dimension, clamp(value)));
        }
        BigDecimal available = scores.keySet().stream().map(V2ScoreDimension::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weighted = scores.entrySet().stream()
                .map(e -> e.getValue().multiply(e.getKey().weight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal normalizedScore = available.signum() == 0 ? null
                : weighted.divide(available, 6, RoundingMode.HALF_UP);
        BigDecimal coverageRatio = available.divide(HUNDRED, 6, RoundingMode.HALF_UP);
        BigDecimal rankingScore = normalizedScore == null ? null
                : normalizedScore.multiply(coverageConfidence(coverageRatio))
                        .setScale(6, RoundingMode.HALF_UP);
        List<V2ScoreDimension> missing = new ArrayList<>();
        for (V2ScoreDimension d : V2ScoreDimension.values()) {
            if (!scores.containsKey(d)) missing.add(d);
        }
        return new V2SelectionScoreResult(normalizedScore, rankingScore, available, coverageRatio,
                Map.copyOf(scores), List.copyOf(missing));
    }

    public static int compareForRanking(ContractCandidate left, ContractCandidate right) {
        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        Map<String, V2SelectionScoreResult> scores = new java.util.LinkedHashMap<>();
        scores.put(left.instId(), calculator.calculate(left));
        scores.put(right.instId(), calculator.calculate(right));
        return compareForRanking(left, right, scores);
    }

    /**
     * Orders candidates from a scanner-owned score snapshot without recalculating V2.
     * Missing snapshot entries use the legacy deterministic tie-break only for compatibility callers.
     */
    public static int compareForRanking(ContractCandidate left, ContractCandidate right,
                                        Map<String, V2SelectionScoreResult> scoreSnapshot) {
        Map<String, V2SelectionScoreResult> scores = scoreSnapshot == null ? Map.of() : scoreSnapshot;
        V2SelectionScoreResult leftResult = scores.get(left.instId());
        V2SelectionScoreResult rightResult = scores.get(right.instId());
        int score = Comparator.nullsLast(BigDecimal::compareTo)
                .compare(rankingScore(rightResult), rankingScore(leftResult));
        if (score != 0) return score;
        score = Comparator.nullsLast(BigDecimal::compareTo)
                .compare(normalizedScore(rightResult), normalizedScore(leftResult));
        if (score != 0) return score;
        score = Comparator.nullsLast(BigDecimal::compareTo)
                .compare(availableWeight(rightResult), availableWeight(leftResult));
        if (score != 0) return score;
        score = Comparator.nullsLast(BigDecimal::compareTo).compare(right.finalRankScore(), left.finalRankScore());
        if (score != 0) return score;
        score = Integer.compare(right.score(), left.score());
        if (score != 0) return score;
        score = Comparator.nullsLast(BigDecimal::compareTo).compare(right.volume24h(), left.volume24h());
        if (score != 0) return score;
        return String.valueOf(left.instId()).compareTo(String.valueOf(right.instId()));
    }

    private static BigDecimal rankingScore(V2SelectionScoreResult result) {
        return result == null ? null : result.rankingScore();
    }

    private static BigDecimal normalizedScore(V2SelectionScoreResult result) {
        return result == null ? null : result.normalizedScore();
    }

    private static BigDecimal availableWeight(V2SelectionScoreResult result) {
        return result == null ? null : result.availableWeight();
    }

    private java.util.Optional<BigDecimal> score(V2ScoreDimension d, ContractCandidate c) {
        return switch (d) {
            case CAPITAL_FLOW -> capitalFlow(c);
            case DERIVATIVES -> derivatives(c);
            case RELATIVE_STRENGTH -> relativeStrength(c);
            case MARKET_STRUCTURE -> marketStructure(c);
            case VOLUME_QUALITY -> volumeQuality(c);
            case BREAKOUT -> breakout(c);
            case NEWS -> news(c.newsAnalysis());
        };
    }

    private java.util.Optional<BigDecimal> capitalFlow(ContractCandidate c) {
        if (c.volume24h() == null || c.volume24h().signum() < 0
                || c.openInterestUsd() == null || c.openInterestUsd().signum() < 0
                || c.bidDepthUsdt() == null || c.bidDepthUsdt().signum() < 0
                || c.askDepthUsdt() == null || c.askDepthUsdt().signum() < 0) {
            return java.util.Optional.empty();
        }
        BigDecimal volumePart = boundedLinear(c.volume24h(),
                BigDecimal.valueOf(50_000_000), BigDecimal.valueOf(1_000_000_000), 35);
        BigDecimal oiPart = boundedLinear(c.openInterestUsd(),
                BigDecimal.ZERO, BigDecimal.valueOf(1_000_000_000), 15);
        BigDecimal depthPart = boundedLinear(c.bidDepthUsdt().min(c.askDepthUsdt()),
                BigDecimal.ZERO, BigDecimal.valueOf(500_000), 15);
        return java.util.Optional.of(clamp(BigDecimal.valueOf(35).add(volumePart).add(oiPart).add(depthPart)));
    }

    /**
     * 衍生品维度：以候选自身方向为参照，判断"价格朝该方向移动时持仓是在扩张还是收缩"。
     *
     * <p>这是一个<b>观察层</b>陈述，不是对资金意图的推断：OI 上升只说明未平仓合约增加，
     * 不说明净资金看多或看空，也无法识别谁是主动方。因此这里只区分
     * "顺方向移动 + 持仓扩张（不是单纯平仓驱动）" 与 "顺方向移动 + 持仓收缩（平仓驱动，跟进力度弱）"。
     *
     * <p>做多路径的四个分值与方向化改造前完全一致；做空路径是同一套规则的镜像，
     * 而不是对做多分数做 {@code 100 - score} 翻转。
     */
    private java.util.Optional<BigDecimal> derivatives(ContractCandidate c) {
        OpenInterestHistorySnapshot oi = c.openInterestHistory();
        FundingHistorySnapshot funding = c.fundingHistory();
        if (oi == null || funding == null || oi.oiChange24h() == null || funding.dataQuality() == FundingHistorySnapshot.DataQuality.UNAVAILABLE) {
            return java.util.Optional.empty();
        }
        DirectionalOiRegime regime = classifyRegime(c.trendDirection(), c.changePercent24h(), oi.oiChange24h());
        BigDecimal base = switch (regime) {
            case TREND_WITH_OI_EXPANSION -> BigDecimal.valueOf(85);
            case COUNTER_MOVE_WITH_OI_EXPANSION -> BigDecimal.valueOf(55);
            case TREND_WITH_OI_CONTRACTION -> BigDecimal.valueOf(45);
            case COUNTER_MOVE_WITH_OI_CONTRACTION -> BigDecimal.valueOf(60);
        };
        // Funding 调整保持方向中性：极端升温表达的是拥挤度 / 持仓成本风险，两个方向都要付。
        if (funding.trend() == FundingTrend.HEATING && funding.extreme()) base = base.subtract(BigDecimal.valueOf(20));
        if (funding.trend() == FundingTrend.COOLING) base = base.add(BigDecimal.valueOf(5));
        return java.util.Optional.of(base.add(oiMagnitudeAdjustment(regime, oi.oiChange24h())));
    }

    /**
     * 相对强弱：表达"顺着候选方向的相对表现"。
     *
     * <p>做多看相对强势，做空看相对弱势——同一条单调映射作用在<b>按方向取号</b>的 RS 上，
     * 而不是把做多分数取补数。方向未确认（NEUTRAL / WATCH / null）时没有可评估的方向机会，
     * 保持既有非方向映射不变，不擅自编造方向判断。
     */
    private java.util.Optional<BigDecimal> relativeStrength(ContractCandidate c) {
        RelativeStrengthSnapshot rs = c.relativeStrength();
        if (rs == null || rs.dataQuality() == DataQuality.UNAVAILABLE || rs.rs1h() == null || rs.rs4h() == null || rs.rs24h() == null) {
            return java.util.Optional.empty();
        }
        BigDecimal sum = rs.rs1h().add(rs.rs4h()).add(rs.rs24h());
        BigDecimal directional = c.trendDirection() == DirectionBias.BEARISH ? sum.negate() : sum;
        return java.util.Optional.of(BigDecimal.valueOf(50)
                .add(linear(directional, BigDecimal.valueOf(-30), BigDecimal.valueOf(30), 50)));
    }

    private java.util.Optional<BigDecimal> marketStructure(ContractCandidate c) {
        ContractKlineAnalysis k = c.klineAnalysis();
        if (k == null || c.trendDirection() == null) return java.util.Optional.empty();
        BigDecimal score = c.trendDirection() == DirectionBias.BULLISH
                || c.trendDirection() == DirectionBias.BEARISH ? BigDecimal.valueOf(60) : BigDecimal.valueOf(45);
        if (structureSupports(k.structure(), c.trendDirection())) score = score.add(BigDecimal.valueOf(30));
        if (c.marketRiskLevel() != null) {
            score = score.subtract(switch (c.marketRiskLevel()) {
                case HIGH -> BigDecimal.valueOf(25);
                case BLOCKED -> BigDecimal.valueOf(40);
                case LOW, MEDIUM -> BigDecimal.ZERO;
            });
        }
        return java.util.Optional.of(score);
    }

    private java.util.Optional<BigDecimal> volumeQuality(ContractCandidate c) {
        if (c.volumeSpikeRatio() == null || c.volumeMcapRatio() == null || c.spreadBps() == null
                || c.volumeSpikeRatio().signum() < 0 || c.volumeMcapRatio().signum() < 0
                || c.spreadBps().signum() < 0) return java.util.Optional.empty();
        return java.util.Optional.of(BigDecimal.valueOf(25)
                .add(boundedLinear(c.volumeSpikeRatio(), BigDecimal.ONE, BigDecimal.valueOf(3), 35))
                .add(boundedLinear(c.volumeMcapRatio(), BigDecimal.ZERO, BigDecimal.ONE, 25))
                .add(boundedLinear(c.spreadBps(), BigDecimal.valueOf(20), BigDecimal.ZERO, 15)));
    }

    /**
     * 突破维度：只对做多方向参与评分。
     *
     * <p>Provider 只提供 ATH（历史高点水位），没有 ATL。对做空候选而言：
     * 贴近 ATH 不构成做空机会（它同样可能继续突破），远离 ATH 也<b>不等于</b>接近历史低点，
     * 无法推出"下方空间大"。现有数据构造不出可靠的做空侧方向评分，
     * 因此该维度对做空候选<b>退出评分</b>（{@code athBreakout} 仍作为描述信息保留在候选上），
     * 可用权重与覆盖率按真实情况结算，不伪造中性分。
     */
    private java.util.Optional<BigDecimal> breakout(ContractCandidate c) {
        if (c.trendDirection() == DirectionBias.BEARISH) {
            return java.util.Optional.empty();
        }
        AthBreakoutSnapshot ath = c.athBreakout();
        if (ath == null || ath.dataQuality() == null || ath.dataQuality() == DataQuality.UNAVAILABLE
                || ath.dataQuality() == DataQuality.TIMEOUT || ath.breakoutLevel() == null) {
            return java.util.Optional.empty();
        }
        BigDecimal rawScore = switch (ath.breakoutLevel()) {
            case "AT_ATH" -> BigDecimal.valueOf(90);
            case "NEAR_ATH" -> BigDecimal.valueOf(80);
            case "MID_RANGE" -> BigDecimal.valueOf(55);
            case "DEEP_BELOW" -> BigDecimal.valueOf(25);
            default -> null;
        };
        if (rawScore == null) return java.util.Optional.empty();
        return java.util.Optional.of(rawScore.multiply(breakoutQualityConfidence(ath.dataQuality(), rawScore)));
    }

    private java.util.Optional<BigDecimal> news(ContractNewsRiskAnalysis news) {
        if (news == null || news.newsRiskLevel() == null || "UNKNOWN".equals(news.newsRiskLevel())) return java.util.Optional.empty();
        return java.util.Optional.of(BigDecimal.valueOf(news.newsRiskScore()));
    }

    private static BigDecimal linear(BigDecimal value, BigDecimal low, BigDecimal high, int range) {
        if (value == null || high.compareTo(low) == 0) return BigDecimal.ZERO;
        return value.subtract(low).divide(high.subtract(low), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(range));
    }

    private static BigDecimal boundedLinear(BigDecimal value, BigDecimal low, BigDecimal high, int range) {
        BigDecimal bounded = value.max(low.min(high)).min(low.max(high));
        return linear(bounded, low, high, range);
    }

    private static BigDecimal oiMagnitudeAdjustment(DirectionalOiRegime regime, BigDecimal oiChange) {
        if (oiChange == null || oiChange.signum() == 0) return BigDecimal.ZERO;
        BigDecimal magnitude = boundedLinear(oiChange.abs(), BigDecimal.ZERO, BigDecimal.valueOf(20), 10);
        return switch (regime) {
            case TREND_WITH_OI_EXPANSION -> magnitude;
            case TREND_WITH_OI_CONTRACTION, COUNTER_MOVE_WITH_OI_EXPANSION,
                 COUNTER_MOVE_WITH_OI_CONTRACTION -> magnitude.negate();
        };
    }

    /**
     * 以候选方向为参照，把 {@link OIDirection} 的价格×OI 四象限重述为方向相对的四种状态。
     *
     * <p>边界约定与 {@link OIDirection#classify} 保持一致：{@code oi == 0} 视为收缩侧；
     * 做多时 {@code price == 0} 视为顺方向（沿用既有约定），做空时 {@code price == 0} 不视为顺方向。
     */
    private static DirectionalOiRegime classifyRegime(DirectionBias direction, BigDecimal priceChange24h,
                                                      BigDecimal oiChange24h) {
        boolean trendAligned = direction == DirectionBias.BEARISH
                ? priceChange24h != null && priceChange24h.signum() < 0
                : priceChange24h == null || priceChange24h.signum() >= 0;
        boolean oiExpanding = oiChange24h != null && oiChange24h.signum() > 0;
        if (trendAligned) {
            return oiExpanding ? DirectionalOiRegime.TREND_WITH_OI_EXPANSION
                    : DirectionalOiRegime.TREND_WITH_OI_CONTRACTION;
        }
        return oiExpanding ? DirectionalOiRegime.COUNTER_MOVE_WITH_OI_EXPANSION
                : DirectionalOiRegime.COUNTER_MOVE_WITH_OI_CONTRACTION;
    }

    /** 结构必须支持候选方向才算"清晰结构"；方向不可判定的结构名称保持既有行为。 */
    private static boolean structureSupports(String structure, DirectionBias direction) {
        if (!clearStructure(structure)) return false;
        String normalized = structure.toUpperCase(Locale.ROOT);
        if (normalized.equals("HIGHER_HIGH_HIGHER_LOW")) return direction != DirectionBias.BEARISH;
        if (normalized.equals("LOWER_HIGH_LOWER_LOW")) return direction != DirectionBias.BULLISH;
        return true;
    }

    private static boolean clearStructure(String structure) {
        if (structure == null) return false;
        String normalized = structure.toUpperCase(Locale.ROOT);
        return normalized.equals("HIGHER_HIGH_HIGHER_LOW")
                || normalized.equals("LOWER_HIGH_LOWER_LOW")
                || normalized.contains("TREND")
                || normalized.contains("BREAK");
    }

    /** 相对候选方向的价格 × 持仓量状态。命名刻意保持在观察层，不宣称参与者意图。 */
    private enum DirectionalOiRegime {
        /** 价格顺候选方向移动，且持仓扩张。 */
        TREND_WITH_OI_EXPANSION,
        /** 价格顺候选方向移动，但持仓收缩（平仓驱动）。 */
        TREND_WITH_OI_CONTRACTION,
        /** 价格逆候选方向移动，且持仓扩张。 */
        COUNTER_MOVE_WITH_OI_EXPANSION,
        /** 价格逆候选方向移动，且持仓收缩。 */
        COUNTER_MOVE_WITH_OI_CONTRACTION
    }

    private static BigDecimal coverageConfidence(BigDecimal coverageRatio) {
        if (coverageRatio.signum() == 0) return BigDecimal.ZERO;
        BigDecimal mediumCoverage = MEDIUM_COVERAGE_WEIGHT.divide(HUNDRED, 6, RoundingMode.HALF_UP);
        BigDecimal highCoverage = HIGH_COVERAGE_WEIGHT.divide(HUNDRED, 6, RoundingMode.HALF_UP);
        if (coverageRatio.compareTo(highCoverage) >= 0) {
            return new BigDecimal("0.98").add(
                    coverageRatio.subtract(highCoverage)
                            .divide(BigDecimal.ONE.subtract(highCoverage), 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("0.02")));
        }
        if (coverageRatio.compareTo(mediumCoverage) >= 0) {
            return new BigDecimal("0.85").add(
                    coverageRatio.subtract(mediumCoverage)
                            .divide(highCoverage.subtract(mediumCoverage), 6, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("0.13")));
        }
        return new BigDecimal("0.50").add(coverageRatio.multiply(new BigDecimal("0.70")));
    }

    private static BigDecimal weightOf(V2ScoreDimension... dimensions) {
        BigDecimal total = BigDecimal.ZERO;
        for (V2ScoreDimension dimension : dimensions) {
            total = total.add(dimension.weight());
        }
        return total;
    }

    private static BigDecimal breakoutQualityConfidence(DataQuality quality, BigDecimal rawScore) {
        BigDecimal remaining = HUNDRED.subtract(rawScore).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        return switch (quality) {
            case FRESH, AVAILABLE -> BigDecimal.ONE;
            case PARTIAL -> new BigDecimal("0.85").add(remaining.multiply(new BigDecimal("0.15")));
            case STALE -> new BigDecimal("0.75").add(remaining.multiply(new BigDecimal("0.10")));
            case UNAVAILABLE, TIMEOUT -> BigDecimal.ZERO;
        };
    }

    private static BigDecimal clamp(BigDecimal value) {
        if (value == null || value.signum() < 0) return BigDecimal.ZERO;
        return value.compareTo(HUNDRED) > 0 ? HUNDRED : value;
    }
}

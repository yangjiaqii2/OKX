package com.example.quant.agent.execution;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.market.DirectionBias;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PositionQualityPolicy {
    private final AgentProperties agentProperties;

    public PositionQualityPolicy(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    public PositionQualityDecision evaluate(List<PositionSummary> positions, int inFlightCount,
                                            ContractCandidate candidate) {
        if (!agentProperties.positionQuality().enabled()) {
            return PositionQualityDecision.allowed(maxOpenPositions(), 0, "position_quality_disabled");
        }
        int openOrPending = (positions == null ? 0 : positions.size()) + inFlightCount;
        String regime = marketRegime(candidate);
        int maxByRegime = agentProperties.positionQuality().maxPositionsForRegime(regime);
        int allowedMax = Math.min(maxOpenPositions(), maxByRegime);
        if (openOrPending >= allowedMax) {
            return PositionQualityDecision.rejected(allowedMax, minScore(openOrPending, regime),
                    "position_quality_capacity_full_regime_" + regime + "_max_" + allowedMax);
        }
        int minScore = minScore(openOrPending, regime);
        int minFinalRankScore = minFinalRankScore(openOrPending, regime);
        BigDecimal finalRank = candidate.finalRankScore() == null ? BigDecimal.ZERO : candidate.finalRankScore();
        if (candidate.score() < minScore) {
            return PositionQualityDecision.rejected(allowedMax, minScore,
                    "position_quality_score_below_" + minScore);
        }
        if (finalRank.compareTo(BigDecimal.valueOf(minFinalRankScore)) < 0) {
            return PositionQualityDecision.rejected(allowedMax, minScore,
                    "position_quality_final_rank_below_" + minFinalRankScore);
        }
        return PositionQualityDecision.allowed(allowedMax, minScore,
                "position_quality_passed_regime_" + regime + "_min_" + minScore);
    }

    public int allowedMaxPositionsFor(ContractCandidate candidate) {
        if (!agentProperties.positionQuality().enabled()) {
            return maxOpenPositions();
        }
        return Math.min(maxOpenPositions(), agentProperties.positionQuality().maxPositionsForRegime(marketRegime(candidate)));
    }

    public String marketRegimeFor(ContractCandidate candidate) {
        if (candidate == null) {
            return "UNKNOWN";
        }
        return marketRegime(candidate);
    }

    public int minScoreFor(int openOrPending, ContractCandidate candidate) {
        return minScore(openOrPending, marketRegimeFor(candidate));
    }

    public int minFinalRankScoreFor(int openOrPending, ContractCandidate candidate) {
        return minFinalRankScore(openOrPending, marketRegimeFor(candidate));
    }

    private int minScore(int openOrPending, String regime) {
        int min = agentProperties.positionQuality().minScoreForOpenPositions(openOrPending);
        if ("HIGH_VOLATILITY".equals(regime)) {
            min = Math.max(min, 88);
        }
        return min;
    }

    private int minFinalRankScore(int openOrPending, String regime) {
        int min = agentProperties.positionQuality().minFinalRankScoreForOpenPositions(openOrPending);
        if ("HIGH_VOLATILITY".equals(regime)) {
            min = Math.max(min, 88);
        }
        return min;
    }

    private int maxOpenPositions() {
        return Math.max(1, agentProperties.autoTrade().maxOpenPositions());
    }

    private static String marketRegime(ContractCandidate candidate) {
        if (candidate.marketRiskLevel() == RiskLevel.BLOCKED) {
            return "RISK_OFF";
        }
        if (candidate.marketRiskLevel() == RiskLevel.HIGH) {
            return "HIGH_VOLATILITY";
        }
        if (candidate.btcTrend() == DirectionBias.BULLISH && candidate.ethTrend() == DirectionBias.BULLISH) {
            return "BULLISH";
        }
        if (candidate.btcTrend() == DirectionBias.BEARISH && candidate.ethTrend() == DirectionBias.BEARISH) {
            return "BEARISH";
        }
        return "CHOPPY";
    }

    public record PositionQualityDecision(boolean allowed, int allowedMaxPositions, int minScore, String reason) {
        static PositionQualityDecision allowed(int allowedMaxPositions, int minScore, String reason) {
            return new PositionQualityDecision(true, allowedMaxPositions, minScore, reason);
        }

        static PositionQualityDecision rejected(int allowedMaxPositions, int minScore, String reason) {
            return new PositionQualityDecision(false, allowedMaxPositions, minScore, reason);
        }
    }
}

package com.example.quant.agent.market;

import com.example.quant.market.DirectionBias;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MarketEnvironment(
        DirectionBias btcTrend,
        DirectionBias ethTrend,
        RiskLevel riskLevel,
        BigDecimal btcReturn20m,
        BigDecimal btcReturn1h,
        BigDecimal ethReturn20m,
        BigDecimal ethReturn1h,
        List<String> reasonList,
        Instant createdAt
) {
    public static MarketEnvironment unavailable(String reason) {
        return new MarketEnvironment(
                DirectionBias.NEUTRAL,
                DirectionBias.NEUTRAL,
                RiskLevel.HIGH,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(reason),
                Instant.now()
        );
    }

    public boolean conflictsWith(DirectionBias candidateDirection) {
        if (candidateDirection == DirectionBias.BULLISH) {
            return btcTrend == DirectionBias.BEARISH && ethTrend == DirectionBias.BEARISH;
        }
        if (candidateDirection == DirectionBias.BEARISH) {
            return btcTrend == DirectionBias.BULLISH && ethTrend == DirectionBias.BULLISH;
        }
        return false;
    }

    public boolean partiallyConflictsWith(DirectionBias candidateDirection) {
        if (candidateDirection == DirectionBias.BULLISH) {
            return btcTrend == DirectionBias.BEARISH || ethTrend == DirectionBias.BEARISH;
        }
        if (candidateDirection == DirectionBias.BEARISH) {
            return btcTrend == DirectionBias.BULLISH || ethTrend == DirectionBias.BULLISH;
        }
        return false;
    }

    public int scoreFor(DirectionBias candidateDirection) {
        int score = switch (riskLevel) {
            case LOW -> 8;
            case MEDIUM -> 6;
            case HIGH -> 4;
            case BLOCKED -> 0;
        };
        if (conflictsWith(candidateDirection)) {
            score -= 4;
        } else if (partiallyConflictsWith(candidateDirection)) {
            score -= 2;
        } else if (candidateDirection == btcTrend || candidateDirection == ethTrend) {
            score += 2;
        }
        return Math.max(0, Math.min(10, score));
    }
}

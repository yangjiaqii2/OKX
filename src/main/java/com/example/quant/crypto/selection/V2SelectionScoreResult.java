package com.example.quant.crypto.selection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record V2SelectionScoreResult(
        BigDecimal normalizedScore,
        BigDecimal rankingScore,
        BigDecimal availableWeight,
        BigDecimal coverageRatio,
        Map<V2ScoreDimension, BigDecimal> dimensionScores,
        List<V2ScoreDimension> missingDimensions
) {
    /**
     * Backward-compatible alias for callers that used the Wave 4 name. The
     * value is the score over available evidence, not the coverage-adjusted
     * ranking value.
     */
    public BigDecimal finalScore() {
        return normalizedScore;
    }
}

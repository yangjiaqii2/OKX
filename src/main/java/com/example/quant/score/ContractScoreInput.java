package com.example.quant.score;

import java.math.BigDecimal;

public record ContractScoreInput(
        int trendScore,
        BigDecimal volumeSpikeRatio,
        BigDecimal fundingRate,
        BigDecimal volatility,
        int positiveNewsCount,
        int negativeNewsCount
) {
}

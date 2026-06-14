package com.example.quant.score;

import java.math.BigDecimal;

public record StockScoreInput(
        boolean st,
        boolean suspended,
        boolean aboveMa5,
        boolean aboveMa20,
        BigDecimal changePercent,
        BigDecimal volumeRatio,
        boolean limitUp,
        int sectorHeatScore,
        int moneyFlowScore,
        int negativeNewsCount
) {
}

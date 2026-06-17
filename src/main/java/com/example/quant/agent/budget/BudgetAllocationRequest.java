package com.example.quant.agent.budget;

import java.math.BigDecimal;

public record BudgetAllocationRequest(
        BigDecimal totalBudgetUsdt,
        BigDecimal usedBudgetUsdt,
        BigDecimal inFlightReservedBudgetUsdt,
        int slotIndex,
        BigDecimal score,
        BigDecimal riskBasedMaxMarginUsdt,
        BigDecimal stopLossPct,
        String newsRiskLevel,
        String marketRegime,
        BigDecimal fundingRate,
        BigDecimal riskRewardRatio
) {
}

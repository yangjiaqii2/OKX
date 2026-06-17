package com.example.quant.agent.budget;

import java.math.BigDecimal;
import java.util.List;

public record BudgetStatusSnapshot(
        BigDecimal totalBudgetUsdt,
        BigDecimal targetUsedBudgetUsdt,
        BigDecimal minTargetUsedBudgetUsdt,
        BigDecimal usedBudgetUsdt,
        BigDecimal inFlightReservedBudgetUsdt,
        BigDecimal remainingBudgetUsdt,
        BigDecimal budgetUtilizationPct,
        String status,
        List<String> underUtilizedReasons
) {
}

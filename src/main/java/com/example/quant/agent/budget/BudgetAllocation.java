package com.example.quant.agent.budget;

import java.math.BigDecimal;
import java.util.List;

public record BudgetAllocation(
        BigDecimal totalBudgetUsdt,
        BigDecimal targetUsedBudgetUsdt,
        BigDecimal minTargetUsedBudgetUsdt,
        BigDecimal maxUtilizationUsdt,
        BigDecimal usedBudgetBefore,
        BigDecimal inFlightReservedBefore,
        BigDecimal remainingBudgetBefore,
        int slotIndex,
        BigDecimal slotWeight,
        BigDecimal slotBudgetUsdt,
        BigDecimal scoreFactor,
        BigDecimal qualityAdjustedBudgetUsdt,
        BigDecimal riskBasedMaxMarginUsdt,
        BigDecimal maxSinglePositionBudgetUsdt,
        BigDecimal redistributedExtraUsdt,
        BigDecimal finalOrderMarginUsdt,
        BigDecimal budgetUtilizationAfter,
        String allocationMode,
        String status,
        List<String> underUtilizedReasons,
        String reason
) {
}

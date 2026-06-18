package com.example.quant.agent.execution;

import java.math.BigDecimal;
import java.time.Instant;

public record AutoTradeProfitSummary(
        BigDecimal totalBudgetUsdt,
        BigDecimal submittedMarginUsdt,
        BigDecimal todaySubmittedMarginUsdt,
        BigDecimal realizedPnlUsdt,
        BigDecimal todayRealizedPnlUsdt,
        BigDecimal unrealizedPnlUsdt,
        BigDecimal totalNetPnlUsdt,
        BigDecimal budgetRoiPct,
        BigDecimal usedMarginRoiPct,
        int openPositionCount,
        int executedOrderCount,
        Instant lastSyncAt,
        String dataQuality,
        String message
) {
}

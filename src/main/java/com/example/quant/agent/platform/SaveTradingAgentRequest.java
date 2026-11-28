package com.example.quant.agent.platform;

import java.math.BigDecimal;

public record SaveTradingAgentRequest(
        Long id,
        String name,
        String templateCode,
        String runMode,
        Long llmProfileId,
        BigDecimal allocatedBudgetUsdt,
        Integer maxOpenPositions,
        Integer maxLeverage,
        Integer minTotalScore,
        Integer minFinalRankScore,
        BigDecimal maxSingleLossPct,
        BigDecimal maxDailyLossPct,
        String directionPreference,
        String symbolWhitelist,
        String symbolBlacklist,
        String systemPrompt
) {
}

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
        String systemPrompt,
        Boolean toolCallingEnabled
) {
    public SaveTradingAgentRequest(
            Long id, String name, String templateCode, String runMode, Long llmProfileId,
            BigDecimal allocatedBudgetUsdt, Integer maxOpenPositions, Integer maxLeverage,
            Integer minTotalScore, Integer minFinalRankScore, BigDecimal maxSingleLossPct,
            BigDecimal maxDailyLossPct, String directionPreference, String symbolWhitelist,
            String symbolBlacklist, String systemPrompt
    ) {
        this(id, name, templateCode, runMode, llmProfileId, allocatedBudgetUsdt, maxOpenPositions,
                maxLeverage, minTotalScore, minFinalRankScore, maxSingleLossPct, maxDailyLossPct,
                directionPreference, symbolWhitelist, symbolBlacklist, systemPrompt, true);
    }
}

package com.example.quant.agent.platform;

import java.math.BigDecimal;
import java.time.Instant;

public record TradingAgentView(
        Long id,
        String name,
        String templateCode,
        String status,
        String runMode,
        Long llmProfileId,
        BigDecimal allocatedBudgetUsdt,
        int maxOpenPositions,
        int maxLeverage,
        int minTotalScore,
        int minFinalRankScore,
        BigDecimal maxSingleLossPct,
        BigDecimal maxDailyLossPct,
        String directionPreference,
        String symbolWhitelist,
        String symbolBlacklist,
        String systemPrompt,
        int versionNo,
        String lastDecision,
        String lastSummary,
        String lastErrorCode,
        Instant lastRunAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static TradingAgentView from(TradingAgentEntity entity) {
        return new TradingAgentView(
                entity.getId(),
                entity.getName(),
                entity.getTemplateCode(),
                entity.getStatus().name(),
                entity.getRunMode().name(),
                entity.getLlmProfileId(),
                entity.getAllocatedBudgetUsdt(),
                entity.getMaxOpenPositions(),
                entity.getMaxLeverage(),
                entity.getMinTotalScore(),
                entity.getMinFinalRankScore(),
                entity.getMaxSingleLossPct(),
                entity.getMaxDailyLossPct(),
                entity.getDirectionPreference().name(),
                entity.getSymbolWhitelist(),
                entity.getSymbolBlacklist(),
                entity.getSystemPrompt(),
                entity.getVersionNo(),
                entity.getLastDecision(),
                entity.getLastSummary(),
                entity.getLastErrorCode(),
                entity.getLastRunAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

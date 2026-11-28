package com.example.quant.agent.platform;

import java.math.BigDecimal;

public record AgentTemplateView(
        String code,
        String title,
        String description,
        BigDecimal defaultBudgetUsdt,
        int defaultMaxOpenPositions,
        int defaultMaxLeverage,
        int defaultMinTotalScore,
        int defaultMinFinalRankScore,
        BigDecimal defaultMaxSingleLossPct,
        BigDecimal defaultMaxDailyLossPct,
        String defaultDirection,
        String defaultSystemPrompt
) {
    public static AgentTemplateView from(TradingAgentTemplate template) {
        return new AgentTemplateView(
                template.name(),
                template.title(),
                template.description(),
                template.defaultBudgetUsdt(),
                template.defaultMaxOpenPositions(),
                template.defaultMaxLeverage(),
                template.defaultMinTotalScore(),
                template.defaultMinFinalRankScore(),
                template.defaultMaxSingleLossPct(),
                template.defaultMaxDailyLossPct(),
                template.defaultDirection().name(),
                template.defaultSystemPrompt()
        );
    }
}

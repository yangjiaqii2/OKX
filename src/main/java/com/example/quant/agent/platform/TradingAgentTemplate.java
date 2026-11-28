package com.example.quant.agent.platform;

import java.math.BigDecimal;

public enum TradingAgentTemplate {
    TREND_BALANCED(
            "稳健趋势 Agent",
            "顺应 20M 主趋势与 5M 节奏，平衡胜率与盈亏比，适合多数行情。",
            new BigDecimal("100"),
            2,
            5,
            65,
            65,
            new BigDecimal("1.2"),
            new BigDecimal("3.5"),
            TradingAgentDirection.BOTH,
            "你是一名纪律严明的 OKX 永续合约趋势交易 Agent。只在 20M 趋势清晰、量能配合且盘口流动性健康时择优入场；宁可观望也不追高。"
    ),
    BREAKOUT_AGGRESSIVE(
            "强势突破 Agent",
            "捕捉高动量放量突破机会，允许更高弹性但仍严格执行止损门禁。",
            new BigDecimal("150"),
            3,
            8,
            60,
            62,
            new BigDecimal("1.5"),
            new BigDecimal("4.5"),
            TradingAgentDirection.BOTH,
            "你是一名动量突破型交易 Agent。优先选择放量突破、相对强弱领先 BTC/ETH 的合约；若出现长上影线或盘口滑点偏高则果断跳过。"
    ),
    PULLBACK_CONSERVATIVE(
            "回踩低吸 Agent",
            "只等待趋势确认后的健康回踩，严控杠杆与单笔止损。",
            new BigDecimal("80"),
            1,
            3,
            72,
            70,
            new BigDecimal("1.0"),
            new BigDecimal("2.5"),
            TradingAgentDirection.BOTH,
            "你是一名极度保守的回踩交易 Agent。仅在高评分、回踩关键支撑/均线企稳且风险回报比优秀时出手，任何犹豫信号一律观望。"
    ),
    CUSTOM(
            "自定义策略 Agent",
            "完全按你的风控边界与自定义 Prompt 独立运行。",
            new BigDecimal("100"),
            2,
            5,
            60,
            60,
            new BigDecimal("1.5"),
            new BigDecimal("4.0"),
            TradingAgentDirection.BOTH,
            "你是一名理性的 OKX 永续合约交易 Agent。基于候选快照中的客观指标做出 TRADE 或 SKIP 决策，严禁编造数据。"
    );

    private final String title;
    private final String description;
    private final BigDecimal defaultBudgetUsdt;
    private final int defaultMaxOpenPositions;
    private final int defaultMaxLeverage;
    private final int defaultMinTotalScore;
    private final int defaultMinFinalRankScore;
    private final BigDecimal defaultMaxSingleLossPct;
    private final BigDecimal defaultMaxDailyLossPct;
    private final TradingAgentDirection defaultDirection;
    private final String defaultSystemPrompt;

    TradingAgentTemplate(
            String title,
            String description,
            BigDecimal defaultBudgetUsdt,
            int defaultMaxOpenPositions,
            int defaultMaxLeverage,
            int defaultMinTotalScore,
            int defaultMinFinalRankScore,
            BigDecimal defaultMaxSingleLossPct,
            BigDecimal defaultMaxDailyLossPct,
            TradingAgentDirection defaultDirection,
            String defaultSystemPrompt
    ) {
        this.title = title;
        this.description = description;
        this.defaultBudgetUsdt = defaultBudgetUsdt;
        this.defaultMaxOpenPositions = defaultMaxOpenPositions;
        this.defaultMaxLeverage = defaultMaxLeverage;
        this.defaultMinTotalScore = defaultMinTotalScore;
        this.defaultMinFinalRankScore = defaultMinFinalRankScore;
        this.defaultMaxSingleLossPct = defaultMaxSingleLossPct;
        this.defaultMaxDailyLossPct = defaultMaxDailyLossPct;
        this.defaultDirection = defaultDirection;
        this.defaultSystemPrompt = defaultSystemPrompt;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public BigDecimal defaultBudgetUsdt() {
        return defaultBudgetUsdt;
    }

    public int defaultMaxOpenPositions() {
        return defaultMaxOpenPositions;
    }

    public int defaultMaxLeverage() {
        return defaultMaxLeverage;
    }

    public int defaultMinTotalScore() {
        return defaultMinTotalScore;
    }

    public int defaultMinFinalRankScore() {
        return defaultMinFinalRankScore;
    }

    public BigDecimal defaultMaxSingleLossPct() {
        return defaultMaxSingleLossPct;
    }

    public BigDecimal defaultMaxDailyLossPct() {
        return defaultMaxDailyLossPct;
    }

    public TradingAgentDirection defaultDirection() {
        return defaultDirection;
    }

    public String defaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    public static TradingAgentTemplate parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return TREND_BALANCED;
        }
        try {
            return TradingAgentTemplate.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return CUSTOM;
        }
    }
}

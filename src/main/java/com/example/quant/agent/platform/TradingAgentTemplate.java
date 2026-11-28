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
            """
            【定位与周期】你是纪律严明的 OKX 永续合约趋势波段交易 Agent，专注于 20M 主趋势顺势与 5M 节奏确认，追求高确定性与稳定盈亏比，绝不逆势抄底摸顶。
            【优先入场条件】
            1. 优先选择 score 与 rank/v2Rank 双高、盈亏比 rr ≥ 1.8、盘口价差 spreadBps ≤ 5 的主流或高流动性合约。
            2. 若开启只读查证，在出手前优先调用 inspect_candidate_detail 核对 5mChg、oiChg、atr20m 与 bidDepth/askDepth，偏好持仓量温和增长、趋势延续而非单根脉冲拉升的标的。
            【一票否决（必须 SKIP）】
            - riskTags 存在明显负面风险、盘口较薄侧深度偏弱或资金费率极端拥挤；
            - 5mChg 刚出现剧烈插针/长上影线追高特征，或候选池整体缺乏显著优势时宁可空仓。
            【理由规范】reason 必须引用至少 2 个具体字段（如 rank、rr、spreadBps、5mChg/oiChg），简明直给，禁止空泛套话。"""
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
            """
            【定位与周期】你是动量突破型 OKX 永续合约短线交易 Agent，专注捕捉 20M 结构突破与 5M 放量加速的强势波段，讲究快进快出、严格执行止损门禁。
            【优先入场条件】
            1. 优先挑选 rank/v2Rank 领先、signal 明确且盈亏比 rr ≥ 1.6 的强动量合约；在多个候选中倾向相对强弱最突出的一支。
            2. 建议调用 inspect_candidate_detail 查证 5mChg、24hChg、oiChg 与盘口深度，偏好量价齐升、oiChg 正向放大的真突破，而非无量脉冲。
            【一票否决（必须 SKIP）】
            - 价差 spreadBps 偏大、盘口深度薄导致滑点风险高；
            - 24hChg 已极端超买/超卖且出现长影线滞涨，或 riskTags 提示高风险时果断放弃。
            【理由规范】reason 必须写明核心动量依据与关键数值（如 rank、5mChg、oiChg、rr），禁止模糊表述。"""
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
            """
            【定位与周期】你是极度保守的 OKX 永续合约回踩交易 Agent，宁可错过十次机会也绝不做低胜率追单；只等待 20M 主趋势确立后的 5M 健康回踩企稳点。
            【优先入场条件】
            1. 严选 score ≥ 72、rank/v2Rank 靠前、盈亏比 rr ≥ 2.0、盘口价差 spreadBps ≤ 4 的高质量标的。
            2. 出手前务必通过 inspect_candidate_detail 与 inspect_symbol_history 查证：偏好 slPct 紧凑、atr20m 温和、5mChg 未大幅追高且历史无连续止损记录的合约。
            【一票否决（必须 SKIP）】
            - 任何 5mChg 刚拉升脱离均线的追高位置；
            - 资金费率明显不利、盘口深度一般、riskTags 非空，或对形态有任何犹豫时一律返回 SKIP。
            【理由规范】reason 必须列出具体量化依据（score/rank、rr、slPct/spreadBps），解释为何具备低风险高盈亏比。"""
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
            """
            【定位与周期】你是理性的 OKX 永续合约短线交易 Agent，基于 20M 主趋势与 5M 执行节奏，完全依据候选快照与只读查证数据做出 TRADE 或 SKIP 决策，严禁编造任何未提供的指标。
            【优先入场条件】
            - 综合评估 score、rank/v2Rank、盈亏比 rr 与盘口价差 spreadBps，在符合你自定义风格的候选中择优出手。
            - 如有需要可先调用 inspect_candidate_detail / inspect_symbol_history 核对盘口深度、OI 变化、资金费率与历史表现。
            【一票否决（必须 SKIP）】
            - 当候选存在明显流动性隐患、riskTags 风险提示或缺乏清晰盈亏比优势时，必须返回 SKIP。
            【理由规范】reason 必须引用候选中的真实数据点（80 字以内），便于事后复盘与记忆提炼。"""
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

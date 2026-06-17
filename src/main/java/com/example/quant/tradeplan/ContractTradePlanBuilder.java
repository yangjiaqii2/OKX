package com.example.quant.tradeplan;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.agent.entry.SmartEntryMode;
import com.example.quant.agent.entry.SmartEntryModeDecision;
import com.example.quant.agent.entry.SmartEntryModeResolver;
import com.example.quant.agent.gate.ContractTradeGate;
import com.example.quant.agent.plan.TradePlanRecordService;
import com.example.quant.ai.AiAnalysisService;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.OkxContractScanner;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.leverage.LeverageDecisionRequest;
import com.example.quant.leverage.LeverageDecisionResult;
import com.example.quant.leverage.LeverageDecisionService;
import com.example.quant.market.DirectionBias;
import com.example.quant.position.PositionSizingRequest;
import com.example.quant.position.PositionSizingResult;
import com.example.quant.position.PositionSizingService;
import com.example.quant.risk.ContractRiskRequest;
import com.example.quant.risk.RiskCheckResult;
import com.example.quant.risk.RiskLevel;
import com.example.quant.risk.RiskService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class ContractTradePlanBuilder {
    private static final BigDecimal FALLBACK_EQUITY = BigDecimal.valueOf(1_000);
    private static final BigDecimal FALLBACK_MARGIN = BigDecimal.valueOf(20);
    private static final BigDecimal MIN_RISK_REWARD = BigDecimal.valueOf(1.5);

    private final OkxContractScanner scanner;
    private final AiAnalysisService aiAnalysisService;
    private final AccountSnapshotService accountSnapshotService;
    private final PositionSizingService positionSizingService;
    private final LeverageDecisionService leverageDecisionService;
    private final RiskService riskService;
    private final AgentProperties agentProperties;
    private final TradePlanRecordService tradePlanRecordService;

    public ContractTradePlanBuilder(
            OkxContractScanner scanner,
            AiAnalysisService aiAnalysisService,
            AccountSnapshotService accountSnapshotService,
            PositionSizingService positionSizingService,
            LeverageDecisionService leverageDecisionService,
            RiskService riskService,
            AgentProperties agentProperties,
            TradePlanRecordService tradePlanRecordService
    ) {
        this.scanner = scanner;
        this.aiAnalysisService = aiAnalysisService;
        this.accountSnapshotService = accountSnapshotService;
        this.positionSizingService = positionSizingService;
        this.leverageDecisionService = leverageDecisionService;
        this.riskService = riskService;
        this.agentProperties = agentProperties;
        this.tradePlanRecordService = tradePlanRecordService;
    }

    public TradePlan buildPlan(String instId) {
        List<ContractCandidate> candidates = scanner.scan();
        ContractCandidate candidate = candidates.stream()
                .filter(item -> instId != null && item.instId().equals(instId))
                .findFirst()
                .orElseGet(() -> candidates.stream()
                        .max(Comparator.comparing(ContractCandidate::score))
                        .orElseThrow(() -> new IllegalStateException("OKX did not return contract candidates")));
        return buildPlan(candidate);
    }

    public TradePlan buildPlan(ContractCandidate candidate) {
        return buildPlan(candidate, false);
    }

    public TradePlan buildNoRiskPlan(ContractCandidate candidate) {
        return buildPlan(candidate, true);
    }

    private TradePlan buildPlan(ContractCandidate candidate, boolean noRiskMode) {
        List<String> gateWarnings = ContractTradeGate.planDenyReasons(candidate, agentProperties);
        if (!noRiskMode && watchOnlyCandidate(candidate)) {
            TradePlan watchPlan = watchTradePlan(candidate, gateWarnings, "候选不满足自动交易计划条件，输出观察计划");
            tradePlanRecordService.record(watchPlan, TradePlanStatus.CREATED, String.join(",", gateWarnings),
                    auditPayload(candidate, gateWarnings, watchDraft(candidate)));
            return watchPlan;
        }
        List<String> entryReasons;
        if (noRiskMode) {
            entryReasons = List.of("无风控模式：忽略AUTO_TRADE_ALLOWED和智能入场等待门控，直接生成可提交计划");
        } else {
            SmartEntryModeDecision entryDecision = new SmartEntryModeResolver(agentProperties).resolve(candidate);
            if (entryDecision.mode() != SmartEntryMode.MARKET) {
                TradePlan watchPlan = watchTradePlan(candidate, gateWarnings,
                        "智能入场模式" + entryDecision.mode() + "：" + String.join("；", entryDecision.reasons()));
                tradePlanRecordService.record(watchPlan, TradePlanStatus.CREATED, String.join(",", gateWarnings),
                        auditPayload(candidate, gateWarnings, watchDraft(candidate)));
                return watchPlan;
            }
            entryReasons = entryDecision.reasons();
        }
        PlanDraft draft = noRiskMode ? noRiskPlanOrFallback(candidate) : aiPlanOrFallback(candidate);
        BigDecimal stopDistanceRate = draft.entryPrice().subtract(draft.stopLossPrice()).abs()
                .divide(draft.entryPrice(), 8, RoundingMode.HALF_UP);
        AccountSummary account = safeAccount();
        LeverageDecisionResult leverageDecision = leverageDecisionService.decide(new LeverageDecisionRequest(
                candidate.instId(),
                candidate.factorScore().trendScore(),
                candidate.volatility(),
                candidate.fundingRate(),
                candidate.openInterestChange(),
                stopDistanceRate,
                usableEquity(account),
                agentProperties.risk().maxLeverage(),
                safeRiskLevel(candidate.marketRiskLevel()),
                draft.confidence()
        ));
        int leverage = analyzedLeverage(candidate, draft.suggestedLeverage(), leverageDecision);
        PositionSizingResult sizing = sizing(candidate, draft, account, leverage, leverageDecision.riskLevel());
        List<String> reasons = Stream.concat(
                        mergedPlanReasons(draft, candidate, leverageDecision, sizing).stream(),
                        entryReasons.stream())
                .distinct()
                .toList();
        List<String> risks = mergedPlanRisks(draft, candidate, leverageDecision, sizing, account, gateWarnings);

        TradePlan tradePlan = new TradePlan(
                UUID.randomUUID(),
                candidate.instId(),
                draft.action(),
                draft.orderType(),
                draft.direction(),
                draft.confidence(),
                draft.entryPrice(),
                draft.stopLossPrice(),
                draft.takeProfitPrice(),
                leverage,
                sizing.suggestedSize(),
                sizing.suggestedMargin(),
                sizing.maxLossAmount(),
                sizing.riskRewardRatio().signum() > 0
                        ? sizing.riskRewardRatio().setScale(2, RoundingMode.HALF_UP)
                        : draft.riskRewardRatio(),
                candidate.score(),
                candidate.fundingRate(),
                candidate.volatility(),
                candidate.volume24h(),
                "cross",
                reasons,
                risks,
                draft.invalidCondition(),
                true,
                Instant.now().plusSeconds(300)
        );

        RiskCheckResult risk = riskService.check(riskRequestFor(tradePlan, account, leverageDecision.riskLevel()));
        String softDenyReason = null;
        if (!risk.passed()) {
            softDenyReason = risk.rejectCode() + ": " + risk.rejectReason();
            tradePlan = withExtraRisk(tradePlan, "规则风控提示：" + softDenyReason + "；已按高可用模式继续生成小额计划");
        }
        tradePlanRecordService.record(tradePlan, TradePlanStatus.CREATED, softDenyReason, auditPayload(candidate, gateWarnings, draft));
        return tradePlan;
    }

    private PlanDraft aiPlanOrFallback(ContractCandidate candidate) {
        try {
            JsonNode plan = aiAnalysisService.completeJson(systemPrompt(), userPrompt(candidate));
            PlanDraft draft = aiPlan(candidate, plan);
            if (draft != null) {
                return draft;
            }
            return rulePlan(candidate, "AI建议WAIT，按高可用规则计划兜底");
        } catch (RuntimeException ex) {
            return rulePlan(candidate, "AI计划不可用，按高可用规则计划兜底：" + compact(ex.getMessage()));
        }
    }

    private PlanDraft noRiskPlanOrFallback(ContractCandidate candidate) {
        try {
            JsonNode plan = aiAnalysisService.completeJson(systemPrompt(), userPrompt(candidate));
            PlanDraft draft = aiPlan(candidate, plan);
            if (draft != null && draft.action() != TradePlanType.WATCH) {
                return draft;
            }
            return rulePlan(candidate, "无风控模式AI建议WAIT，已按本地规则直接生成计划", true);
        } catch (RuntimeException ex) {
            return rulePlan(candidate, "无风控模式AI计划不可用，按本地规则直接生成计划：" + compact(ex.getMessage()), true);
        }
    }

    private PlanDraft aiPlan(ContractCandidate candidate, JsonNode plan) {
        if (plan == null || !plan.isObject()) {
            throw new IllegalStateException("AI计划不是JSON对象");
        }
        TradePlanType action = action(plan.path("action").asText(""));
        if (action == null) {
            return null;
        }
        if (action == TradePlanType.WATCH) {
            return watchDraft(candidate);
        }
        DirectionBias direction = action == TradePlanType.OPEN_SHORT ? DirectionBias.BEARISH : DirectionBias.BULLISH;
        BigDecimal entry = positiveDecimal(plan, "entryPrice", positive(candidate.entryPrice(), candidate.lastPrice()));
        BigDecimal stopLoss = positiveDecimal(plan, "stopLossPrice", fallbackStop(entry, direction));
        BigDecimal takeProfit = positiveDecimal(plan, "takeProfitPrice", fallbackTakeProfit(entry, stopLoss, direction));
        BigDecimal riskReward = safeRiskReward(action, entry, stopLoss, takeProfit);
        if (riskReward.compareTo(MIN_RISK_REWARD) < 0) {
            throw new IllegalStateException("AI计划盈亏比不足：" + riskReward);
        }
        String orderType = plan.path("orderType").asText("LIMIT");
        if (!List.of("LIMIT", "MARKET").contains(orderType)) {
            orderType = "LIMIT";
        }
        orderType = entryOrderType(candidate, orderType);
        BigDecimal confidence = decimal(plan, "confidence", BigDecimal.valueOf(0.65)).max(BigDecimal.ZERO).min(BigDecimal.ONE);
        int leverage = Math.max(1, plan.path("leverage").asInt(candidate.suggestedLeverage()));
        return new PlanDraft(
                action,
                orderType,
                direction,
                confidence,
                entry,
                stopLoss.max(BigDecimal.ZERO),
                takeProfit.max(BigDecimal.ZERO),
                riskReward,
                leverage,
                stringList(plan.path("reasonList"), "AI生成单合约交易计划"),
                stringList(plan.path("riskList"), "AI计划仍需执行风控和OKX委托校验"),
                "AI计划；下一轮扫描若评分、方向或风控变化则失效",
                "AI"
        );
    }

    private PlanDraft rulePlan(ContractCandidate candidate, String fallbackReason) {
        return rulePlan(candidate, fallbackReason, false);
    }

    private PlanDraft rulePlan(ContractCandidate candidate, String fallbackReason, boolean ignoreWatchOnly) {
        if (!ignoreWatchOnly && watchOnlyCandidate(candidate)) {
            return watchDraft(candidate);
        }
        DirectionBias direction = candidate.trendDirection() == DirectionBias.BEARISH
                ? DirectionBias.BEARISH
                : DirectionBias.BULLISH;
        TradePlanType action = direction == DirectionBias.BEARISH ? TradePlanType.OPEN_SHORT : TradePlanType.OPEN_LONG;
        BigDecimal entry = positive(candidate.entryPrice(), candidate.lastPrice());
        BigDecimal stopLoss = positive(candidate.stopLossPrice(), fallbackStop(entry, direction));
        BigDecimal takeProfit = positive(candidate.takeProfitPrice(), fallbackTakeProfit(entry, stopLoss, direction));
        BigDecimal riskReward = safeRiskReward(action, entry, stopLoss, takeProfit);
        if (riskReward.compareTo(MIN_RISK_REWARD) < 0) {
            BigDecimal riskDistance = entry.subtract(stopLoss).abs();
            takeProfit = direction == DirectionBias.BEARISH
                    ? entry.subtract(riskDistance.multiply(MIN_RISK_REWARD))
                    : entry.add(riskDistance.multiply(MIN_RISK_REWARD));
            riskReward = safeRiskReward(action, entry, stopLoss, takeProfit);
        }
        BigDecimal confidence = BigDecimal.valueOf(Math.max(35, Math.min(90, candidate.score())))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new PlanDraft(action, entryOrderType(candidate, "LIMIT"), direction, confidence, entry, stopLoss.max(BigDecimal.ZERO),
                takeProfit.max(BigDecimal.ZERO), riskReward, candidate.suggestedLeverage(),
                List.of("最高分候选AI计划失败后采用本地规则兜底"),
                List.of(fallbackReason),
                "AI计划不可用时的高可用兜底计划；下一轮扫描若评分、方向或风控变化则失效",
                "LOCAL_FALLBACK");
    }

    private PositionSizingResult sizing(ContractCandidate candidate, PlanDraft draft, AccountSummary account,
                                        int leverage, RiskLevel riskLevel) {
        try {
            PositionSizingResult result = positionSizingService.calculate(new PositionSizingRequest(
                    usableEquity(account),
                    usableBalance(account),
                    draft.entryPrice(),
                    draft.stopLossPrice(),
                    draft.takeProfitPrice(),
                    leverage,
                    percentRate(agentProperties.risk().singleTradeRiskPercent()),
                    percentRate(agentProperties.risk().maxSingleLossPercent()),
                    MIN_RISK_REWARD,
                    candidate.volatility(),
                    riskLevel
            ));
            if (result.accepted()) {
                return result;
            }
            return fallbackSizing(draft, leverage, "仓位计算未通过：" + result.rejectReason());
        } catch (RuntimeException ex) {
            return fallbackSizing(draft, leverage, "仓位计算异常：" + ex.getMessage());
        }
    }

    private static PositionSizingResult fallbackSizing(PlanDraft draft, int leverage, String reason) {
        BigDecimal margin = FALLBACK_MARGIN;
        BigDecimal positionValue = margin.multiply(BigDecimal.valueOf(Math.max(1, leverage)));
        BigDecimal size = positionValue.divide(draft.entryPrice(), 8, RoundingMode.DOWN);
        BigDecimal maxLoss = draft.entryPrice().subtract(draft.stopLossPrice()).abs()
                .multiply(size)
                .setScale(4, RoundingMode.HALF_UP);
        return new PositionSizingResult(true, size, margin, maxLoss, BigDecimal.ZERO, positionValue,
                draft.riskRewardRatio(), List.of("账户或风控数据不可用时使用固定小额兜底仓位"),
                List.of(reason), null);
    }

    private AccountSummary safeAccount() {
        try {
            AccountSummary account = accountSnapshotService.summary();
            if (account.equity().signum() > 0 && account.availableBalance().signum() > 0) {
                return account;
            }
            if (account.availableBalance().signum() > 0) {
                return new AccountSummary(
                        account.availableBalance(),
                        account.availableBalance(),
                        account.mode(),
                        "OKX账户总览为0，已使用可用余额作为自动交易资金基准：" + account.message()
                );
            }
            return new AccountSummary(
                    account.equity().signum() > 0 ? account.equity() : FALLBACK_EQUITY,
                    account.availableBalance().signum() > 0 ? account.availableBalance() : FALLBACK_MARGIN,
                    "FALLBACK",
                    "OKX账户权益/余额不可用，使用固定小额兜底：" + account.message()
            );
        } catch (RuntimeException ex) {
            return new AccountSummary(FALLBACK_EQUITY, FALLBACK_MARGIN, "FALLBACK",
                    "OKX账户读取异常，使用固定小额兜底：" + ex.getMessage());
        }
    }

    private ContractRiskRequest riskRequestFor(TradePlan plan, AccountSummary account, RiskLevel riskLevel) {
        ContractRiskRequest defaults = ContractRiskRequest.safeDefaults();
        return new ContractRiskRequest(
                defaults.emergencyStop(),
                defaults.websocketConnected(),
                defaults.marketDelayMs(),
                defaults.maxMarketDelayMs(),
                usableEquity(account),
                plan.suggestedMargin(),
                percentRate(agentProperties.risk().maxSingleLossPercent()).max(new BigDecimal("0.20")),
                plan.suggestedLeverage(),
                agentProperties.risk().maxLeverage(),
                plan.entryPrice(),
                plan.stopLossPrice(),
                plan.riskRewardRatio(),
                MIN_RISK_REWARD,
                plan.fundingRate(),
                agentProperties.market().maxFundingAbs().multiply(BigDecimal.valueOf(3)),
                plan.volume24h(),
                BigDecimal.ZERO,
                safeRiskLevel(riskLevel),
                plan.volatility(),
                defaults.maxStopLossDistanceRate(),
                plan.signalScore(),
                Math.min(agentProperties.score().minTotalScore(), 55),
                percentRate(agentProperties.risk().singleTradeRiskPercent())
        );
    }

    private static TradePlan withExtraRisk(TradePlan plan, String risk) {
        List<String> risks = Stream.concat(plan.riskList().stream(), Stream.of(risk))
                .distinct()
                .toList();
        return new TradePlan(plan.id(), plan.instId(), plan.action(), plan.orderType(), plan.directionBias(),
                plan.confidence(), plan.entryPrice(), plan.stopLossPrice(), plan.takeProfitPrice(),
                plan.suggestedLeverage(), plan.suggestedSize(), plan.suggestedMargin(), plan.maxLossAmount(),
                plan.riskRewardRatio(), plan.signalScore(), plan.fundingRate(), plan.volatility(), plan.volume24h(),
                plan.tdMode(), plan.reasonList(), risks, plan.invalidCondition(), plan.needUserConfirm(),
                plan.expireTime());
    }

    private static String entryOrderType(ContractCandidate candidate, String requestedOrderType) {
        if ("AUTO_TRADE_ALLOWED".equals(candidate.action())) {
            return "MARKET";
        }
        return requestedOrderType;
    }

    private static boolean watchOnlyCandidate(ContractCandidate candidate) {
        return !"AUTO_TRADE_ALLOWED".equals(candidate.action())
                || !List.of(
                com.example.quant.crypto.dto.ContractSignalType.STRONG_LONG,
                com.example.quant.crypto.dto.ContractSignalType.PULLBACK_LONG,
                com.example.quant.crypto.dto.ContractSignalType.TREND_SHORT,
                com.example.quant.crypto.dto.ContractSignalType.REVERSAL_SHORT
        ).contains(candidate.signalType())
                || "HIGH".equals(candidate.newsAnalysis().newsRiskLevel())
                || "CRITICAL".equals(candidate.newsAnalysis().newsRiskLevel())
                || "UNKNOWN".equals(candidate.newsAnalysis().newsRiskLevel());
    }

    private static PlanDraft watchDraft(ContractCandidate candidate) {
        BigDecimal entry = positive(candidate.entryPrice(), candidate.lastPrice());
        return new PlanDraft(
                TradePlanType.WATCH,
                "NO_ENTRY",
                DirectionBias.NEUTRAL,
                BigDecimal.ZERO,
                entry,
                positive(candidate.stopLossPrice(), entry),
                positive(candidate.takeProfitPrice(), entry),
                BigDecimal.ZERO,
                0,
                List.of("候选信号或外部风险未满足自动交易条件，输出观察计划"),
                List.of("action=" + candidate.action(), "signalType=" + candidate.signalType(),
                        "newsRiskLevel=" + candidate.newsAnalysis().newsRiskLevel()),
                "等待20m结构、5m入场节奏、新闻风险和流动性重新确认",
                "LOCAL_WATCH"
        );
    }

    private TradePlan watchTradePlan(ContractCandidate candidate, List<String> gateWarnings, String reason) {
        PlanDraft draft = watchDraft(candidate);
        List<String> reasons = Stream.concat(draft.reasonList().stream(), candidate.candidateReasonList().stream())
                .distinct()
                .toList();
        List<String> risks = Stream.concat(
                        Stream.concat(draft.riskList().stream(), candidate.riskTagList().stream()),
                        gateWarnings.stream().map(item -> "交易门控提示：" + item))
                .distinct()
                .toList();
        return new TradePlan(
                UUID.randomUUID(),
                candidate.instId(),
                TradePlanType.WATCH,
                "NO_ENTRY",
                DirectionBias.NEUTRAL,
                BigDecimal.ZERO,
                draft.entryPrice(),
                draft.stopLossPrice(),
                draft.takeProfitPrice(),
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                candidate.score(),
                candidate.fundingRate(),
                candidate.volatility(),
                candidate.volume24h(),
                "cross",
                reasons,
                Stream.concat(risks.stream(), Stream.of(reason)).distinct().toList(),
                draft.invalidCondition(),
                false,
                Instant.now().plusSeconds(300)
        );
    }

    private static int analyzedLeverage(ContractCandidate candidate, int aiLeverage, LeverageDecisionResult leverageDecision) {
        int analyzed = Math.max(1, candidate.suggestedLeverage());
        int requested = Math.max(1, aiLeverage);
        int decided = Math.max(1, leverageDecision.suggestedLeverage());
        return Math.min(Math.min(analyzed, requested), decided);
    }

    private static List<String> mergedPlanReasons(PlanDraft draft,
                                                  ContractCandidate candidate,
                                                  LeverageDecisionResult leverageDecision,
                                                  PositionSizingResult sizing) {
        return Stream.of(
                        draft.reasonList(),
                        candidate.candidateReasonList(),
                        leverageDecision.leverageReasonList(),
                        sizing.reasonList())
                .flatMap(List::stream)
                .distinct()
                .toList();
    }

    private static List<String> mergedPlanRisks(PlanDraft draft,
                                                ContractCandidate candidate,
                                                LeverageDecisionResult leverageDecision,
                                                PositionSizingResult sizing,
                                                AccountSummary account,
                                                List<String> gateWarnings) {
        List<String> risks = new ArrayList<>();
        risks.addAll(draft.riskList());
        risks.addAll(candidate.riskTagList());
        risks.addAll(leverageDecision.leverageRiskList());
        risks.addAll(sizing.warningList());
        gateWarnings.stream()
                .map(reason -> "交易门控提示：" + reason)
                .forEach(risks::add);
        if (!"OKX_REAL".equals(account.mode()) && !"OKX_EMPTY".equals(account.mode())) {
            risks.add("OKX账户未完成读取验证：" + account.message());
        }
        return risks.stream().distinct().toList();
    }

    private static Map<String, Object> auditPayload(ContractCandidate candidate, List<String> gateWarnings, PlanDraft draft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", draft.source());
        payload.put("instId", candidate.instId());
        payload.put("score", candidate.score());
        payload.put("factorScore", candidate.factorScore());
        payload.put("action", draft.action());
        payload.put("entryPrice", draft.entryPrice());
        payload.put("stopLossPrice", draft.stopLossPrice());
        payload.put("takeProfitPrice", draft.takeProfitPrice());
        payload.put("gateWarnings", gateWarnings);
        payload.put("scoreWeights", "trend=25, volume=25, liquidity=15, volatility=10, oiFunding=10, marketEnv=8, newsRisk=7");
        return payload;
    }

    private static String systemPrompt() {
        return """
                你是OKX USDT永续合约自动交易计划分析器。
                只分析输入的这一个合约，不要推荐其他合约。
                必须输出严格JSON，不要Markdown，不要解释。
                若方向和价格结构可交易，输出OPEN_LONG或OPEN_SHORT；只有明显不可交易才输出WAIT。
                禁止为WAIT_OVERHEATED、WAIT_OVERSOLD、NEUTRAL、NO_TRADE、新闻HIGH/CRITICAL/UNKNOWN候选输出开仓。
                杠杆leverage必须结合波动率、资金费率、止损距离和清算风险，不得超过系统建议杠杆上限。
                输出字段：
                action: OPEN_LONG | OPEN_SHORT | WAIT
                orderType: LIMIT | MARKET
                entryPrice: number
                stopLossPrice: number
                takeProfitPrice: number
                leverage: number
                confidence: 0到1
                reasonList: string[]
                riskList: string[]
                invalidCondition: string
                """;
    }

    private static String userPrompt(ContractCandidate candidate) {
        return """
                本轮自动交易只允许分析这个最高分候选，并为它生成交易计划。
                合约: %s
                最新价: %s
                24h涨跌幅百分比: %s
                24h成交量USDT: %s
                趋势方向: %s
                综合评分: %s
                因子评分: 趋势=%s 成交量=%s 流动性=%s 波动=%s OI资金=%s 市场=%s 新闻=%s
                系统建议杠杆上限: %sx
                系统分析入场价: %s
                系统分析止损价: %s
                系统分析止盈价: %s
                系统分析风险收益比: %s
                系统信号类型: %s
                系统候选动作: %s
                最终排序分: %s
                结构化子分: %s
                K线结构: %s
                新闻风险: %s
                5m涨跌幅百分比: %s
                量能放大倍数: %s
                资金费率: %s
                持仓量: %s
                ATR波动率: %s
                订单簿价差bps: %s
                买盘深度USDT估算: %s
                卖盘深度USDT估算: %s
                BTC趋势: %s
                ETH趋势: %s
                市场风险: %s
                入选理由: %s
                风险标签: %s
                """.formatted(
                candidate.instId(),
                candidate.lastPrice(),
                candidate.changePercent24h(),
                candidate.volume24h(),
                candidate.trendDirection(),
                candidate.score(),
                candidate.factorScore().trendScore(),
                candidate.factorScore().volumeScore(),
                candidate.factorScore().liquidityScore(),
                candidate.factorScore().volatilityScore(),
                candidate.factorScore().oiFundingScore(),
                candidate.factorScore().marketEnvScore(),
                candidate.factorScore().newsRiskScore(),
                candidate.suggestedLeverage(),
                candidate.entryPrice(),
                candidate.stopLossPrice(),
                candidate.takeProfitPrice(),
                candidate.riskRewardRatio(),
                candidate.signalType(),
                candidate.action(),
                candidate.finalRankScore(),
                candidate.scoreBreakdown(),
                candidate.klineAnalysis(),
                candidate.newsAnalysis(),
                candidate.changePercent5m(),
                candidate.volumeSpikeRatio(),
                candidate.fundingRate(),
                candidate.openInterest(),
                candidate.volatility(),
                candidate.spreadBps(),
                candidate.bidDepthUsdt(),
                candidate.askDepthUsdt(),
                candidate.btcTrend(),
                candidate.ethTrend(),
                candidate.marketRiskLevel(),
                candidate.candidateReasonList(),
                candidate.riskTagList()
        );
    }

    private static TradePlanType action(String value) {
        return switch (value) {
            case "OPEN_LONG" -> TradePlanType.OPEN_LONG;
            case "OPEN_SHORT" -> TradePlanType.OPEN_SHORT;
            case "WAIT", "NO_TRADE" -> TradePlanType.WATCH;
            default -> null;
        };
    }

    private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        String value = node.path(field).asText("");
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static BigDecimal positiveDecimal(JsonNode node, String field, BigDecimal fallback) {
        BigDecimal value = decimal(node, field, fallback);
        if (value == null || value.signum() <= 0) {
            throw new IllegalStateException("AI计划字段无效：" + field);
        }
        return value;
    }

    private static List<String> stringList(JsonNode node, String fallback) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    result.add(value);
                }
            }
        }
        return result.isEmpty() ? List.of(fallback) : result;
    }

    private static BigDecimal fallbackStop(BigDecimal entry, DirectionBias direction) {
        BigDecimal distance = entry.multiply(new BigDecimal("0.01"));
        return direction == DirectionBias.BEARISH ? entry.add(distance) : entry.subtract(distance);
    }

    private static BigDecimal fallbackTakeProfit(BigDecimal entry, BigDecimal stopLoss, DirectionBias direction) {
        BigDecimal distance = entry.subtract(stopLoss).abs().multiply(BigDecimal.valueOf(2));
        return direction == DirectionBias.BEARISH ? entry.subtract(distance) : entry.add(distance);
    }

    private static BigDecimal safeRiskReward(TradePlanType action, BigDecimal entry, BigDecimal stopLoss,
                                             BigDecimal takeProfit) {
        BigDecimal risk = action == TradePlanType.OPEN_SHORT ? stopLoss.subtract(entry) : entry.subtract(stopLoss);
        BigDecimal reward = action == TradePlanType.OPEN_SHORT ? entry.subtract(takeProfit) : takeProfit.subtract(entry);
        if (risk.signum() <= 0 || reward.signum() <= 0) {
            return MIN_RISK_REWARD;
        }
        return reward.divide(risk, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal positive(BigDecimal value, BigDecimal fallback) {
        if (value != null && value.signum() > 0) {
            return value;
        }
        return fallback != null && fallback.signum() > 0 ? fallback : BigDecimal.ONE;
    }

    private static String compact(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 160 ? compact.substring(0, 160) + "..." : compact;
    }

    private static BigDecimal usableEquity(AccountSummary account) {
        if (account.equity().signum() > 0) {
            return account.equity();
        }
        if (account.availableBalance().signum() > 0) {
            return account.availableBalance();
        }
        return FALLBACK_EQUITY;
    }

    private static BigDecimal usableBalance(AccountSummary account) {
        return account.availableBalance().signum() > 0 ? account.availableBalance() : FALLBACK_MARGIN;
    }

    private static RiskLevel safeRiskLevel(RiskLevel riskLevel) {
        return riskLevel == null || riskLevel == RiskLevel.BLOCKED ? RiskLevel.HIGH : riskLevel;
    }

    private static BigDecimal percentRate(BigDecimal percent) {
        if (percent == null) {
            return BigDecimal.ZERO;
        }
        return percent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
    }

    private record PlanDraft(
            TradePlanType action,
            String orderType,
            DirectionBias direction,
            BigDecimal confidence,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            BigDecimal takeProfitPrice,
            BigDecimal riskRewardRatio,
            int suggestedLeverage,
            List<String> reasonList,
            List<String> riskList,
            String invalidCondition,
            String source
    ) {
    }
}

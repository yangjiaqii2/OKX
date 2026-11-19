package com.example.quant.tradeplan;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.agent.entry.SmartEntryMode;
import com.example.quant.agent.entry.SmartEntryModeDecision;
import com.example.quant.agent.entry.SmartEntryModeResolver;
import com.example.quant.agent.gate.ContractTradeGate;
import com.example.quant.agent.plan.TradePlanRecordService;
import com.example.quant.agent.selection.ContractSelectionDecision;
import com.example.quant.ai.AiAnalysisService;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.OkxContractScanner;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
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
import java.time.Duration;
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
    private static final BigDecimal MIN_RISK_REWARD = BigDecimal.valueOf(1.5);
    private static final String STRATEGY_VERSION = "contract-analysis-s1s2-v2";

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
        return buildPlan(requireRequestedCandidate(instId, candidates));
    }

    /**
     * 自动推荐入口：在未指定标的时选取当前扫描最高分候选。
     * 指定标的请走 {@link #buildPlan(String)}，缺失时拒绝而不是静默换币。
     */
    public TradePlan buildTopCandidatePlan() {
        List<ContractCandidate> candidates = scanner.scan();
        ContractCandidate top = candidates.stream()
                .max(Comparator.comparing(ContractCandidate::score))
                .orElseThrow(() -> new IllegalStateException("OKX did not return contract candidates"));
        return buildPlan(top);
    }

    static ContractCandidate requireRequestedCandidate(String instId, List<ContractCandidate> candidates) {
        if (instId == null || instId.isBlank()) {
            throw new IllegalArgumentException("requested_inst_id_required");
        }
        List<ContractCandidate> safe = candidates == null ? List.of() : candidates;
        return safe.stream()
                .filter(item -> instId.equals(item.instId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "requested_inst_id_not_in_candidates:" + instId));
    }

    public TradePlan buildPlan(ContractCandidate candidate) {
        return buildPlan(candidate, false);
    }

    public TradePlan buildNoRiskPlan(ContractCandidate candidate) {
        return buildPlan(candidate, true);
    }

    public TradePlan buildSelectedPlan(ContractCandidate candidate, ContractSelectionDecision selection) {
        if (selection == null || selection.source() != ContractSelectionDecision.DecisionSource.AI_PRIMARY
                || !candidate.instId().equals(selection.selectedInstId())) {
            throw new IllegalArgumentException("AI selection does not match candidate");
        }
        return buildPlan(candidate, false, selectedDraft(candidate, selection), true);
    }

    public TradePlan buildRuleFallbackPlan(ContractCandidate candidate) {
        return buildPlan(candidate, false, rulePlan(candidate, "AI选币不可用，使用确定性规则保底"), false);
    }

    private TradePlan buildPlan(ContractCandidate candidate, boolean noRiskMode) {
        return buildPlan(candidate, noRiskMode, null, false);
    }

    private TradePlan buildPlan(ContractCandidate candidate, boolean noRiskMode, PlanDraft selectedDraft,
                                boolean aiPrimary) {
        List<String> gateWarnings = ContractTradeGate.planDenyReasons(candidate, agentProperties);
        if (noRiskMode) {
            PlanDraft draft = noRiskPlanOrFallback(candidate);
            TradePlan shadowPlan = shadowTradePlan(candidate, gateWarnings, draft);
            tradePlanRecordService.record(shadowPlan, TradePlanStatus.CREATED, "SHADOW_ONLY",
                    auditPayload(candidate, gateWarnings, draft));
            return shadowPlan;
        }
        if (!noRiskMode && !aiPrimary && watchOnlyCandidate(candidate)) {
            TradePlan watchPlan = watchTradePlan(candidate, gateWarnings, "候选不满足自动交易计划条件，输出观察计划");
            tradePlanRecordService.record(watchPlan, TradePlanStatus.CREATED, String.join(",", gateWarnings),
                    auditPayload(candidate, gateWarnings, watchDraft(candidate)));
            return watchPlan;
        }
        SmartEntryModeDecision entryDecision = aiPrimary
                ? new SmartEntryModeDecision(SmartEntryMode.MARKET, true, List.of("AI主选后由规则重建价格与风险参数"))
                : new SmartEntryModeResolver(agentProperties).resolve(candidate);
        if (!aiPrimary && entryDecision.mode() != SmartEntryMode.MARKET) {
            TradePlan watchPlan = watchTradePlan(candidate, gateWarnings,
                    "智能入场模式" + entryDecision.mode() + "：" + String.join("；", entryDecision.reasons()));
            tradePlanRecordService.record(watchPlan, TradePlanStatus.CREATED, String.join(",", gateWarnings),
                    auditPayload(candidate, gateWarnings, watchDraft(candidate)));
            return watchPlan;
        }
        List<String> entryReasons = entryDecision.reasons();
        PlanDraft draft = selectedDraft == null ? aiPlanOrFallback(candidate) : selectedDraft;
        if (draft.action() == TradePlanType.WATCH) {
            TradePlan watchPlan = watchTradePlan(candidate, gateWarnings,
                    draft.riskList().stream().findFirst().orElse("AI或确定性规则将候选降级为观察"), draft);
            tradePlanRecordService.record(watchPlan, TradePlanStatus.CREATED, String.join(",", gateWarnings),
                    auditPayload(candidate, gateWarnings, draft));
            return watchPlan;
        }
        BigDecimal stopDistanceRate = draft.entryPrice().subtract(draft.stopLossPrice()).abs()
                .divide(draft.entryPrice(), 8, RoundingMode.HALF_UP);
        List<String> finalHardDenyReasons = finalJavaHardDenyReasons(candidate, draft, stopDistanceRate,
                agentProperties);
        if (!finalHardDenyReasons.isEmpty()) {
            TradePlan watchPlan = watchTradePlan(candidate, gateWarnings,
                    "FINAL_JAVA_HARD_GUARD: " + String.join(",", finalHardDenyReasons), draft);
            tradePlanRecordService.record(watchPlan, TradePlanStatus.CREATED,
                    String.join(",", finalHardDenyReasons), auditPayload(candidate, gateWarnings, draft));
            return watchPlan;
        }
        AccountSummary account = safeAccount();
        if (!verifiedAccount(account)) {
            TradePlan watchPlan = watchTradePlan(candidate, gateWarnings,
                    "OKX账户权益或可用余额不可用，禁止生成可执行计划：" + account.message());
            tradePlanRecordService.record(watchPlan, TradePlanStatus.CREATED, "ACCOUNT_UNAVAILABLE",
                    auditPayload(candidate, gateWarnings, draft));
            return watchPlan;
        }
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
        if (!sizing.accepted()) {
            TradePlan watchPlan = watchTradePlan(candidate, gateWarnings,
                    "仓位计算失败关闭：" + sizing.rejectReason());
            tradePlanRecordService.record(watchPlan, TradePlanStatus.CREATED, sizing.rejectReason(),
                    auditPayload(candidate, gateWarnings, draft));
            return watchPlan;
        }
        List<String> reasons = Stream.concat(
                        Stream.concat(mergedPlanReasons(draft, candidate, leverageDecision, sizing).stream(),
                                entryReasons.stream()),
                        Stream.of("finalPlanTimestamp=" + Instant.now()))
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

        RiskCheckResult risk = riskService.check(riskRequestFor(
                tradePlan, account, leverageDecision.riskLevel(), candidate.createdAt(), aiPrimary));
        if (!risk.passed()) {
            String denyReason = risk.rejectCode() + ": " + risk.rejectReason();
            TradePlan watchPlan = watchTradePlan(candidate, gateWarnings, "规则风控失败关闭：" + denyReason);
            tradePlanRecordService.record(watchPlan, TradePlanStatus.CREATED, denyReason,
                    auditPayload(candidate, gateWarnings, draft));
            return watchPlan;
        }
        tradePlanRecordService.record(tradePlan, TradePlanStatus.CREATED, null, auditPayload(candidate, gateWarnings, draft));
        return tradePlan;
    }

    private PlanDraft aiPlanOrFallback(ContractCandidate candidate) {
        return aiReviewOrFallback(candidate, false);
    }

    private PlanDraft noRiskPlanOrFallback(ContractCandidate candidate) {
        return aiReviewOrFallback(candidate, true);
    }

    private PlanDraft aiReviewOrFallback(ContractCandidate candidate, boolean ignoreWatchOnly) {
        PlanDraft deterministic = rulePlan(candidate,
                ignoreWatchOnly ? "观察模式使用确定性本地计划" : "确定性本地计划", ignoreWatchOnly);
        if (deterministic.action() == TradePlanType.WATCH) {
            return deterministic;
        }
        try {
            JsonNode plan = aiAnalysisService.completeJson(systemPrompt(), userPrompt(candidate));
            if (plan == null || !plan.isObject()) {
                throw new IllegalStateException("AI复核不是JSON对象");
            }
            TradePlanType reviewAction = action(plan.path("action").asText(""));
            if (reviewAction == TradePlanType.WATCH) {
                return watchDraft(candidate);
            }
            List<String> reasons = Stream.concat(
                            deterministic.reasonList().stream(),
                            stringList(plan.path("reasonList"), "AI未补充分析理由").stream())
                    .distinct()
                    .toList();
            List<String> risks = new ArrayList<>(deterministic.riskList());
            risks.addAll(stringList(plan.path("riskList"), "AI未补充额外风险"));
            if (reviewAction != null && reviewAction != deterministic.action()) {
                risks.add("AI方向与确定性策略不一致，已忽略AI方向");
            }
            return new PlanDraft(
                    deterministic.action(), deterministic.orderType(), deterministic.direction(),
                    deterministic.confidence(), deterministic.entryPrice(), deterministic.stopLossPrice(),
                    deterministic.takeProfitPrice(), deterministic.riskRewardRatio(),
                    deterministic.suggestedLeverage(), reasons, risks.stream().distinct().toList(),
                    deterministic.invalidCondition(), "DETERMINISTIC_AI_REVIEW"
            );
        } catch (RuntimeException ex) {
            List<String> risks = Stream.concat(deterministic.riskList().stream(),
                            Stream.of("AI复核不可用，未改变确定性交易参数：" + compact(ex.getMessage())))
                    .distinct()
                    .toList();
            return new PlanDraft(
                    deterministic.action(), deterministic.orderType(), deterministic.direction(),
                    deterministic.confidence(), deterministic.entryPrice(), deterministic.stopLossPrice(),
                    deterministic.takeProfitPrice(), deterministic.riskRewardRatio(),
                    deterministic.suggestedLeverage(), deterministic.reasonList(), risks,
                    deterministic.invalidCondition(), "DETERMINISTIC_AI_UNAVAILABLE"
            );
        }
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
        if (!validGeometry(action, entry, stopLoss, takeProfit)) {
            return watchDraft(candidate);
        }
        BigDecimal riskReward = safeRiskReward(action, entry, stopLoss, takeProfit);
        if (riskReward.compareTo(MIN_RISK_REWARD) < 0) {
            BigDecimal riskDistance = entry.subtract(stopLoss).abs();
            takeProfit = direction == DirectionBias.BEARISH
                    ? entry.subtract(riskDistance.multiply(MIN_RISK_REWARD))
                    : entry.add(riskDistance.multiply(MIN_RISK_REWARD));
            riskReward = safeRiskReward(action, entry, stopLoss, takeProfit);
        }
        if (riskReward.compareTo(MIN_RISK_REWARD) < 0) {
            return watchDraft(candidate);
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

    private PlanDraft selectedDraft(ContractCandidate candidate, ContractSelectionDecision selection) {
        if (selection.entryMode().startsWith("WAIT")) {
            return watchDraft(candidate);
        }
        DirectionBias direction = selection.direction();
        if (!directionMatchesAnalysis(candidate, direction)) {
            return directionMismatchDraft(candidate, direction);
        }
        TradePlanType action = direction == DirectionBias.BEARISH ? TradePlanType.OPEN_SHORT : TradePlanType.OPEN_LONG;
        BigDecimal entry = positive(candidate.entryPrice(), candidate.lastPrice());
        BigDecimal candidateDistance = entry.subtract(positive(candidate.stopLossPrice(), entry)).abs();
        BigDecimal minimumDistance = entry.multiply(new BigDecimal("0.006"));
        BigDecimal riskDistance = candidateDistance.max(minimumDistance);
        BigDecimal stopLoss = direction == DirectionBias.BEARISH
                ? entry.add(riskDistance) : entry.subtract(riskDistance);
        BigDecimal requestedRiskReward = candidate.riskRewardRatio() == null
                ? MIN_RISK_REWARD : candidate.riskRewardRatio().max(MIN_RISK_REWARD);
        BigDecimal takeProfit = direction == DirectionBias.BEARISH
                ? entry.subtract(riskDistance.multiply(requestedRiskReward))
                : entry.add(riskDistance.multiply(requestedRiskReward));
        if (!validGeometry(action, entry, stopLoss, takeProfit)) {
            return watchDraft(candidate);
        }
        List<String> provenance = List.of(
                "candidateOriginalSide=" + candidate.trendDirection(),
                "jevSelectedSide=" + direction,
                "finalPlanSide=" + direction,
                "directionReanalysisPerformed=false",
                "stopDistanceSource=JAVA_CANDIDATE_DIRECTION_MATCH",
                "riskRewardSource=JAVA_CANDIDATE_DIRECTION_MATCH",
                "leverageSource=JAVA_CANDIDATE_CAPPED_BY_JAVA"
        );
        return new PlanDraft(
                action,
                "MARKET".equals(selection.entryMode()) ? "MARKET" : "LIMIT",
                direction,
                selection.confidence(),
                entry,
                stopLoss,
                takeProfit,
                safeRiskReward(action, entry, stopLoss, takeProfit),
                candidate.suggestedLeverage(),
                Stream.concat(Stream.concat(Stream.of("AI主选合约与方向，规则重建入场、止损、止盈、杠杆和仓位"),
                                        provenance.stream()),
                                selection.reasonList().stream())
                        .distinct().toList(),
                selection.riskList(),
                "AI选择失效或任一硬风控不再满足时停止执行",
                "AI_PRIMARY_RULE_PRICING"
        );
    }

    private static List<String> finalJavaHardDenyReasons(ContractCandidate candidate, PlanDraft draft,
                                                          BigDecimal stopDistanceRate,
                                                          AgentProperties agentProperties) {
        List<String> reasons = candidate == null
                ? new ArrayList<>(List.of("candidate_invalid"))
                : new ArrayList<>(ContractTradeGate.planDenyReasons(candidate, agentProperties));
        if (candidate == null || candidate.instId() == null || !candidate.instId().endsWith("-USDT-SWAP")) {
            reasons.add("candidate_invalid");
        }
        if (draft.entryPrice() == null || draft.entryPrice().signum() <= 0
                || !validGeometry(draft.action(), draft.entryPrice(), draft.stopLossPrice(), draft.takeProfitPrice())) {
            reasons.add("entry_or_side_geometry_invalid");
        }
        if (stopDistanceRate.compareTo(BigDecimal.valueOf(0.05)) > 0) {
            reasons.add("final_stop_loss_pct_above_5");
        }
        if (draft.riskRewardRatio() == null || draft.riskRewardRatio().compareTo(MIN_RISK_REWARD) < 0) {
            reasons.add("final_risk_reward_below_" + MIN_RISK_REWARD);
        }
        return reasons.stream().distinct().toList();
    }

    private static boolean directionMatchesAnalysis(ContractCandidate candidate, DirectionBias selectedDirection) {
        return candidate != null && selectedDirection != null
                && ((selectedDirection == DirectionBias.BULLISH && candidate.trendDirection() == DirectionBias.BULLISH)
                || (selectedDirection == DirectionBias.BEARISH && candidate.trendDirection() == DirectionBias.BEARISH));
    }

    private static PlanDraft directionMismatchDraft(ContractCandidate candidate, DirectionBias selectedDirection) {
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
                List.of(
                        "candidateOriginalSide=" + candidate.trendDirection(),
                        "jevSelectedSide=" + selectedDirection,
                        "finalPlanSide=NONE",
                        "directionReanalysisPerformed=false",
                        "stopDistanceSource=REJECTED_DIRECTION_MISMATCH",
                        "riskRewardSource=REJECTED_DIRECTION_MISMATCH",
                        "leverageSource=REJECTED_DIRECTION_MISMATCH"
                ),
                List.of("REJECT_SELECTED_SETUP_DIRECTION_MISMATCH"),
                "Jev方向与Java原始分析方向不一致，未重新分析，禁止镜像旧止损/止盈/杠杆",
                "AI_PRIMARY_DIRECTION_MISMATCH_REJECTED"
        );
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
                    percentRate(agentProperties.risk().maxSingleMarginPercent()),
                    MIN_RISK_REWARD,
                    candidate.volatility(),
                    riskLevel
            ));
            if (result.accepted()) {
                return result;
            }
            return result;
        } catch (RuntimeException ex) {
            return new PositionSizingResult(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(),
                    "仓位计算异常：" + compact(ex.getMessage()), BigDecimal.ZERO);
        }
    }

    private AccountSummary safeAccount() {
        try {
            return accountSnapshotService.summary();
        } catch (RuntimeException ex) {
            return new AccountSummary(BigDecimal.ZERO, BigDecimal.ZERO, "OKX_ERROR",
                    "OKX账户读取异常：" + compact(ex.getMessage()));
        }
    }

    private ContractRiskRequest riskRequestFor(TradePlan plan, AccountSummary account, RiskLevel riskLevel,
                                               Instant marketDataAt, boolean deferMarketFreshnessToSubmit) {
        ContractRiskRequest defaults = ContractRiskRequest.safeDefaults();
        long marketDelayMs = deferMarketFreshnessToSubmit
                ? 0
                : marketDataAt == null
                ? Long.MAX_VALUE
                : Math.max(0, Duration.between(marketDataAt, Instant.now()).toMillis());
        return new ContractRiskRequest(
                defaults.emergencyStop(),
                marketDataAt != null,
                marketDelayMs,
                defaults.maxMarketDelayMs(),
                usableEquity(account),
                plan.suggestedMargin(),
                percentRate(agentProperties.risk().maxSingleMarginPercent()),
                plan.suggestedLeverage(),
                agentProperties.risk().maxLeverage(),
                plan.entryPrice(),
                plan.stopLossPrice(),
                plan.riskRewardRatio(),
                MIN_RISK_REWARD,
                plan.fundingRate(),
                agentProperties.market().maxFundingAbs(),
                plan.volume24h(),
                agentProperties.market().minVolume24hUsdt(),
                safeRiskLevel(riskLevel),
                plan.volatility(),
                defaults.maxStopLossDistanceRate(),
                plan.signalScore(),
                agentProperties.score().minTotalScore(),
                percentRate(agentProperties.risk().singleTradeRiskPercent())
        );
    }

    private static boolean verifiedAccount(AccountSummary account) {
        return account != null
                && ("OKX_REAL".equals(account.mode()) || "OKX_DEMO".equals(account.mode()))
                && account.equity() != null && account.equity().signum() > 0
                && account.availableBalance() != null && account.availableBalance().signum() > 0;
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
                com.example.quant.crypto.dto.ContractSignalType.TREND_SHORT
                ).contains(candidate.signalType())
                || candidate.newsAnalysis() == null
                || candidate.newsAnalysis().decision() == null
                || !candidate.newsAnalysis().decision().allowTrade();
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
        return watchTradePlan(candidate, gateWarnings, reason, watchDraft(candidate));
    }

    private TradePlan watchTradePlan(ContractCandidate candidate, List<String> gateWarnings, String reason,
                                     PlanDraft draft) {
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

    private TradePlan shadowTradePlan(ContractCandidate candidate, List<String> gateWarnings, PlanDraft draft) {
        List<String> reasons = Stream.concat(
                        Stream.of("Shadow 观察：仅记录确定性策略输出，不创建订单"),
                        draft.reasonList().stream())
                .distinct()
                .toList();
        List<String> risks = Stream.concat(
                        draft.riskList().stream(),
                        gateWarnings.stream().map(item -> "交易门控提示：" + item))
                .distinct()
                .toList();
        return new TradePlan(
                UUID.randomUUID(), candidate.instId(), TradePlanType.WATCH, "NO_ENTRY", draft.direction(),
                draft.confidence(), draft.entryPrice(), draft.stopLossPrice(), draft.takeProfitPrice(), 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, draft.riskRewardRatio(), candidate.score(),
                candidate.fundingRate(), candidate.volatility(), candidate.volume24h(), "cross", reasons, risks,
                draft.invalidCondition(), false, Instant.now().plusSeconds(300)
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
        payload.put("strategyVersion", STRATEGY_VERSION);
        payload.put("signalPool", candidate.signalType() == null ? "UNKNOWN" : candidate.signalType().name());
        payload.put("autoTradeEligible", !watchOnlyCandidate(candidate));
        payload.put("dataTimestamp", candidate.createdAt());
        payload.put("analysisSource", draft.source());
        payload.put("source", draft.source());
        payload.put("instId", candidate.instId());
        payload.put("score", candidate.score());
        payload.put("factorScore", candidate.factorScore());
        payload.put("action", draft.action());
        payload.put("entryPrice", draft.entryPrice());
        payload.put("stopLossPrice", draft.stopLossPrice());
        payload.put("takeProfitPrice", draft.takeProfitPrice());
        payload.put("gateWarnings", gateWarnings);
        payload.put("scoreWeights", ContractScoreBreakdown.weightSummary());
        return payload;
    }

    private static String systemPrompt() {
        return """
                你是OKX USDT永续合约确定性交易计划的只读复核器。
                只分析输入的这一个合约，不要推荐其他合约。
                必须输出严格JSON，不要Markdown，不要解释。
                系统已经确定方向、入场、止损、止盈、杠杆和仓位；你不能修改这些字段。
                若发现风险或证据不足，输出WAIT；否则原样确认系统方向。
                禁止为WAIT_RECLAIM、WAIT_OVERHEATED、WAIT_OVERSOLD、NEUTRAL、NO_TRADE、新闻HIGH/CRITICAL候选输出开仓。
                输出字段：
                action: OPEN_LONG | OPEN_SHORT | WAIT
                reasonList: string[]
                riskList: string[]
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
            return BigDecimal.ZERO;
        }
        return reward.divide(risk, 2, RoundingMode.HALF_UP);
    }

    private static boolean validGeometry(TradePlanType action, BigDecimal entry, BigDecimal stopLoss,
                                         BigDecimal takeProfit) {
        if (action == TradePlanType.OPEN_LONG) {
            return stopLoss.compareTo(entry) < 0 && takeProfit.compareTo(entry) > 0;
        }
        if (action == TradePlanType.OPEN_SHORT) {
            return stopLoss.compareTo(entry) > 0 && takeProfit.compareTo(entry) < 0;
        }
        return false;
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
        return account.equity();
    }

    private static BigDecimal usableBalance(AccountSummary account) {
        return account.availableBalance();
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

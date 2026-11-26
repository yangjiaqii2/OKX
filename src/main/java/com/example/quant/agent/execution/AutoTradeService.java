package com.example.quant.agent.execution;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.PositionSideMatcher;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetAllocationRequest;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.event.TradeEventPayload;
import com.example.quant.agent.event.TradeEventService;
import com.example.quant.agent.event.TradeEventType;
import com.example.quant.agent.gate.ContractTradeGate;
import com.example.quant.agent.selection.AiContractSelectionService;
import com.example.quant.agent.selection.ContractSelectionDecision;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.selection.V2SelectionScoreCalculator;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.market.MarketType;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.OrderSubmitStatusUnknownException;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.okxtrade.OkxCurrentOrderSyncService;
import com.example.quant.system.AutoTradeRiskMode;
import com.example.quant.system.SystemControlService;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AutoTradeService {
    private static final Logger log = LoggerFactory.getLogger(AutoTradeService.class);

    private final AgentProperties agentProperties;
    private final SystemControlService systemControlService;
    private final TradeOrderRecordService tradeOrderRecordService;
    private final AutoTradeRecordService autoTradeRecordService;
    private final TradePlanService tradePlanService;
    private final PendingOrderService pendingOrderService;
    private final com.example.quant.order.OrderConfirmService orderConfirmService;
    private final AccountSnapshotService accountSnapshotService;
    private final PositionSnapshotService positionSnapshotService;
    private final AutoTradeBudgetService budgetService;
    private final OkxCurrentOrderSyncService currentOrderSyncService;
    private final TradeEventService tradeEventService;
    private AiContractSelectionService aiContractSelectionService;
    private LiveMarketRefreshService liveMarketRefreshService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Instant> inFlightUntilByInstId = new ConcurrentHashMap<>();

    public AutoTradeService(
            AgentProperties agentProperties,
            SystemControlService systemControlService,
            TradeOrderRecordService tradeOrderRecordService,
            AutoTradeRecordService autoTradeRecordService,
            TradePlanService tradePlanService,
            PendingOrderService pendingOrderService,
            com.example.quant.order.OrderConfirmService orderConfirmService,
            AccountSnapshotService accountSnapshotService,
            PositionSnapshotService positionSnapshotService
    ) {
        this(agentProperties, systemControlService, tradeOrderRecordService, autoTradeRecordService,
                tradePlanService, pendingOrderService, orderConfirmService, accountSnapshotService,
                positionSnapshotService, new AutoTradeBudgetService(agentProperties), null, null);
    }

    public AutoTradeService(
            AgentProperties agentProperties,
            SystemControlService systemControlService,
            TradeOrderRecordService tradeOrderRecordService,
            AutoTradeRecordService autoTradeRecordService,
            TradePlanService tradePlanService,
            PendingOrderService pendingOrderService,
            com.example.quant.order.OrderConfirmService orderConfirmService,
            AccountSnapshotService accountSnapshotService,
            PositionSnapshotService positionSnapshotService,
            AutoTradeBudgetService budgetService
    ) {
        this(agentProperties, systemControlService, tradeOrderRecordService, autoTradeRecordService,
                tradePlanService, pendingOrderService, orderConfirmService, accountSnapshotService,
                positionSnapshotService, budgetService, null, null);
    }

    public AutoTradeService(
            AgentProperties agentProperties,
            SystemControlService systemControlService,
            TradeOrderRecordService tradeOrderRecordService,
            AutoTradeRecordService autoTradeRecordService,
            TradePlanService tradePlanService,
            PendingOrderService pendingOrderService,
            com.example.quant.order.OrderConfirmService orderConfirmService,
            AccountSnapshotService accountSnapshotService,
            PositionSnapshotService positionSnapshotService,
            AutoTradeBudgetService budgetService,
            OkxCurrentOrderSyncService currentOrderSyncService
    ) {
        this(agentProperties, systemControlService, tradeOrderRecordService, autoTradeRecordService,
                tradePlanService, pendingOrderService, orderConfirmService, accountSnapshotService,
                positionSnapshotService, budgetService, currentOrderSyncService, null);
    }

    @Autowired
    public AutoTradeService(
            AgentProperties agentProperties,
            SystemControlService systemControlService,
            TradeOrderRecordService tradeOrderRecordService,
            AutoTradeRecordService autoTradeRecordService,
            TradePlanService tradePlanService,
            PendingOrderService pendingOrderService,
            com.example.quant.order.OrderConfirmService orderConfirmService,
            AccountSnapshotService accountSnapshotService,
            PositionSnapshotService positionSnapshotService,
            AutoTradeBudgetService budgetService,
            OkxCurrentOrderSyncService currentOrderSyncService,
            TradeEventService tradeEventService
    ) {
        this.agentProperties = agentProperties;
        this.systemControlService = systemControlService;
        this.tradeOrderRecordService = tradeOrderRecordService;
        this.autoTradeRecordService = autoTradeRecordService;
        this.tradePlanService = tradePlanService;
        this.pendingOrderService = pendingOrderService;
        this.orderConfirmService = orderConfirmService;
        this.accountSnapshotService = accountSnapshotService;
        this.positionSnapshotService = positionSnapshotService;
        this.budgetService = budgetService == null ? new AutoTradeBudgetService(agentProperties) : budgetService;
        this.currentOrderSyncService = currentOrderSyncService;
        this.tradeEventService = tradeEventService;
    }

    @Autowired(required = false)
    public void configureAiContractSelection(AiContractSelectionService aiContractSelectionService) {
        this.aiContractSelectionService = aiContractSelectionService;
    }

    @Autowired
    public void configureLiveMarketRefresh(LiveMarketRefreshService liveMarketRefreshService) {
        this.liveMarketRefreshService = liveMarketRefreshService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.quant.risk.DailyRiskService dailyRisk;

    public AutoTradeResult evaluateAndExecute(List<ContractCandidate> candidates) {
        return evaluateAndExecute(candidates, Map.of(), Map.of());
    }

    public AutoTradeResult evaluateAndExecute(List<ContractCandidate> candidates,
                                              Map<String, String> decisionClientOrderIds) {
        return evaluateAndExecute(candidates, decisionClientOrderIds, Map.of());
    }

    public AutoTradeResult evaluateAndExecute(List<ContractCandidate> candidates,
                                              Map<String, String> decisionClientOrderIds,
                                              Map<String, V2SelectionScoreResult> v2ScoreSnapshot) {
        String ownerUsername = systemControlService.autoTradeOwnerUsername();
        if (ownerUsername != null && !ownerUsername.isBlank()) {
            return AuthUserContext.callAs(ownerUsername,
                    () -> evaluateAndExecuteInternal(candidates, decisionClientOrderIds, v2ScoreSnapshot));
        }
        return evaluateAndExecuteInternal(candidates, decisionClientOrderIds, v2ScoreSnapshot);
    }

    private AutoTradeResult evaluateAndExecuteInternal(List<ContractCandidate> candidates,
                                                       Map<String, String> decisionClientOrderIds,
                                                       Map<String, V2SelectionScoreResult> v2ScoreSnapshot) {
        List<ContractCandidate> safeCandidates = candidates == null ? List.of() : candidates;
        Map<String, V2SelectionScoreResult> safeV2Scores = v2ScoreSnapshot == null
                ? Map.of() : Map.copyOf(v2ScoreSnapshot);
        Map<String, String> stableDecisionIds = decisionClientOrderIds == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(decisionClientOrderIds);
        int candidateCount = safeCandidates.size();
        if (!agentProperties.autoTrade().enabled()) {
            return recordAndReturn(AutoTradeResult.skipped("auto_trade_disabled_by_config"), candidateCount, null);
        }
        if (!systemControlService.autoTradeEnabled()) {
            return recordAndReturn(AutoTradeResult.skipped("auto_trade_runtime_switch_off"), candidateCount, null);
        }
        boolean executionLockEnabled = agentProperties.concurrency().autoTradeLockEnabled();
        boolean executionLockAcquired = false;
        if (executionLockEnabled) {
            executionLockAcquired = running.compareAndSet(false, true);
            if (!executionLockAcquired) {
                log.warn("AutoTrade skipped: reason=AUTO_TRADE_EXECUTION_ALREADY_RUNNING");
                return recordAndReturn(AutoTradeResult.skipped("AUTO_TRADE_EXECUTION_ALREADY_RUNNING"),
                        candidateCount, topCandidate(safeCandidates, safeV2Scores).orElse(null));
            }
        }
        String scanId = UUID.randomUUID().toString();
        try {
            AutoTradeRiskMode riskMode = systemControlService.autoTradeRiskMode();
            boolean noRiskMode = riskMode != null && riskMode.noRisk();
            int noRiskMinScore = noRiskMinScore();
            if (noRiskMode) {
                return evaluateShadow(safeCandidates, candidateCount, scanId, noRiskMinScore, safeV2Scores);
            }
            pruneInFlightOrders();
            OkxCurrentOrderSyncService.SyncResult currentOrderSyncResult = syncCurrentOrders();
            if (currentOrderSyncResult.failed()) {
                ContractCandidate top = topCandidate(safeCandidates, safeV2Scores).orElse(null);
                log.warn("AutoTrade round stopped scanId={} stage=OKX_CURRENT_ORDER_SYNC reason={} fallback=false",
                        scanId, currentOrderSyncResult.errorMessage());
                return recordAndReturn(AutoTradeResult.skipped("okx_current_orders_unavailable: "
                        + currentOrderSyncResult.errorMessage()), candidateCount, top);
            }
            Optional<PendingOrder> unresolvedSubmit = pendingOrderService.allOrders().stream()
                    .filter(order -> order.status() == OrderStatus.SUBMITTING
                            || order.status() == OrderStatus.UNKNOWN_SUBMIT_STATUS)
                    .findFirst();
            if (unresolvedSubmit.isPresent()) {
                PendingOrder unresolved = unresolvedSubmit.get();
                log.warn("AutoTrade round stopped scanId={} stage=LOCAL_SUBMIT_RECONCILIATION pendingOrderId={} symbol={} status={} clOrdId={}",
                        scanId, unresolved.id(), unresolved.instId(), unresolved.status(), unresolved.clientOrderId());
                return recordAndReturn(AutoTradeResult.skipped("unresolved_local_submit: pendingOrderId="
                        + unresolved.id() + ", status=" + unresolved.status()), candidateCount,
                        topCandidate(safeCandidates, safeV2Scores).orElse(null));
            }
            Set<String> localActiveEntryInstIds = tradeOrderRecordService.activeEntryInstIds();
            List<PositionSummary> positions;
            try {
                positions = new ArrayList<>(activePositions());
            } catch (RuntimeException ex) {
                ContractCandidate top = topCandidate(safeCandidates, safeV2Scores).orElse(null);
                log.warn("AutoTrade round stopped scanId={} stage=POSITION_SNAPSHOT reason={} fallback=false",
                        scanId, ex.getMessage());
                return recordAndReturn(AutoTradeResult.skipped("position_snapshot_unavailable: " + ex.getMessage()),
                        candidateCount, top);
            }
            Set<String> heldInstIds = positions.stream()
                    .map(PositionSummary::instId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> exposureInstIds = new LinkedHashSet<>(heldInstIds);
            mergePendingExposures(positions, exposureInstIds, tradeOrderRecordService.activeEntryExposures());
            mergePendingExposures(positions, exposureInstIds, currentOrderSyncResult.activeEntryExposures());
            Set<String> okxCurrentOrderInstIds = currentOrderSyncResult.activeInstIds().stream()
                    .filter(instId -> instId != null && !instId.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            PositionQualityPolicy positionQualityPolicy = new PositionQualityPolicy(agentProperties);
            CorrelationExposureGuard exposureGuard = new CorrelationExposureGuard(agentProperties);
            FailureReasonClassifier failureReasonClassifier = new FailureReasonClassifier(agentProperties);
            PreConfirmRefreshService preConfirmRefreshService = new PreConfirmRefreshService(agentProperties);
            ContractCandidate topForPolicy = topCandidate(safeCandidates, safeV2Scores).orElse(null);
            String marketRegime = positionQualityPolicy.marketRegimeFor(topForPolicy);
            int dynamicMaxPositions = topForPolicy == null
                    ? maxOpenPositions()
                    : positionQualityPolicy.allowedMaxPositionsFor(topForPolicy);
            Set<String> openOrPendingInstIds = new LinkedHashSet<>(heldInstIds);
            openOrPendingInstIds.addAll(okxCurrentOrderInstIds);
            openOrPendingInstIds.addAll(localActiveEntryInstIds);
            int openOrPendingCount = openOrPendingInstIds.size();
            int initialOpenOrPendingCount = openOrPendingCount;
            int initialInFlightCount = Math.max(0, openOrPendingCount - heldInstIds.size());
            int capacity = dynamicMaxPositions - openOrPendingCount;
            log.warn("AutoTrade round start scanId={} held={} okxCurrentOrders={} localActiveEntries={} inFlight={} candidateCount={} marketRegime={} dynamicMaxPositions={} minScore={} minFinalRankScore={} riskMode={}",
                    scanId, heldInstIds.size(), okxCurrentOrderInstIds.size(), localActiveEntryInstIds.size(),
                    symbolLockCount(), candidateCount,
                    marketRegime, dynamicMaxPositions,
                    positionQualityPolicy.minScoreFor(openOrPendingCount, topForPolicy),
                    positionQualityPolicy.minFinalRankScoreFor(openOrPendingCount, topForPolicy),
                    riskMode);
            if (capacity <= 0) {
                ContractCandidate top = topCandidate(safeCandidates, safeV2Scores).orElse(null);
                log.warn("AutoTrade round stopped scanId={} stage=POSITION_CAPACITY reason=capacity_full fallback=false", scanId);
                return recordAndReturn(AutoTradeResult.skipped("position_capacity_full: held="
                                + heldInstIds.size() + ", okxCurrentOrders=" + okxCurrentOrderInstIds.size()
                                + ", inFlight=" + symbolLockCount()
                                + ", dynamicMax=" + dynamicMaxPositions
                                + ", configuredMax=" + maxOpenPositions()
                                + ", marketRegime=" + marketRegime),
                        candidateCount, top);
            }
            int refillNeeded = Math.min(targetOpenPositions(), dynamicMaxPositions) - openOrPendingCount;
            if (refillNeeded <= 0) {
                ContractCandidate top = topCandidate(safeCandidates, safeV2Scores).orElse(null);
                log.warn("AutoTrade round stopped scanId={} stage=POSITION_TARGET reason=target_satisfied fallback=false", scanId);
                return recordAndReturn(AutoTradeResult.skipped("position_target_satisfied: held="
                                + heldInstIds.size() + ", okxCurrentOrders=" + okxCurrentOrderInstIds.size()
                                + ", inFlight=" + symbolLockCount()
                                + ", target=" + targetOpenPositions()),
                        candidateCount, top);
            }
            int targetExecutions = Math.min(capacity, refillNeeded);
            Optional<AccountSummary> accountAtRoundStart = accountFunds();
            Optional<BigDecimal> accountAvailableAtRoundStart = accountAtRoundStart
                    .map(AccountSummary::availableBalance).map(value -> value.max(BigDecimal.ZERO));
            if (accountAvailableAtRoundStart.isEmpty() || accountAvailableAtRoundStart.orElseThrow().signum() <= 0) {
                AccountSummary account = accountAtRoundStart.orElse(null);
                log.warn("AutoTrade account gate blocked scanId={} reason=account_available_balance_unavailable mode={} equity={} availableBalance={} message={}",
                        scanId,
                        account == null ? "NONE" : account.mode(),
                        account == null ? null : account.equity(),
                        account == null ? null : account.availableBalance(),
                        account == null ? "ACCOUNT_SNAPSHOT_EMPTY" : account.message());
                return recordAndReturn(AutoTradeResult.skipped("account_available_balance_unavailable"),
                        candidateCount, topCandidate(safeCandidates, safeV2Scores).orElse(null));
            }
            if (dailyRisk != null) {
                var decision = dailyRisk.checkRound(currentOwner(), effectiveEquity(accountAtRoundStart.orElseThrow()), Instant.now());
                if (!decision.allowed()) return recordAndReturn(AutoTradeResult.skipped(decision.reason()), candidateCount, null);
            }
            BigDecimal totalBudgetUsdt = autoTradeTotalBudgetUsdt(accountAvailableAtRoundStart.orElseThrow());
            if (totalBudgetUsdt == null || totalBudgetUsdt.signum() <= 0) {
                return recordAndReturn(AutoTradeResult.skipped("auto_trade_total_budget_not_configured"), candidateCount, null);
            }
            BigDecimal accountSingleMarginCap = effectiveEquity(accountAtRoundStart.orElseThrow())
                    .multiply(percentRate(agentProperties.risk().maxSingleMarginPercent()));
            // 规则回退路径的尝试名单。分数职责：
            //   - 上游 Scanner 同一扫描的 V2 score snapshot 负责入围及组内机会排序；
            //   - readiness 只划分当前可尝试资格，组内仍沿用 V2 顺序；V1 仅在兼容调用缺少 V2 snapshot 时兜底；
            //   - 所有硬门控（news / 仓位质量 / 敞口 / autoGate / refresh / 最终风险检查）在逐个候选上原位生效。
            // 排在最前的是"当前自动交易门控已经通过"的候选：尝试名额有限
            // （maxAutoTradeFallbackRank），不能让已知不可交易的高分候选把它耗光。
            List<ContractCandidate> orderedCandidates = safeCandidates.stream()
                    .sorted(Comparator.comparing((ContractCandidate candidate) -> autoTradeReady(candidate) ? 1 : 0)
                            .reversed()
                            .thenComparing((left, right) -> V2SelectionScoreCalculator.compareForRanking(
                                    left, right, safeV2Scores)))
                    .limit(Math.max(1, agentProperties.ranking().maxAutoTradeFallbackRank()))
                    .toList();
            List<ContractCandidate> aiPool = aiEligibleCandidates(safeCandidates, safeV2Scores);
            ContractSelectionDecision selection = aiContractSelectionService == null
                    ? null
                    : safeV2Scores.isEmpty()
                    ? aiContractSelectionService.select(aiPool)
                    : aiContractSelectionService.select(aiPool, safeV2Scores);
            if (selection != null && selection.source() == ContractSelectionDecision.DecisionSource.AI_WAIT) {
                ContractCandidate selected = safeCandidates.stream()
                        .filter(candidate -> candidate.instId().equals(selection.selectedInstId()))
                        .findFirst().orElseGet(() -> topCandidate(safeCandidates, safeV2Scores).orElse(null));
                return recordAndReturn(AutoTradeResult.skipped("AI_WAIT_SKIPPED"), candidateCount, selected, selection);
            }
            if (selection != null && selection.source() == ContractSelectionDecision.DecisionSource.AI_PRIMARY) {
                orderedCandidates = aiOrderedCandidates(safeCandidates, selection);
            }
            List<AutoTradeResult> executedResults = new ArrayList<>();
            Set<String> attemptedSymbols = new LinkedHashSet<>();
            Map<String, String> skipReasonsBySymbol = new LinkedHashMap<>();
            for (ContractCandidate candidate : orderedCandidates) {
                if (executedResults.size() >= targetExecutions) {
                    break;
                }
                if (!attemptedSymbols.add(candidate.instId())) {
                    log.warn("AutoTrade candidate skipped scanId={} symbol={} reason=DUPLICATE_ROUND_CANDIDATE classification={} fallback=true",
                            scanId, candidate.instId(), FailureClassification.NEXT_CANDIDATE_ALLOWED);
                    continue;
                }
                if (heldInstIds.contains(candidate.instId())
                        || okxCurrentOrderInstIds.contains(candidate.instId())
                        || localActiveEntryInstIds.contains(candidate.instId())
                        || (agentProperties.concurrency().symbolLockEnabled()
                        && inFlightUntilByInstId.containsKey(candidate.instId()))) {
                    String reason = localActiveEntryInstIds.contains(candidate.instId())
                            ? "local_active_entry_order:" + candidate.instId()
                            : okxCurrentOrderInstIds.contains(candidate.instId())
                            ? "okx_current_active_order"
                            : heldInstIds.contains(candidate.instId()) ? "SYMBOL_ALREADY_HELD"
                            : "SYMBOL_ENTRY_LOCKED";
                    skipReasonsBySymbol.put(candidate.instId(), reason);
                    recordCandidateSkipped(candidate, reason);
                    log.warn("AutoTrade candidate skipped scanId={} symbol={} reason={} classification={} fallback=true",
                            scanId, candidate.instId(), reason,
                            FailureClassification.NEXT_CANDIDATE_ALLOWED);
                    continue;
                }
                boolean aiPrimaryCandidate = selection != null
                        && selection.source() == ContractSelectionDecision.DecisionSource.AI_PRIMARY
                        && selection.rankedCandidates().stream().anyMatch(item -> item.instId().equals(candidate.instId()));
                String hardNewsBlockReason = hardNewsBlockReason(candidate);
                if (hardNewsBlockReason != null) {
                    skipReasonsBySymbol.put(candidate.instId(), hardNewsBlockReason);
                    recordCandidateSkipped(candidate, hardNewsBlockReason);
                    log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=NEWS_RISK reason={} classification={} fallback=true",
                            scanId, candidate.instId(), hardNewsBlockReason, FailureClassification.NEXT_CANDIDATE_ALLOWED);
                    continue;
                }
                PositionQualityPolicy.PositionQualityDecision qualityDecision = positionQualityPolicy.evaluate(
                        positions,
                        Math.max(0, openOrPendingInstIds.size() - positions.size()),
                        candidate
                );
                if (!qualityDecision.allowed() && !aiPrimaryCandidate) {
                    skipReasonsBySymbol.put(candidate.instId(), qualityDecision.reason());
                    recordCandidateSkipped(candidate, qualityDecision.reason());
                    log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=POSITION_QUALITY reason={} classification={} fallback=true",
                            scanId, candidate.instId(), qualityDecision.reason(), FailureClassification.NEXT_CANDIDATE_ALLOWED);
                    continue;
                }
                CorrelationExposureGuard.ExposureDecision exposureDecision = exposureGuard.evaluate(
                        positions, candidate, aiPrimaryCandidate ? selectionFor(candidate, selection).direction() : candidate.trendDirection());
                if (!exposureDecision.allowed()) {
                    skipReasonsBySymbol.put(candidate.instId(), exposureDecision.reason());
                    recordCandidateSkipped(candidate, exposureDecision.reason());
                    log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=EXPOSURE reason={} classification={} fallback=true",
                            scanId, candidate.instId(), exposureDecision.reason(), FailureClassification.NEXT_CANDIDATE_ALLOWED);
                    continue;
                }
                List<String> autoGateReasons = aiPrimaryCandidate ? aiHardGate(candidate) : autoGate(candidate);
                if (!autoGateReasons.isEmpty()) {
                    String reason = String.join(",", autoGateReasons);
                    skipReasonsBySymbol.put(candidate.instId(), reason);
                    recordCandidateSkipped(candidate, reason);
                    continue;
                }
                markInFlight(candidate.instId());
                TradePlan plan = null;
                PendingOrder order = null;
                OrderExecutionResult result;
                BudgetReservation reservation = null;
                BigDecimal orderMarginUsdt = BigDecimal.ZERO;
                try {
                    ContractCandidate planningCandidate = candidate;
                    if (liveMarketRefreshService != null) {
                        Instant jevDecisionTimestamp = Instant.now();
                        LiveMarketRefreshResult liveRefresh = liveMarketRefreshService.refresh(candidate,
                                jevDecisionTimestamp);
                        if (!liveRefresh.passed()) {
                            String reason = String.join(",", liveRefresh.reasons());
                            skipReasonsBySymbol.put(candidate.instId(), reason);
                            recordCandidateSkipped(candidate, reason);
                            inFlightUntilByInstId.remove(candidate.instId());
                            log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=LIVE_MARKET_REFRESH reason={} classification={} fallback=true",
                                    scanId, candidate.instId(), reason, FailureClassification.NEXT_CANDIDATE_ALLOWED);
                            continue;
                        }
                        planningCandidate = liveRefresh.candidate();
                    }
                    plan = aiPrimaryCandidate
                            ? tradePlanService.createSelectedContractPlan(planningCandidate, selectionFor(candidate, selection))
                            : selection != null
                                    && selection.source() == ContractSelectionDecision.DecisionSource.RULE_FALLBACK
                                    ? tradePlanService.createRuleFallbackPlan(planningCandidate)
                                    : tradePlanService.createContractPlan(planningCandidate);
                    String planRejection = plan.rejectionReason();
                    recordEvent(candidate, null, plan, TradeEventType.PLAN_CREATED, null, null,
                            planRejection == null ? "PLAN_CREATED" : "PLAN_NOT_EXECUTABLE",
                            planRejection == null ? "自动交易计划已生成" : planRejection, null);
                    if (planRejection != null) {
                        String reason = "PLAN_NOT_EXECUTABLE: " + planRejection;
                        skipReasonsBySymbol.put(candidate.instId(), reason);
                        recordCandidateSkipped(candidate, reason);
                        inFlightUntilByInstId.remove(candidate.instId());
                        log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=PLAN reason={} classification={} fallback=true",
                                scanId, candidate.instId(), reason, FailureClassification.NEXT_CANDIDATE_ALLOWED);
                        continue;
                    }
                    PreConfirmRefreshResult refreshResult = preConfirmRefreshService.check(planningCandidate, plan);
                    if (!refreshResult.passed()) {
                        String reason = String.join(",", refreshResult.reasons());
                        skipReasonsBySymbol.put(candidate.instId(), reason);
                        recordCandidateSkipped(candidate, reason);
                        inFlightUntilByInstId.remove(candidate.instId());
                        log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=PRE_CONFIRM_REFRESH reason={} classification={} fallback=true",
                                scanId, candidate.instId(), reason, FailureClassification.NEXT_CANDIDATE_ALLOWED);
                        continue;
                    }
                    int slotIndex = initialOpenOrPendingCount + executedResults.size() + 1;
                    BigDecimal allocationScore = scoreForBudget(candidate);
                    BigDecimal maxMarginForCandidate = plan.suggestedMargin();
                    if (maxMarginForCandidate == null || maxMarginForCandidate.signum() <= 0) {
                        String reason = "PLAN_RISK_MARGIN_UNAVAILABLE";
                        skipReasonsBySymbol.put(candidate.instId(), reason);
                        recordCandidateSkipped(candidate, reason);
                        inFlightUntilByInstId.remove(candidate.instId());
                        continue;
                    }
                    BigDecimal availableForThisRound = accountAvailableAtRoundStart.orElseThrow()
                            .subtract(executedMarginAmount(executedResults))
                            .max(BigDecimal.ZERO);
                    maxMarginForCandidate = min(min(maxMarginForCandidate, availableForThisRound),
                            accountSingleMarginCap);
                    if (plan.executionPreview() == null) {
                        // Preserve sizing limits for callers supplying a plan without an execution preview.
                        BigDecimal costRate = percentRate(stopLossPct(plan)
                                .add(agentProperties.profitProtection().roundTripFeePct())
                                .add(agentProperties.profitProtection().estimatedSlippagePct()))
                                .add(candidate.fundingRate() == null
                                        ? agentProperties.market().maxFundingAbs() : candidate.fundingRate().abs());
                        BigDecimal riskBudget = effectiveEquity(accountAtRoundStart.orElseThrow())
                                .multiply(percentRate(agentProperties.risk().singleTradeRiskPercent()));
                        if (costRate.signum() > 0 && plan.suggestedLeverage() > 0) {
                            maxMarginForCandidate = maxMarginForCandidate.min(riskBudget.divide(
                                    costRate.multiply(BigDecimal.valueOf(plan.suggestedLeverage())), 8, RoundingMode.DOWN));
                        }
                    }
                    BudgetAllocation allocation = budgetService.allocate(new BudgetAllocationRequest(
                            totalBudgetUsdt,
                            budgetService.usedBudget(),
                            budgetService.reservedBudget(),
                            slotIndex,
                            allocationScore,
                            maxMarginForCandidate,
                            stopLossPct(plan),
                            candidate.newsAnalysis().newsRiskLevel(),
                            positionQualityPolicy.marketRegimeFor(candidate),
                            candidate.fundingRate(),
                            plan.executionPreview() == null ? plan.riskRewardRatio()
                                    : plan.executionPreview().risk().estimatedNetRiskRewardRatio()
                    ));
                    orderMarginUsdt = allocation.finalOrderMarginUsdt();
                    if (orderMarginUsdt.compareTo(agentProperties.budget().minOrderMarginUsdt()) < 0) {
                        String reason = "CANDIDATE_BUDGET_ALLOCATION_TOO_LOW_" + orderMarginUsdt;
                        skipReasonsBySymbol.put(candidate.instId(), reason);
                        recordCandidateSkipped(candidate, reason);
                        inFlightUntilByInstId.remove(candidate.instId());
                        log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=BUDGET_ALLOCATION reason={} classification={} fallback=true totalBudget={} usedBudget={} reservedBudget={} remainingBudget={} slotIndex={} slotBudget={} accountAvailableRoundStart={} maxMarginForCandidate={}",
                                scanId, candidate.instId(), reason, FailureClassification.NEXT_CANDIDATE_ALLOWED,
                                allocation.totalBudgetUsdt(), allocation.usedBudgetBefore(),
                                allocation.inFlightReservedBefore(), allocation.remainingBudgetBefore(),
                                allocation.slotIndex(), allocation.slotBudgetUsdt(),
                                accountAvailableAtRoundStart.map(BigDecimal::stripTrailingZeros).map(BigDecimal::toPlainString).orElse("UNKNOWN"),
                                maxMarginForCandidate.stripTrailingZeros().toPlainString());
                        continue;
                    }
                    UUID pendingOrderId = UUID.randomUUID();
                    reservation = budgetService.reserveBudget(plan.id(), pendingOrderId, candidate.instId(),
                            orderMarginUsdt, totalBudgetUsdt);
                    if (!reservation.reserved()) {
                        String reason = reservation.reason();
                        skipReasonsBySymbol.put(candidate.instId(), reason);
                        recordCandidateSkipped(candidate, reason);
                        inFlightUntilByInstId.remove(candidate.instId());
                        FailureClassification classification = failureReasonClassifier.classify("BUDGET_RESERVE", reason);
                        log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=BUDGET_RESERVE reason={} classification={} fallback={}",
                                scanId, candidate.instId(), reason, classification,
                                classification == FailureClassification.NEXT_CANDIDATE_ALLOWED);
                        if (classification == FailureClassification.NEXT_CANDIDATE_ALLOWED) {
                            continue;
                        }
                        return recordAndReturn(AutoTradeResult.skipped(reason), candidateCount, candidate);
                    }
                    log.warn("AutoTrade budget allocation: totalBudget={}, targetUsedBudget={}, minTargetUsedBudget={}, usedBudget={}, inFlightReserved={}, remainingBudget={}, slotIndex={}, slotWeight={}, slotBudget={}, score={}, scoreFactor={}, qualityAdjustedBudget={}, riskBasedMaxMargin={}, maxSinglePositionBudget={}, finalOrderMargin={}, budgetUtilizationAfter={}, symbol={}, planId={}, pendingOrderId={}",
                            allocation.totalBudgetUsdt(), allocation.targetUsedBudgetUsdt(), allocation.minTargetUsedBudgetUsdt(),
                            allocation.usedBudgetBefore(), allocation.inFlightReservedBefore(), allocation.remainingBudgetBefore(),
                            allocation.slotIndex(), allocation.slotWeight(), allocation.slotBudgetUsdt(), allocationScore,
                            allocation.scoreFactor(), allocation.qualityAdjustedBudgetUsdt(), allocation.riskBasedMaxMarginUsdt(),
                            allocation.maxSinglePositionBudgetUsdt(), allocation.finalOrderMarginUsdt(),
                            allocation.budgetUtilizationAfter(), candidate.instId(), plan.id(), pendingOrderId);
                    order = pendingOrderService.createAutoPendingOrder(MarketType.OKX_SWAP, plan, pendingOrderId,
                            orderMarginUsdt, reservation.reservationId(), allocation,
                            stableDecisionIds.getOrDefault(candidate.instId(),
                                    clientOrderId(agentProperties.idempotency().clientOrderIdPrefix(), plan.id(), pendingOrderId)));
                    recordEvent(candidate, order, plan, TradeEventType.BUDGET_RESERVED, null,
                            "BUDGET_RESERVED", "BUDGET_RESERVED", "自动交易预算已预留", null);
                    result = orderConfirmService.confirmAuto(order.id(), orderMarginUsdt);
                } catch (OrderSubmitStatusUnknownException ex) {
                    inFlightUntilByInstId.remove(candidate.instId());
                    recordEvent(candidate, order, plan, TradeEventType.ENTRY_SUBMIT_UNKNOWN,
                            "SUBMITTING", "UNKNOWN_SUBMIT_STATUS", "ENTRY_SUBMIT_UNKNOWN", ex.getMessage(), null);
                    log.warn("AutoTrade submit status unknown scanId={} symbol={} stage=PLAN_OR_CONFIRM reason={} fallback=false",
                            scanId, candidate.instId(), ex.getMessage());
                    AutoTradeResult unknown = new AutoTradeResult(
                            "UNKNOWN_SUBMIT_STATUS",
                            order == null ? candidate.instId() : order.instId(),
                            plan == null || plan.id() == null ? null : plan.id().toString(),
                            order == null ? null : order.id().toString(),
                            null,
                            plan == null || plan.action() == null ? null : plan.action().name(),
                            order == null ? null : order.posSide(),
                            order == null ? null : order.leverage(),
                            orderMarginUsdt,
                            plan == null ? null : plan.entryPrice(),
                            ex.getMessage(),
                            Instant.now()
                    );
                    recordExecution(unknown, candidateCount, candidate, selection);
                    return unknown;
                } catch (org.springframework.dao.OptimisticLockingFailureException stale) {
                    return recordAndReturn(AutoTradeResult.failed("ORDER_STATE_CHANGED_RECONCILIATION_REQUIRED"),
                            candidateCount, candidate, selection);
                } catch (RuntimeException ex) {
                    inFlightUntilByInstId.remove(candidate.instId());
                    if (reservation != null) {
                        budgetService.release(reservation.reservationId(), "PLAN_OR_CONFIRM_FAILED");
                        recordEvent(candidate, order, plan, TradeEventType.BUDGET_RELEASED,
                                "RESERVED", "RELEASED", "PLAN_OR_CONFIRM_FAILED", ex.getMessage(), null);
                    }
                    FailureClassification classification = failureReasonClassifier.classify("PLAN_OR_CONFIRM", ex.getMessage());
                    log.warn("AutoTrade attempt failed scanId={} symbol={} stage=PLAN_OR_CONFIRM reason={} classification={} fallback={}",
                            scanId, candidate.instId(), ex.getMessage(), classification,
                            classification == FailureClassification.NEXT_CANDIDATE_ALLOWED);
                    if (classification == FailureClassification.NEXT_CANDIDATE_ALLOWED) {
                        skipReasonsBySymbol.put(candidate.instId(), ex.getMessage());
                        continue;
                    }
                    return recordAndReturn(AutoTradeResult.failed(ex.getMessage()), candidateCount, candidate, selection);
                }
                if (result.submitted()) {
                    if (reservation != null && result.filled()) {
                        budgetService.markUsed(reservation.reservationId());
                    }
                    String submittedStatus = result.filled() ? "EXECUTED" : "ENTRY_SUBMITTED";
                    String submittedNewStatus = result.filled() ? "ENTRY_FILLED" : "SUBMITTED";
                    recordEvent(candidate, order, plan, TradeEventType.ENTRY_SUBMITTED,
                            "SUBMITTING", submittedNewStatus, "ENTRY_SUBMITTED", result.message(), result.externalOrderId());
                    log.warn("Auto trade submitted scanId={} instId={} tradePlanId={} pendingOrderId={} okxOrdId={}",
                            scanId, plan.instId(), plan.id(), order.id(), result.externalOrderId());
                    AutoTradeResult executed = new AutoTradeResult(submittedStatus, plan.instId(), plan.id().toString(),
                            order.id().toString(), result.externalOrderId(), plan.action().name(), order.posSide(),
                            order.leverage(), order.marginAmount(), plan.entryPrice(), result.message(), Instant.now());
                    recordExecution(executed, candidateCount, candidate, selection);
                    executedResults.add(executed);
                    heldInstIds.add(candidate.instId());
                    openOrPendingInstIds.add(candidate.instId());
                    positions.add(committedExposurePosition(candidate, plan, order));
                    continue;
                }
                log.warn("Auto trade rejected after plan scanId={} instId={} tradePlanId={} reason={}",
                        scanId, plan.instId(), plan.id(), result.message());
                inFlightUntilByInstId.remove(candidate.instId());
                if (reservation != null) {
                    budgetService.release(reservation.reservationId(), "CONFIRM_REJECTED");
                    recordEvent(candidate, order, plan, TradeEventType.BUDGET_RELEASED,
                            "RESERVED", "RELEASED", "CONFIRM_REJECTED", result.message(), null);
                }
                FailureClassification classification = failureReasonClassifier.classify("CONFIRM", result.message());
                log.warn("AutoTrade attempt failed scanId={} symbol={} stage=CONFIRM reason={} classification={} fallback={}",
                        scanId, candidate.instId(), result.message(), classification,
                        classification == FailureClassification.NEXT_CANDIDATE_ALLOWED);
                if (classification == FailureClassification.NEXT_CANDIDATE_ALLOWED) {
                    skipReasonsBySymbol.put(candidate.instId(), result.message());
                    continue;
                }
                AutoTradeResult rejected = new AutoTradeResult("REJECTED", plan.instId(), plan.id().toString(),
                        order.id().toString(), null, plan.action().name(), order.posSide(), order.leverage(),
                        orderMarginUsdt, plan.entryPrice(), result.message(), Instant.now());
                if (executedResults.isEmpty()) {
                    return recordAndReturn(rejected, candidateCount, candidate, selection);
                }
                return aggregateExecutedResults(executedResults,
                        "auto_refill_submitted=" + executedResults.size() + ", stopped_by_rejection=" + result.message());
            }
            if (executedResults.isEmpty()) {
                ContractCandidate top = topCandidate(safeCandidates, safeV2Scores).orElse(null);
                boolean aiPrimary = selection != null
                        && selection.source() == ContractSelectionDecision.DecisionSource.AI_PRIMARY;
                ContractCandidate recordedCandidate = aiPrimary
                        ? safeCandidates.stream()
                                .filter(candidate -> selection.selectedInstId().equals(candidate.instId()))
                                .findFirst().orElse(top)
                        : top;
                String message = aiPrimary ? "no_order_submitted" : "no_candidate_passed_auto_gate";
                if (recordedCandidate != null) {
                    Set<String> reasons = new LinkedHashSet<>();
                    String attemptReason = skipReasonsBySymbol.get(recordedCandidate.instId());
                    if (attemptReason != null && !attemptReason.isBlank()) {
                        reasons.add(attemptReason);
                    }
                    if (!aiPrimary) {
                        List<String> gateReasons = autoGate(recordedCandidate);
                        if (!gateReasons.isEmpty()) {
                            reasons.add(String.join(",", gateReasons));
                        }
                    }
                    if (!reasons.isEmpty()) {
                        message = message + ": " + String.join(": ", reasons);
                    }
                }
                return recordAndReturn(AutoTradeResult.skipped(message), candidateCount, recordedCandidate, selection);
            }
            if (executedResults.size() == 1) {
                return executedResults.get(0);
            }
            return aggregateExecutedResults(executedResults, "auto_refill_submitted=" + executedResults.size());
        } catch (RuntimeException ex) {
            log.warn("Auto trade failed: {}", ex.getMessage(), ex);
            return recordAndReturn(AutoTradeResult.failed(ex.getMessage()), candidateCount, null);
        } finally {
            if (executionLockEnabled && executionLockAcquired) {
                running.set(false);
            }
        }
    }

    private AutoTradeResult recordAndReturn(AutoTradeResult result, int candidateCount, ContractCandidate candidate) {
        recordExecution(result, candidateCount, candidate);
        return result;
    }

    private AutoTradeResult recordAndReturn(AutoTradeResult result, int candidateCount, ContractCandidate candidate,
                                            ContractSelectionDecision selection) {
        recordExecution(result, candidateCount, candidate, selection);
        return result;
    }

    private AutoTradeResult evaluateShadow(List<ContractCandidate> candidates, int candidateCount,
                                           String scanId, int minScore, Map<String, V2SelectionScoreResult> scores) {
        int recorded = 0;
        ContractCandidate lastRecorded = null;
        String lastSkipReason = null;
        for (ContractCandidate candidate : candidates.stream()
                .sorted(scores.isEmpty() ? Comparator.comparing(AutoTradeService::displayedScore).reversed()
                        : (left, right) -> V2SelectionScoreCalculator.compareForRanking(left, right, scores))
                .limit(Math.max(1, agentProperties.ranking().maxAutoTradeFallbackRank()))
                .toList()) {
            String newsBlock = hardNewsBlockReason(candidate);
            if (newsBlock != null || displayedScore(candidate).compareTo(BigDecimal.valueOf(minScore)) < 0) {
                lastSkipReason = newsBlock == null ? "shadow_score_below_" + minScore : newsBlock;
                recordCandidateSkipped(candidate, lastSkipReason);
                continue;
            }
            TradePlan plan = tradePlanService.createNoRiskContractPlan(candidate);
            recordEvent(candidate, null, plan, TradeEventType.PLAN_CREATED, null, "SHADOW_RECORDED",
                    "SHADOW_RECORDED", "Shadow 观察已持久化；未创建订单", null);
            recorded++;
            lastRecorded = candidate;
        }
        String message = recorded == 0
                ? "shadow_no_candidate_qualified" + (lastSkipReason == null ? "" : ": " + lastSkipReason)
                : "shadow_observation_recorded=" + recorded;
        log.info("AutoTrade Shadow round completed scanId={} candidateCount={} recorded={} minScore={}",
                scanId, candidateCount, recorded, minScore);
        return recordAndReturn(AutoTradeResult.skipped(message), candidateCount,
                lastRecorded == null ? topCandidate(candidates, scores).orElse(null) : lastRecorded);
    }

    private void recordExecution(AutoTradeResult result, int candidateCount, ContractCandidate candidate) {
        autoTradeRecordService.record(result, candidateCount, candidate);
    }

    private void recordExecution(AutoTradeResult result, int candidateCount, ContractCandidate candidate,
                                 ContractSelectionDecision selection) {
        if (selection == null) {
            autoTradeRecordService.record(result, candidateCount, candidate);
            return;
        }
        autoTradeRecordService.record(result, candidateCount, candidate, selection);
    }

    private void recordCandidateSkipped(ContractCandidate candidate, String reason) {
        recordEvent(candidate, null, null, TradeEventType.AUTO_CANDIDATE_SKIPPED, null, null,
                "AUTO_CANDIDATE_SKIPPED", reason, null);
    }

    private void recordEvent(ContractCandidate candidate, PendingOrder order, TradePlan plan, TradeEventType type,
                             String oldStatus, String newStatus, String reasonCode, String reasonMessage,
                             String okxOrdId) {
        if (tradeEventService == null || type == null) {
            return;
        }
        try {
            tradeEventService.record(new TradeEventPayload(
                    null,
                    order == null ? candidate == null ? null : candidate.instId() : order.instId(),
                    order == null ? null : order.id().toString(),
                    null,
                    null,
                    type,
                    oldStatus,
                    newStatus,
                    reasonCode,
                    reasonMessage,
                    okxOrdId,
                    order == null ? null : order.clientOrderId(),
                    null
            ));
        } catch (RuntimeException ex) {
            log.warn("Failed to write trade event type={} instId={} message={}",
                    type, candidate == null ? null : candidate.instId(), ex.getMessage());
        }
    }

    private AutoTradeResult aggregateExecutedResults(List<AutoTradeResult> executedResults, String message) {
        AutoTradeResult last = executedResults.get(executedResults.size() - 1);
        BigDecimal totalMargin = executedResults.stream()
                .map(AutoTradeResult::marginAmount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String aggregateStatus = executedResults.stream().allMatch(item -> "EXECUTED".equals(item.status()))
                ? "EXECUTED"
                : "ENTRY_SUBMITTED";
        return new AutoTradeResult(aggregateStatus, last.instId(), last.tradePlanId(), last.pendingOrderId(),
                last.okxOrderId(), last.action(), last.posSide(), last.leverage(), totalMargin,
                last.entryPrice(), message, Instant.now());
    }

    private BigDecimal autoTradeTotalBudgetUsdt(BigDecimal availableBalance) {
        return systemControlService.resolveAutoTradeTotalBudget(availableBalance);
    }

    private Optional<AccountSummary> accountFunds() {
        try {
            AccountSummary account = accountSnapshotService.summary();
            if (account == null || account.availableBalance() == null) {
                log.warn("AutoTrade account snapshot unavailable reason=NULL_AVAILABLE_BALANCE accountPresent={} mode={} equity={} availableBalance={} message={}",
                        account != null,
                        account == null ? "NONE" : account.mode(),
                        account == null ? null : account.equity(),
                        account == null ? null : account.availableBalance(),
                        account == null ? "ACCOUNT_SNAPSHOT_EMPTY" : account.message());
                return Optional.empty();
            }
            return Optional.of(account);
        } catch (RuntimeException ex) {
            log.warn("AutoTrade account snapshot unavailable reason=READ_EXCEPTION errorType={} message={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    private static BigDecimal effectiveEquity(AccountSummary account) {
        return account.equity() != null && account.equity().signum() > 0
                ? account.equity() : account.availableBalance().max(BigDecimal.ZERO);
    }

    private static BigDecimal percentRate(BigDecimal percent) {
        return percent == null ? BigDecimal.ZERO
                : percent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal stopLossPct(TradePlan plan) {
        if (plan.entryPrice() == null || plan.entryPrice().signum() <= 0 || plan.stopLossPrice() == null) {
            return BigDecimal.ZERO;
        }
        return plan.entryPrice().subtract(plan.stopLossPrice()).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(plan.entryPrice(), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal scoreForBudget(ContractCandidate candidate) {
        return candidate.finalRankScore() == null
                ? BigDecimal.valueOf(candidate.score())
                : candidate.finalRankScore();
    }

    private static BigDecimal displayedScore(ContractCandidate candidate) {
        return BigDecimal.valueOf(candidate.score());
    }

    private String hardNewsBlockReason(ContractCandidate candidate) {
        return ContractTradeGate.newsDenyReasons(candidate, agentProperties).stream().findFirst().orElse(null);
    }

    private int noRiskMinScore() {
        return Math.max(60, Math.min(100, systemControlService.noRiskMinScore()));
    }

    private static String clientOrderId(String configuredPrefix, UUID planId, UUID pendingOrderId) {
        String prefix = configuredPrefix == null ? "AUTO" : configuredPrefix.replaceAll("[^A-Za-z0-9]", "");
        if (prefix.isBlank()) {
            prefix = "AUTO";
        }
        if (prefix.length() > 8) {
            prefix = prefix.substring(0, 8);
        }
        String planPart = planId == null ? "noplan" : planId.toString().replace("-", "").substring(0, 8);
        String orderPart = pendingOrderId == null ? "noorder" : pendingOrderId.toString().replace("-", "").substring(0, 8);
        String id = prefix + planPart + orderPart;
        return id.length() > 32 ? id.substring(0, 32) : id;
    }

    private List<String> autoGate(ContractCandidate candidate) {
        return ContractTradeGate.autoDenyReasons(candidate, agentProperties);
    }

    /**
     * 仅用于回退尝试顺序的优先级判断，不用于放行：真正的门控仍然在循环内逐个候选执行。
     *
     * <p>判据只包含<b>规则回退循环确实会 {@code continue} 掉、且仅凭候选本身就能判定</b>的门控：
     * 新闻硬风险（{@code hardNewsBlockReason}）和动作门控（{@code autoGate}）。
     * 原先只看 {@code autoGate}，新闻已判定必拒的候选照样占用尝试名额。
     *
     * <p>这里<b>刻意不含</b> {@code planDenyReasons}：该路径下它由
     * {@code ContractTradePlanBuilder} 当作 gateWarnings 处理，并不必然阻断下单，
     * 把它算作"已知必拒"会误伤真正可成交的候选。仓位质量与敞口门控依赖账户状态，
     * 仍留在循环内逐个候选判断。
     *
     * <p>任何字段缺失导致的异常一律按"未就绪"处理（fail-closed）。
     */
    private boolean autoTradeReady(ContractCandidate candidate) {
        try {
            return hardNewsBlockReason(candidate) == null && autoGate(candidate).isEmpty();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private List<ContractCandidate> aiEligibleCandidates(
            List<ContractCandidate> candidates,
            Map<String, V2SelectionScoreResult> v2ScoreSnapshot
    ) {
        return candidates.stream()
                .filter(candidate -> {
                    List<String> denials = aiHardGate(candidate);
                    if (!denials.isEmpty() && aiContractSelectionService != null) {
                        aiContractSelectionService.observeHardDeny(candidate, denials);
                    }
                    return denials.isEmpty();
                })
                .filter(candidate -> !"RULE_FIRST_AI_RANK".equals(agentProperties.aiSelection().policy())
                        || ContractTradeGate.planDenyReasons(candidate, agentProperties).isEmpty())
                .sorted((left, right) -> V2SelectionScoreCalculator.compareForRanking(
                        left, right, v2ScoreSnapshot))
                .toList();
    }

    private List<String> aiHardGate(ContractCandidate candidate) {
        return ContractTradeGate.aiHardDenyReasons(candidate, agentProperties);
    }

    private static List<ContractCandidate> aiOrderedCandidates(
            List<ContractCandidate> candidates,
            ContractSelectionDecision selection
    ) {
        return selection.rankedCandidates().stream()
                .map(ranked -> candidates.stream()
                        .filter(candidate -> ranked.instId().equals(candidate.instId()))
                        .findFirst()
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static ContractSelectionDecision selectionFor(
            ContractCandidate candidate,
            ContractSelectionDecision selection
    ) {
        ContractSelectionDecision.RankedCandidate ranked = selection.rankedCandidates().stream()
                .filter(item -> item.instId().equals(candidate.instId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("candidate missing from AI ranking: " + candidate.instId()));
        return new ContractSelectionDecision(
                selection.source(), selection.decision(), candidate.instId(), ranked.direction(), ranked.entryMode(),
                ranked.confidence(), List.of(ranked), ranked.reasonList(), ranked.riskList(), selection.fallbackReason());
    }

    private String currentOwner() {
        String owner = systemControlService.autoTradeOwnerUsername();
        return owner == null || owner.isBlank() ? "local-admin" : owner;
    }

    private OkxCurrentOrderSyncService.SyncResult syncCurrentOrders() {
        if (currentOrderSyncService == null) {
            return new OkxCurrentOrderSyncService.SyncResult(0, 0, 0, false, null, List.of());
        }
        OkxCurrentOrderSyncService.SyncResult result = currentOrderSyncService.syncOnce();
        return result == null
                ? new OkxCurrentOrderSyncService.SyncResult(0, 0, 0, false, null, List.of())
                : result;
    }

    private static void mergePendingExposures(List<PositionSummary> positions,
                                              Set<String> knownInstIds,
                                              List<PositionSummary> pendingExposures) {
        if (pendingExposures == null || pendingExposures.isEmpty()) {
            return;
        }
        for (PositionSummary exposure : pendingExposures) {
            if (exposure == null || exposure.instId() == null || exposure.instId().isBlank()) {
                continue;
            }
            if (!knownInstIds.add(exposure.instId())) {
                continue;
            }
            positions.add(exposure);
        }
    }

    private List<PositionSummary> activePositions() {
        if (positionSnapshotService == null) {
            throw new IllegalStateException("position_snapshot_service_unavailable");
        }
        return positionSnapshotService.positions().stream()
                .filter(position -> PositionSideMatcher.matches(position.instId(), null, null, position))
                .toList();
    }

    private static PositionSummary committedExposurePosition(ContractCandidate candidate, TradePlan plan,
                                                             PendingOrder order) {
        String posSide = order != null && order.posSide() != null && !order.posSide().isBlank()
                ? order.posSide()
                : (plan != null && plan.directionBias() == com.example.quant.market.DirectionBias.BEARISH
                        ? "short" : "long");
        BigDecimal avgPrice = plan != null && plan.entryPrice() != null ? plan.entryPrice() : candidate.lastPrice();
        return new PositionSummary(candidate.instId(), posSide, "1", avgPrice, BigDecimal.ZERO, "AUTO_TRADE_COMMITTED");
    }

    private void pruneInFlightOrders() {
        Instant now = Instant.now();
        inFlightUntilByInstId.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    private void markInFlight(String instId) {
        int ttlSeconds = agentProperties.concurrency().symbolLockEnabled()
                ? agentProperties.concurrency().symbolLockTtlSeconds()
                : agentProperties.autoTrade().inFlightOrderHoldSeconds();
        inFlightUntilByInstId.put(instId, Instant.now().plusSeconds(Math.max(30, ttlSeconds)));
    }

    private int symbolLockCount() {
        return agentProperties.concurrency().symbolLockEnabled() ? inFlightUntilByInstId.size() : 0;
    }

    private int maxOpenPositions() {
        return Math.max(1, agentProperties.autoTrade().maxOpenPositions());
    }

    private int targetOpenPositions() {
        int configuredTarget = Math.max(1, agentProperties.autoTrade().minOpenPositions());
        return Math.min(maxOpenPositions(), configuredTarget);
    }

    static Optional<ContractCandidate> topCandidate(List<ContractCandidate> candidates,
                                                    Map<String, V2SelectionScoreResult> scores) {
        // Scanner delivery order already includes readiness tiers and the pinned candidate.
        if (!scores.isEmpty()) {
            var firstScored = candidates.stream().filter(candidate -> {
                var score = scores.get(candidate.instId());
                return score != null && score.rankingScore() != null;
            }).findFirst();
            if (firstScored.isPresent()) return firstScored;
        }
        return candidates.stream().max(Comparator.comparing(ContractCandidate::finalRankScore)
                .thenComparing(ContractCandidate::score)
                .thenComparing(ContractCandidate::volume24h, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value).abs();
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal executedMarginAmount(List<AutoTradeResult> executedResults) {
        if (executedResults == null || executedResults.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return executedResults.stream()
                .map(AutoTradeResult::marginAmount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal min(BigDecimal first, BigDecimal second) {
        if (first == null) {
            return second == null ? BigDecimal.ZERO : second;
        }
        if (second == null) {
            return first;
        }
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static final class AutoTradeMarginAllocator {
        private final BigDecimal configuredAmount;
        private BigDecimal remainingAvailable;
        private final String skipReason;

        private AutoTradeMarginAllocator(BigDecimal configuredAmount, BigDecimal remainingAvailable, String skipReason) {
            this.configuredAmount = configuredAmount;
            this.remainingAvailable = remainingAvailable;
            this.skipReason = skipReason;
        }

        static AutoTradeMarginAllocator use(BigDecimal configuredAmount, BigDecimal remainingAvailable) {
            return new AutoTradeMarginAllocator(configuredAmount, remainingAvailable, null);
        }

        static AutoTradeMarginAllocator skipped(String reason) {
            return new AutoTradeMarginAllocator(null, null, reason);
        }

        String skipReason() {
            return skipReason;
        }

        BigDecimal nextAmount() {
            if (skipReason != null) {
                return null;
            }
            if (remainingAvailable == null) {
                return configuredAmount;
            }
            if (remainingAvailable.signum() <= 0) {
                return null;
            }
            BigDecimal amount = configuredAmount.min(remainingAvailable);
            remainingAvailable = remainingAvailable.subtract(amount);
            return amount;
        }
    }

    public record AutoTradeResult(
            String status,
            String instId,
            String tradePlanId,
            String pendingOrderId,
            String okxOrderId,
            String action,
            String posSide,
            Integer leverage,
            BigDecimal marginAmount,
            BigDecimal entryPrice,
            String message,
            Instant createdAt
    ) {
        static AutoTradeResult skipped(String reason) {
            return new AutoTradeResult("SKIPPED", null, null, null, null, null, null,
                    null, null, null, reason, Instant.now());
        }

        static AutoTradeResult failed(String reason) {
            return new AutoTradeResult("FAILED", null, null, null, null, null, null,
                    null, null, null, reason, Instant.now());
        }
    }
}

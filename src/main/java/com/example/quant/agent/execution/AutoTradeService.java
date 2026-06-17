package com.example.quant.agent.execution;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetAllocationRequest;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.gate.ContractTradeGate;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.market.MarketType;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
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
                positionSnapshotService, new AutoTradeBudgetService(agentProperties));
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
            AutoTradeBudgetService budgetService
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
    }

    public AutoTradeResult evaluateAndExecute(List<ContractCandidate> candidates) {
        if (!agentProperties.autoTrade().enabled()) {
            return AutoTradeResult.skipped("auto_trade_disabled_by_config");
        }
        if (!systemControlService.autoTradeEnabled()) {
            return AutoTradeResult.skipped("auto_trade_runtime_switch_off");
        }
        boolean executionLockEnabled = agentProperties.concurrency().autoTradeLockEnabled();
        boolean executionLockAcquired = false;
        if (executionLockEnabled) {
            executionLockAcquired = running.compareAndSet(false, true);
            if (!executionLockAcquired) {
                log.warn("AutoTrade skipped: reason=AUTO_TRADE_EXECUTION_ALREADY_RUNNING");
                return AutoTradeResult.skipped("AUTO_TRADE_EXECUTION_ALREADY_RUNNING");
            }
        }
        List<ContractCandidate> safeCandidates = candidates == null ? List.of() : candidates;
        int candidateCount = safeCandidates.size();
        String scanId = UUID.randomUUID().toString();
        try {
            AutoTradeRiskMode riskMode = systemControlService.autoTradeRiskMode();
            boolean noRiskMode = riskMode != null && riskMode.noRisk();
            int noRiskMinScore = noRiskMinScore();
            pruneInFlightOrders();
            List<PositionSummary> positions = activePositions();
            Set<String> heldInstIds = positions.stream()
                    .map(PositionSummary::instId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            PositionQualityPolicy positionQualityPolicy = new PositionQualityPolicy(agentProperties);
            CorrelationExposureGuard exposureGuard = new CorrelationExposureGuard(agentProperties);
            FailureReasonClassifier failureReasonClassifier = new FailureReasonClassifier(agentProperties);
            PreConfirmRefreshService preConfirmRefreshService = new PreConfirmRefreshService(agentProperties);
            ContractCandidate topForPolicy = topCandidate(safeCandidates).orElse(null);
            int dynamicMaxPositions = noRiskMode || topForPolicy == null
                    ? maxOpenPositions()
                    : positionQualityPolicy.allowedMaxPositionsFor(topForPolicy);
            int openOrPendingCount = heldInstIds.size() + symbolLockCount();
            int initialOpenOrPendingCount = openOrPendingCount;
            int initialInFlightCount = symbolLockCount();
            int capacity = dynamicMaxPositions - openOrPendingCount;
            log.warn("AutoTrade round start scanId={} held={} inFlight={} candidateCount={} marketRegime={} dynamicMaxPositions={} minScore={} minFinalRankScore={} riskMode={}",
                    scanId, heldInstIds.size(), symbolLockCount(), candidateCount,
                    positionQualityPolicy.marketRegimeFor(topForPolicy), dynamicMaxPositions,
                    noRiskMode ? noRiskMinScore
                            : positionQualityPolicy.minScoreFor(openOrPendingCount, topForPolicy),
                    noRiskMode ? noRiskMinScore
                            : positionQualityPolicy.minFinalRankScoreFor(openOrPendingCount, topForPolicy),
                    riskMode);
            if (capacity <= 0) {
                ContractCandidate top = topCandidate(safeCandidates).orElse(null);
                log.warn("AutoTrade round stopped scanId={} stage=POSITION_CAPACITY reason=capacity_full fallback=false", scanId);
                return recordAndReturn(AutoTradeResult.skipped("position_capacity_full: held="
                                + heldInstIds.size() + ", inFlight=" + symbolLockCount()
                                + ", dynamicMax=" + dynamicMaxPositions),
                        candidateCount, top);
            }
            int refillNeeded = Math.min(targetOpenPositions(), dynamicMaxPositions) - openOrPendingCount;
            if (refillNeeded <= 0) {
                ContractCandidate top = topCandidate(safeCandidates).orElse(null);
                log.warn("AutoTrade round stopped scanId={} stage=POSITION_TARGET reason=target_satisfied fallback=false", scanId);
                return recordAndReturn(AutoTradeResult.skipped("position_target_satisfied: held="
                                + heldInstIds.size() + ", inFlight=" + symbolLockCount()
                                + ", target=" + targetOpenPositions()),
                        candidateCount, top);
            }
            int targetExecutions = Math.min(capacity, refillNeeded);
            BigDecimal totalBudgetUsdt = autoTradeTotalBudgetUsdt();
            if (totalBudgetUsdt == null || totalBudgetUsdt.signum() <= 0) {
                return recordAndReturn(AutoTradeResult.skipped("auto_trade_total_budget_not_configured"), candidateCount, null);
            }
            Optional<BigDecimal> accountAvailableAtRoundStart = accountAvailableMarginUsdt();
            List<ContractCandidate> orderedCandidates = safeCandidates.stream()
                    .sorted(Comparator.comparing(ContractCandidate::finalRankScore, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(ContractCandidate::score, Comparator.reverseOrder())
                            .thenComparing(ContractCandidate::volume24h, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(Math.max(1, agentProperties.ranking().maxAutoTradeFallbackRank()))
                    .toList();
            List<AutoTradeResult> executedResults = new ArrayList<>();
            Set<String> attemptedSymbols = new LinkedHashSet<>();
            String lastAttemptSkipReason = null;
            for (ContractCandidate candidate : orderedCandidates) {
                if (executedResults.size() >= targetExecutions) {
                    break;
                }
                if (!attemptedSymbols.add(candidate.instId())) {
                    lastAttemptSkipReason = "SYMBOL_ALREADY_IN_FLIGHT";
                    log.warn("AutoTrade candidate skipped scanId={} symbol={} reason=SYMBOL_ALREADY_IN_FLIGHT classification={} fallback=true",
                            scanId, candidate.instId(), FailureClassification.NEXT_CANDIDATE_ALLOWED);
                    continue;
                }
                if (heldInstIds.contains(candidate.instId())
                        || (agentProperties.concurrency().symbolLockEnabled()
                        && inFlightUntilByInstId.containsKey(candidate.instId()))) {
                    lastAttemptSkipReason = "SYMBOL_ALREADY_IN_FLIGHT";
                    log.warn("AutoTrade candidate skipped scanId={} symbol={} reason=SYMBOL_ALREADY_IN_FLIGHT classification={} fallback=true",
                            scanId, candidate.instId(), FailureClassification.NEXT_CANDIDATE_ALLOWED);
                    continue;
                }
                if (noRiskMode) {
                    BigDecimal score = displayedScore(candidate);
                    BigDecimal minScore = BigDecimal.valueOf(noRiskMinScore);
                    if (score.compareTo(minScore) < 0) {
                        lastAttemptSkipReason = "no_risk_score_below_" + noRiskMinScore;
                        log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=NO_RISK_MIN_SCORE reason={} score={} finalRankScore={} classification={} fallback=true",
                                scanId, candidate.instId(), lastAttemptSkipReason, candidate.score(), candidate.finalRankScore(),
                                FailureClassification.NEXT_CANDIDATE_ALLOWED);
                        continue;
                    }
                } else {
                    PositionQualityPolicy.PositionQualityDecision qualityDecision = positionQualityPolicy.evaluate(
                            positions,
                            initialInFlightCount,
                            candidate
                    );
                    if (!qualityDecision.allowed()) {
                        lastAttemptSkipReason = qualityDecision.reason();
                        log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=POSITION_QUALITY reason={} classification={} fallback=true",
                                scanId, candidate.instId(), qualityDecision.reason(), FailureClassification.NEXT_CANDIDATE_ALLOWED);
                        continue;
                    }
                    CorrelationExposureGuard.ExposureDecision exposureDecision = exposureGuard.evaluate(positions, candidate);
                    if (!exposureDecision.allowed()) {
                        lastAttemptSkipReason = exposureDecision.reason();
                        log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=EXPOSURE reason={} classification={} fallback=true",
                                scanId, candidate.instId(), exposureDecision.reason(), FailureClassification.NEXT_CANDIDATE_ALLOWED);
                        continue;
                    }
                    List<String> autoGateReasons = autoGate(candidate);
                    if (!autoGateReasons.isEmpty()) {
                        lastAttemptSkipReason = String.join(",", autoGateReasons);
                        continue;
                    }
                }
                markInFlight(candidate.instId());
                TradePlan plan;
                PendingOrder order;
                OrderExecutionResult result;
                BudgetReservation reservation = null;
                BigDecimal orderMarginUsdt = BigDecimal.ZERO;
                try {
                    plan = noRiskMode
                            ? tradePlanService.createNoRiskContractPlan(candidate)
                            : tradePlanService.createContractPlan(candidate);
                    if (plan.action() == com.example.quant.tradeplan.TradePlanType.WATCH
                            || "NO_ENTRY".equals(plan.orderType())) {
                        String reason = "智能入场或计划输出为观察，不提交OKX";
                        lastAttemptSkipReason = reason;
                        inFlightUntilByInstId.remove(candidate.instId());
                        log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=PLAN reason={} classification={} fallback=true",
                                scanId, candidate.instId(), reason, FailureClassification.NEXT_CANDIDATE_ALLOWED);
                        continue;
                    }
                    if (noRiskMode) {
                        log.warn("AutoTrade no-risk mode bypassed pre-confirm analysis refresh scanId={} symbol={} score={} minScore={}",
                                scanId, candidate.instId(), candidate.score(), noRiskMinScore);
                    } else {
                        PreConfirmRefreshResult refreshResult = preConfirmRefreshService.check(candidate, plan);
                        if (!refreshResult.passed()) {
                            String reason = String.join(",", refreshResult.reasons());
                            lastAttemptSkipReason = reason;
                            inFlightUntilByInstId.remove(candidate.instId());
                            log.warn("AutoTrade attempt skipped scanId={} symbol={} stage=PRE_CONFIRM_REFRESH reason={} classification={} fallback=true",
                                    scanId, candidate.instId(), reason, FailureClassification.NEXT_CANDIDATE_ALLOWED);
                            continue;
                        }
                    }
                    int slotIndex = initialOpenOrPendingCount + executedResults.size() + 1;
                    BigDecimal allocationScore = scoreForBudget(candidate, noRiskMode);
                    BigDecimal maxMarginForCandidate = noRiskMode
                            ? totalBudgetUsdt
                            : riskBasedMaxMarginUsdt(totalBudgetUsdt, plan);
                    if (accountAvailableAtRoundStart.isPresent()) {
                        BigDecimal availableForThisRound = accountAvailableAtRoundStart.get()
                                .subtract(executedMarginAmount(executedResults))
                                .max(BigDecimal.ZERO);
                        maxMarginForCandidate = min(maxMarginForCandidate, availableForThisRound);
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
                            candidate.riskRewardRatio()
                    ));
                    orderMarginUsdt = allocation.finalOrderMarginUsdt();
                    if (orderMarginUsdt.compareTo(agentProperties.budget().minOrderMarginUsdt()) < 0) {
                        String reason = "CANDIDATE_BUDGET_ALLOCATION_TOO_LOW_" + orderMarginUsdt;
                        lastAttemptSkipReason = reason;
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
                        lastAttemptSkipReason = reason;
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
                            clientOrderId(agentProperties.idempotency().clientOrderIdPrefix(), plan.id(), pendingOrderId));
                    result = orderConfirmService.confirmAuto(order.id(), orderMarginUsdt);
                } catch (RuntimeException ex) {
                    inFlightUntilByInstId.remove(candidate.instId());
                    if (reservation != null) {
                        budgetService.release(reservation.reservationId(), "PLAN_OR_CONFIRM_FAILED");
                    }
                    FailureClassification classification = failureReasonClassifier.classify("PLAN_OR_CONFIRM", ex.getMessage());
                    log.warn("AutoTrade attempt failed scanId={} symbol={} stage=PLAN_OR_CONFIRM reason={} classification={} fallback={}",
                            scanId, candidate.instId(), ex.getMessage(), classification,
                            classification == FailureClassification.NEXT_CANDIDATE_ALLOWED);
                    if (classification == FailureClassification.NEXT_CANDIDATE_ALLOWED) {
                        lastAttemptSkipReason = ex.getMessage();
                        continue;
                    }
                    return recordAndReturn(AutoTradeResult.failed(ex.getMessage()), candidateCount, candidate);
                }
                if (result.executed()) {
                    if (reservation != null) {
                        budgetService.markUsed(reservation.reservationId());
                    }
                    log.warn("Auto trade submitted scanId={} instId={} tradePlanId={} pendingOrderId={} okxOrdId={}",
                            scanId, plan.instId(), plan.id(), order.id(), result.externalOrderId());
                    AutoTradeResult executed = new AutoTradeResult("EXECUTED", plan.instId(), plan.id().toString(),
                            order.id().toString(), result.externalOrderId(), plan.action().name(), order.posSide(),
                            order.leverage(), orderMarginUsdt, plan.entryPrice(), result.message(), Instant.now());
                    recordExecution(executed, candidateCount, candidate);
                    executedResults.add(executed);
                    heldInstIds.add(candidate.instId());
                    continue;
                }
                log.warn("Auto trade rejected after plan scanId={} instId={} tradePlanId={} reason={}",
                        scanId, plan.instId(), plan.id(), result.message());
                inFlightUntilByInstId.remove(candidate.instId());
                if (reservation != null) {
                    budgetService.release(reservation.reservationId(), "CONFIRM_REJECTED");
                }
                FailureClassification classification = failureReasonClassifier.classify("CONFIRM", result.message());
                log.warn("AutoTrade attempt failed scanId={} symbol={} stage=CONFIRM reason={} classification={} fallback={}",
                        scanId, candidate.instId(), result.message(), classification,
                        classification == FailureClassification.NEXT_CANDIDATE_ALLOWED);
                if (classification == FailureClassification.NEXT_CANDIDATE_ALLOWED) {
                    lastAttemptSkipReason = result.message();
                    continue;
                }
                AutoTradeResult rejected = new AutoTradeResult("REJECTED", plan.instId(), plan.id().toString(),
                        order.id().toString(), null, plan.action().name(), order.posSide(), order.leverage(),
                        orderMarginUsdt, plan.entryPrice(), result.message(), Instant.now());
                if (executedResults.isEmpty()) {
                    return recordAndReturn(rejected, candidateCount, candidate);
                }
                return aggregateExecutedResults(executedResults,
                        "auto_refill_submitted=" + executedResults.size() + ", stopped_by_rejection=" + result.message());
            }
            if (executedResults.isEmpty()) {
                ContractCandidate top = topCandidate(safeCandidates).orElse(null);
                String message = "no_candidate_passed_auto_gate";
                if (lastAttemptSkipReason != null && !lastAttemptSkipReason.isBlank()) {
                    message = message + ": " + lastAttemptSkipReason;
                }
                if (top != null) {
                    List<String> reasons = noRiskMode ? List.of() : autoGate(top);
                    if (!reasons.isEmpty()) {
                        message = message + ": " + String.join(",", reasons);
                    }
                }
                return recordAndReturn(AutoTradeResult.skipped(message), candidateCount, top);
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

    private void recordExecution(AutoTradeResult result, int candidateCount, ContractCandidate candidate) {
        if ("EXECUTED".equals(result.status())) {
            autoTradeRecordService.record(result, candidateCount, candidate);
        }
    }

    private AutoTradeResult aggregateExecutedResults(List<AutoTradeResult> executedResults, String message) {
        AutoTradeResult last = executedResults.get(executedResults.size() - 1);
        BigDecimal totalMargin = executedResults.stream()
                .map(AutoTradeResult::marginAmount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AutoTradeResult("EXECUTED", last.instId(), last.tradePlanId(), last.pendingOrderId(),
                last.okxOrderId(), last.action(), last.posSide(), last.leverage(), totalMargin,
                last.entryPrice(), message, Instant.now());
    }

    private BigDecimal autoTradeTotalBudgetUsdt() {
        BigDecimal configured = systemControlService.autoTradeMarginUsdt();
        if (configured == null || configured.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return configured;
    }

    private Optional<BigDecimal> accountAvailableMarginUsdt() {
        try {
            AccountSummary account = accountSnapshotService.summary();
            if (account.availableBalance() == null) {
                return Optional.empty();
            }
            return Optional.of(account.availableBalance().max(BigDecimal.ZERO));
        } catch (RuntimeException ex) {
            log.warn("Auto trade cannot read account available balance, continuing with configured budget only: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private BigDecimal riskBasedMaxMarginUsdt(BigDecimal totalBudgetUsdt, TradePlan plan) {
        BigDecimal riskPct = agentProperties.risk().singleTradeRiskPercent().max(BigDecimal.valueOf(8));
        BigDecimal maxLoss = totalBudgetUsdt.multiply(riskPct).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal stopLossRate = stopLossPct(plan).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        if (stopLossRate.signum() <= 0 || plan.suggestedLeverage() <= 0) {
            return totalBudgetUsdt;
        }
        BigDecimal notional = maxLoss.divide(stopLossRate, 8, RoundingMode.DOWN);
        return notional.divide(BigDecimal.valueOf(plan.suggestedLeverage()), 8, RoundingMode.DOWN);
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

    private static BigDecimal scoreForBudget(ContractCandidate candidate, boolean noRiskMode) {
        return noRiskMode ? displayedScore(candidate) : scoreForBudget(candidate);
    }

    private static BigDecimal displayedScore(ContractCandidate candidate) {
        return BigDecimal.valueOf(candidate.score());
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

    private List<PositionSummary> activePositions() {
        if (positionSnapshotService == null) {
            return List.of();
        }
        try {
            return positionSnapshotService.positions().stream()
                    .filter(position -> decimal(position.size()).signum() > 0)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Auto trade position snapshot unavailable, continue with in-flight dedupe only: {}", ex.getMessage());
            return List.of();
        }
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

    private static Optional<ContractCandidate> topCandidate(List<ContractCandidate> candidates) {
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

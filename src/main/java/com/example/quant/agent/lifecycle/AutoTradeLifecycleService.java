package com.example.quant.agent.lifecycle;

import com.example.quant.account.ClosePositionRecordEntity;
import com.example.quant.account.ClosePositionRecordRepository;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.PositionCloseService;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.event.TradeEventPayload;
import com.example.quant.agent.event.TradeEventService;
import com.example.quant.agent.event.TradeEventType;
import com.example.quant.agent.event.TradeEventView;
import com.example.quant.agent.exit.ExchangeStopView;
import com.example.quant.agent.exit.ExitLegLedgerService;
import com.example.quant.agent.exit.PositionExitService;
import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxInstrumentRulesProvider;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxPositionMode;
import com.example.quant.okxtrade.OkxPositionModeProvider;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.okxtrade.TradingOperationContext;
import com.example.quant.okxtrade.TradingOperationType;
import com.example.quant.order.OrderStatus;
import com.example.quant.account.PositionSideMatcher;
import com.example.quant.okxtrade.OkxCancelAcknowledgement;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import com.example.quant.tradeplan.CanonicalPlanCompiler;
import com.example.quant.tradeplan.ProtectionAllocation;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AutoTradeLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(AutoTradeLifecycleService.class);
    private final PendingOrderService pendingOrderService;
    private final AutoTradeBudgetService budgetService;
    private final AgentProperties agentProperties;
    private final OkxOrderGateway okxOrderGateway;
    private final PositionSnapshotService positionSnapshotService;
    private final OkxInstrumentRulesProvider instrumentRulesProvider;
    private final OkxPositionModeProvider positionModeProvider;
    private final ClosePositionRecordRepository closePositionRecordRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;
    private final TradeEventService tradeEventService;
    private CanonicalPlanCompiler canonicalPlanCompiler = new CanonicalPlanCompiler();
    private PositionCloseService positionCloseService;
    private PositionExitService positionExitService;
    private ExitLegLedgerService exitLegLedgerService;
    private final Map<UUID, Set<String>> submittedProtectionIds = new java.util.concurrent.ConcurrentHashMap<>();

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                OkxInstrumentRules::defaultFor, null, null, null, null);
    }

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     ClosePositionRecordRepository closePositionRecordRepository) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                OkxInstrumentRules::defaultFor, closePositionRecordRepository, null, null, null);
    }

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     ClosePositionRecordRepository closePositionRecordRepository,
                                     TradeOrderRepository tradeOrderRepository) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                OkxInstrumentRules::defaultFor, closePositionRecordRepository, tradeOrderRepository, null, null);
    }

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     OkxInstrumentRulesProvider instrumentRulesProvider,
                                     ClosePositionRecordRepository closePositionRecordRepository,
                                     TradeOrderRepository tradeOrderRepository) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                instrumentRulesProvider, closePositionRecordRepository, tradeOrderRepository, null, null);
    }

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     OkxInstrumentRulesProvider instrumentRulesProvider,
                                     ClosePositionRecordRepository closePositionRecordRepository,
                                     TradeOrderRepository tradeOrderRepository,
                                     TradeEventService tradeEventService) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                instrumentRulesProvider, closePositionRecordRepository, tradeOrderRepository, null, tradeEventService, null);
    }

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     OkxInstrumentRulesProvider instrumentRulesProvider,
                                     ClosePositionRecordRepository closePositionRecordRepository,
                                     TradeOrderRepository tradeOrderRepository,
                                     OkxPositionModeProvider positionModeProvider) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                instrumentRulesProvider, closePositionRecordRepository, tradeOrderRepository, positionModeProvider, null);
    }

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     OkxInstrumentRulesProvider instrumentRulesProvider,
                                     ClosePositionRecordRepository closePositionRecordRepository,
                                     TradeOrderRepository tradeOrderRepository,
                                     OkxPositionModeProvider positionModeProvider,
                                     TradeEventService tradeEventService) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                instrumentRulesProvider, closePositionRecordRepository, tradeOrderRepository, positionModeProvider,
                tradeEventService, null);
    }

    @Autowired
    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     OkxInstrumentRulesProvider instrumentRulesProvider,
                                     ClosePositionRecordRepository closePositionRecordRepository,
                                     TradeOrderRepository tradeOrderRepository,
                                     OkxPositionModeProvider positionModeProvider,
                                     TradeEventService tradeEventService,
                                     TradeFillRepository tradeFillRepository) {
        this.pendingOrderService = pendingOrderService;
        this.budgetService = budgetService;
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
        this.okxOrderGateway = okxOrderGateway;
        this.positionSnapshotService = positionSnapshotService;
        this.instrumentRulesProvider = instrumentRulesProvider == null ? OkxInstrumentRules::defaultFor : instrumentRulesProvider;
        this.positionModeProvider = positionModeProvider == null ? () -> OkxPositionMode.LONG_SHORT : positionModeProvider;
        this.closePositionRecordRepository = closePositionRecordRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.tradeEventService = tradeEventService;
        this.tradeFillRepository = tradeFillRepository;
    }

    @Autowired(required = false)
    public void setCanonicalPlanCompiler(CanonicalPlanCompiler canonicalPlanCompiler) {
        this.canonicalPlanCompiler = canonicalPlanCompiler;
    }

    @Autowired(required = false)
    public void setPositionCloseService(PositionCloseService positionCloseService) {
        this.positionCloseService = positionCloseService;
    }

    @Autowired(required = false)
    public void setPositionExitService(PositionExitService positionExitService) {
        this.positionExitService = positionExitService;
    }

    @Autowired(required = false)
    public void setExitLegLedgerService(ExitLegLedgerService exitLegLedgerService) {
        this.exitLegLedgerService = exitLegLedgerService;
    }

    public LifecycleRunResult runOnce() {
        return runOnce(Instant.now());
    }

    public LifecycleRunResult runOnce(Instant now) {
        int entryTimeoutCancelled = 0;
        int entryFilledProtected = 0;
        int partialFillProtected = 0;
        int sidewaysTpAdjusted = 0;
        int maxHoldCloseSubmitted = 0;
        PositionSyncResult positionSync = positions();
        if (!positionSync.available()) {
            List<PendingOrder> affectedOrders = pendingOrderService.allOrders().stream()
                    .filter(order -> order.submittedAt() != null)
                    .toList();
            for (PendingOrder order : affectedOrders) {
                recordEvent(order, TradeEventType.RECOVERY_FAILED, order.status().name(), order.status().name(),
                        "POSITION_SYNC_UNAVAILABLE", "持仓查询失败，跳过本轮生命周期动作", null, order.clientOrderId(), null);
            }
            int affected = affectedOrders.size();
            return new LifecycleRunResult(0, 0, 0, 0, 0, affected, 0);
        }
        List<PositionSummary> positions = positionSync.positions();
        for (PendingOrder order : pendingOrderService.allOrders()) {
            if (order.requiresLegacyAccountingReconciliation()) {
                // Do not overwrite the durable hold before historical accounting repair.
                continue;
            }
            if (order.submittedAt() == null) {
                continue;
            }
            PositionSummary position = matchingPosition(order, positions);
            Duration age = Duration.between(order.submittedAt(), now);
            if (position != null && order.status() == OrderStatus.EMERGENCY_ATTENTION_REQUIRED
                    && isProtectionSafetyEmergency(order)) {
                // A safety-close failure is not a normal lifecycle state. On
                // every scheduler/restart pass, inspect the exchange first;
                // retry the same durable close intent only when no valid SL
                // is covering the live position.
                boolean validStop;
                try {
                    validStop = hasValidStop(activeAlgoProtections(order), decimal(position.size()));
                } catch (RuntimeException protectionQueryFailure) {
                    // An unavailable protection read cannot be treated as a
                    // safe stop. Keep the durable safety-close incident
                    // active and let the close service/recovery path decide
                    // whether this attempt is ACKed, UNKNOWN, or retried.
                    triggerSafetyClose(order, position,
                            "CANONICAL_PLAN_UNAVAILABLE_AFTER_ENTRY", protectionQueryFailure);
                    continue;
                }
                if (!validStop) {
                    triggerSafetyClose(order, position,
                            "CANONICAL_PLAN_UNAVAILABLE_AFTER_ENTRY", null);
                }
                continue;
            }
            if (order.status() == OrderStatus.SUBMITTED) {
                EntryFill fill = queryEntryFill(order);
                if (fill.complete()) {
                    if (order.budgetReservationId() != null) {
                        budgetService.markUsed(order.budgetReservationId());
                    }
                    try {
                        ProtectionSubmitResult protection = submitProtection(order, fill.avgPx(), fill.filledSize(), "FULL_FILL", true);
                        if (protection == ProtectionSubmitResult.COMPLETE) {
                            order.markProtectionSubmitted("ENTRY_FILLED_PROTECTION_SUBMITTED");
                        }
                        String resultingStatus = order.status().name();
                        TradeEventType protectionEvent = protection == ProtectionSubmitResult.COMPLETE
                                ? TradeEventType.PROTECTION_SUBMITTED : TradeEventType.PROTECTION_FAILED;
                        recordEvent(order, TradeEventType.ENTRY_FILLED, OrderStatus.SUBMITTED.name(),
                                resultingStatus, "ENTRY_FULLY_FILLED",
                                "入场委托完全成交，按实际成交数量提交保护单", order.externalOrderId(), order.clientOrderId(), null);
                        recordEvent(order, protectionEvent, OrderStatus.SUBMITTED.name(),
                                resultingStatus, protection == ProtectionSubmitResult.COMPLETE
                                        ? "FULL_FILL_PROTECTION_SUBMITTED" : protection.name(),
                                "完全成交后提交保护单", order.externalOrderId(), order.clientOrderId(), null);
                        entryFilledProtected++;
                    } catch (RuntimeException ex) {
                        if (position != null && triggerSafetyClose(order, position,
                                "CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY", ex)) {
                            recordEvent(order, TradeEventType.PROTECTION_FAILED, OrderStatus.SUBMITTED.name(),
                                    OrderStatus.CLOSE_SUBMITTED.name(), "TERMINAL_SAFETY_REDUCTION",
                                    ex.getMessage(), order.externalOrderId(), order.clientOrderId(), null);
                        } else {
                            order.markProtectionFailed("PROTECTION_FAILED_AFTER_FULL_FILL: " + compact(ex.getMessage()));
                            recordEvent(order, TradeEventType.PROTECTION_FAILED, OrderStatus.SUBMITTED.name(),
                                    OrderStatus.PROTECTION_FAILED.name(), "PROTECTION_FAILED_AFTER_FULL_FILL",
                                    ex.getMessage(), order.externalOrderId(), order.clientOrderId(), null);
                        }
                    }
                    continue;
                }
                if (position != null) {
                    if (order.budgetReservationId() != null) {
                        budgetService.markUsed(order.budgetReservationId());
                    }
                    try {
                        BigDecimal positionSize = decimal(position.size());
                        BigDecimal avgPx = positive(position.avgPrice(), positive(fill.avgPx(), order.price()));
                        ProtectionSubmitResult protection = submitProtection(order, avgPx, positionSize, "POSITION_SYNC_FILL", false);
                        if (protection == ProtectionSubmitResult.COMPLETE) {
                            order.markProtectionSubmitted("POSITION_SYNC_FILL_PROTECTION_SUBMITTED");
                        }
                        String resultingStatus = order.status().name();
                        TradeEventType protectionEvent = protection == ProtectionSubmitResult.COMPLETE
                                ? TradeEventType.PROTECTION_SUBMITTED : TradeEventType.PROTECTION_FAILED;
                        recordEvent(order, TradeEventType.ENTRY_FILLED, OrderStatus.SUBMITTED.name(),
                                resultingStatus, "POSITION_SYNC_ENTRY_FILLED",
                                "账户已有持仓，按实际持仓数量补交保护单", order.externalOrderId(), order.clientOrderId(), null);
                        recordEvent(order, protectionEvent, OrderStatus.SUBMITTED.name(),
                                resultingStatus, protection == ProtectionSubmitResult.COMPLETE
                                        ? "POSITION_SYNC_PROTECTION_SUBMITTED" : protection.name(),
                                "按持仓快照补交保护单", order.externalOrderId(), order.clientOrderId(), null);
                        entryFilledProtected++;
                    } catch (RuntimeException ex) {
                        if (triggerSafetyClose(order, position,
                                "CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY", ex)) {
                            recordEvent(order, TradeEventType.PROTECTION_FAILED, OrderStatus.SUBMITTED.name(),
                                    OrderStatus.CLOSE_SUBMITTED.name(), "TERMINAL_SAFETY_REDUCTION",
                                    ex.getMessage(), order.externalOrderId(), order.clientOrderId(), null);
                        } else {
                            order.markProtectionFailed("PROTECTION_FAILED_AFTER_POSITION_SYNC: " + compact(ex.getMessage()));
                            recordEvent(order, TradeEventType.PROTECTION_FAILED, OrderStatus.SUBMITTED.name(),
                                    OrderStatus.PROTECTION_FAILED.name(), "PROTECTION_FAILED_AFTER_POSITION_SYNC",
                                    ex.getMessage(), order.externalOrderId(), order.clientOrderId(), null);
                        }
                    }
                    continue;
                }
                if (shouldCancelStaleEntry(order, age)) {
                    try {
                        EntryFill reconciled = cancelEntryOrder(order);
                        if (reconciled == null) {
                            recordEvent(order, TradeEventType.RECOVERY_FAILED, OrderStatus.SUBMITTED.name(),
                                    OrderStatus.SUBMITTED.name(), "ENTRY_CANCEL_NOT_FINAL",
                                    "撤单请求后订单终态尚未确认，保留订单与预算等待下轮对账",
                                    order.externalOrderId(), order.clientOrderId(), null);
                            continue;
                        }
                        fill = reconciled;
                    } catch (RuntimeException ex) {
                        recordEvent(order, TradeEventType.RECOVERY_FAILED, OrderStatus.SUBMITTED.name(),
                                OrderStatus.SUBMITTED.name(), "ENTRY_CANCEL_UNCONFIRMED", ex.getMessage(),
                                order.externalOrderId(), order.clientOrderId(), null);
                        continue;
                    }
                    if (fill.filledSize().signum() <= 0) {
                        order.markEntryTimeoutCancelled("ENTRY_TIMEOUT_CANCELLED");
                        if (order.budgetReservationId() != null) {
                            budgetService.release(order.budgetReservationId(), "ENTRY_TIMEOUT_CANCELLED");
                        }
                        recordEvent(order, TradeEventType.ENTRY_TIMEOUT_CANCELLED, OrderStatus.SUBMITTED.name(),
                                OrderStatus.ENTRY_TIMEOUT_CANCELLED.name(), "ENTRY_TIMEOUT_CANCELLED",
                                "入场委托超时且无成交，已撤单", order.externalOrderId(), order.clientOrderId(), null);
                        recordEvent(order, TradeEventType.BUDGET_RELEASED, "RESERVED", "RELEASED",
                                "ENTRY_TIMEOUT_CANCELLED", "入场无成交超时释放预算", null, order.clientOrderId(), null);
                        entryTimeoutCancelled++;
                    } else {
                        if (order.budgetReservationId() != null) {
                            budgetService.markUsed(order.budgetReservationId());
                        }
                        try {
                            ProtectionSubmitResult protection = submitProtection(order, fill.avgPx(), fill.filledSize(), "PARTIAL_FILL_ENTRY_TIMEOUT", false);
                            if (protection == ProtectionSubmitResult.COMPLETE) {
                                order.markProtectionSubmitted("PARTIAL_FILL_PROTECTION_SUBMITTED");
                            }
                            String resultingStatus = order.status().name();
                            TradeEventType protectionEvent = protection == ProtectionSubmitResult.COMPLETE
                                    ? TradeEventType.PROTECTION_SUBMITTED : TradeEventType.PROTECTION_FAILED;
                            recordEvent(order, TradeEventType.ENTRY_PARTIAL_FILLED, OrderStatus.SUBMITTED.name(),
                                    resultingStatus, "PARTIAL_FILL_ENTRY_TIMEOUT",
                                    "部分成交超时后取消剩余委托，并按实际成交数量提交保护单", order.externalOrderId(),
                                    order.clientOrderId(), null);
                            recordEvent(order, protectionEvent, OrderStatus.SUBMITTED.name(),
                                    resultingStatus, protection == ProtectionSubmitResult.COMPLETE
                                            ? "PARTIAL_FILL_PROTECTION_SUBMITTED" : protection.name(),
                                    "部分成交后提交保护单", order.externalOrderId(), order.clientOrderId(), null);
                            partialFillProtected++;
                        } catch (RuntimeException ex) {
                            if (position != null && triggerSafetyClose(order, position,
                                    "CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY", ex)) {
                                recordEvent(order, TradeEventType.PROTECTION_FAILED, OrderStatus.SUBMITTED.name(),
                                        OrderStatus.CLOSE_SUBMITTED.name(), "TERMINAL_SAFETY_REDUCTION",
                                        ex.getMessage(), order.externalOrderId(), order.clientOrderId(), null);
                            } else {
                                order.markProtectionFailed("PROTECTION_FAILED_AFTER_PARTIAL_FILL: " + compact(ex.getMessage()));
                                recordEvent(order, TradeEventType.PROTECTION_FAILED, OrderStatus.SUBMITTED.name(),
                                        OrderStatus.PROTECTION_FAILED.name(), "PROTECTION_FAILED_AFTER_PARTIAL_FILL",
                                        ex.getMessage(), order.externalOrderId(), order.clientOrderId(), null);
                            }
                        }
                    }
                }
                continue;
            }
            // Profit protection. Reconciles the durable exit state against the
            // exchange and moves the protective stop toward profit. It never
            // closes a position, so it cannot race with the close branches below.
            // See docs/PROFIT_PROTECTION_DESIGN.md section 10.
            if (positionExitService != null && managedProtectionStatus(order.status())) {
                if (position == null) {
                    positionExitService.recordClosure(order, null, now, "POSITION_GONE");
                    reconcilePositionGone(order, now);
                } else {
                    try {
                        positionExitService.reconcileAndProtect(order, position, instrumentRules(order.instId()),
                                exchangeStopView(activeAlgoProtections(order)), now);
                    } catch (RuntimeException ex) {
                        log.warn("Profit protection failed instId={} pendingOrderId={}: {}",
                                order.instId(), order.id(), ex.getMessage());
                        recordEvent(order, TradeEventType.PROFIT_PROTECTION_FAILED, order.status().name(),
                                order.status().name(), "PROFIT_PROTECTION_FAILED", ex.getMessage(),
                                order.externalOrderId(), order.clientOrderId(), null);
                    }
                }
            }
            // Canonical protection is a prerequisite for every normal
            // lifecycle branch, including SIDEWAYS and max-hold handling.
            // An active position with a missing/unknown plan may only keep a
            // verified exchange stop or enter durable safety handling.
            if (position != null && managedProtectionStatus(order.status())
                    && !canonicalPlanAvailable(order)) {
                try {
                    reconcileCanonicalProtection(order, position);
                } catch (RuntimeException ex) {
                    order.markEmergencyAttentionRequired("CANONICAL_PLAN_UNAVAILABLE_AFTER_ENTRY: "
                            + compact(ex.getMessage()));
                }
                continue;
            }
            if (position != null && shouldRecoverMissingProtection(order)) {
                if (recoverMissingProtection(order, position)) {
                    entryFilledProtected++;
                }
                continue;
            }
            age = Duration.between(holdingStart(order), now);
            boolean forceCloseDue = position != null && shouldForceClose(order, position, age);
            if (forceCloseDue) {
                maxHoldCloseSubmitted += submitMaxHoldClose(order, position, now);
                continue;
            }
            if (position != null && order.status() == OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED) {
                try {
                    tightenTakeProfit(order, position);
                } catch (RuntimeException ex) {
                    order.markProtectionFailed("SIDEWAYS_RECONCILIATION_REQUIRED: " + compact(ex.getMessage()));
                    recordEvent(order, TradeEventType.PROTECTION_FAILED,
                            OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED.name(), OrderStatus.PROTECTION_FAILED.name(),
                            "SIDEWAYS_RECONCILIATION_REQUIRED", ex.getMessage(),
                            order.externalOrderId(), order.clientOrderId(), null);
                }
                continue;
            }
            boolean sidewaysDue = position != null && shouldAdjustSideways(order, position, age);
            if (position != null && managedProtectionStatus(order.status())
                    && order.status() != OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED
                    && !sidewaysDue && !forceCloseDue) {
                try {
                    if (reconcileCanonicalProtection(order, position)) {
                        continue;
                    }
                } catch (RuntimeException ex) {
                    order.markProtectionFailed("PROTECTION_RECONCILIATION_FAILED: " + compact(ex.getMessage()));
                    recordEvent(order, TradeEventType.PROTECTION_FAILED, order.status().name(),
                            OrderStatus.PROTECTION_FAILED.name(), "PROTECTION_RECONCILIATION_FAILED",
                            ex.getMessage(), order.externalOrderId(), order.clientOrderId(), null);
                    continue;
                }
            }
            if (sidewaysDue) {
                try {
                    sidewaysTpAdjusted += tightenTakeProfit(order, position);
                } catch (RuntimeException ex) {
                    boolean newProtectionActive = false;
                    try {
                        newProtectionActive = activeAlgoProtections(order).stream()
                                .anyMatch(item -> "SIDEWAYS_TP".equalsIgnoreCase(item.role()));
                    } catch (RuntimeException ignored) {
                        // Keep the conservative failure state when the
                        // exchange cannot answer the read-back query.
                    }
                    if (newProtectionActive) {
                        order.markSidewaysTimeoutTpAdjusted("SIDEWAYS_RECONCILE_REQUIRED");
                    } else {
                        order.markProtectionFailed("SIDEWAYS_REPLACEMENT_RECONCILE_REQUIRED: "
                                + compact(ex.getMessage()));
                    }
                    recordEvent(order, TradeEventType.PROTECTION_FAILED,
                            OrderStatus.PROTECTION_SUBMITTED.name(), order.status().name(),
                            "SIDEWAYS_REPLACEMENT_RECONCILE_REQUIRED", ex.getMessage(),
                            order.externalOrderId(), order.clientOrderId(), null);
                }
                continue;
            }
        }
        return new LifecycleRunResult(entryTimeoutCancelled, entryFilledProtected, partialFillProtected,
                sidewaysTpAdjusted, maxHoldCloseSubmitted, 0, 0);
    }

    public List<AutoTradeLifecycleSnapshot> snapshots() {
        PositionSyncResult positionSync = positions();
        List<PositionSummary> positions = positionSync.positions();
        Instant now = Instant.now();
        return pendingOrderService.allOrders().stream()
                .filter(order -> order.budgetReservationId() != null || order.clientOrderId() != null)
                .map(order -> {
                    PositionSummary position = positionSync.available() ? matchingPosition(order, positions) : null;
                    Instant entryTime = order.submittedAt() == null ? order.createdAt() : holdingStart(order);
                    BudgetReservation reservation = order.budgetReservationId() == null
                            ? null
                            : budgetService.reservation(order.budgetReservationId()).orElse(null);
                    BigDecimal budgetUsed = reservation == null ? order.marginAmount() : reservation.amount();
                    EntryOrderFact entryFact = entryFact(order, position);
                    List<ProtectionOrderFact> protectionFacts = protectionFacts(order);
                    ClosePositionFact closeFact = closeFact(order);
                    BudgetFact budgetFact = budgetFact(order, reservation);
                    List<TradeEventView> recentEvents = recentEvents(order);
                    PositionLifecycleFact positionFact = positionFact(positionSync, position);
                    return new AutoTradeLifecycleSnapshot(
                            order.instId(),
                            order.posSide(),
                            entryTime,
                            entryTime == null ? Duration.ZERO : Duration.between(entryTime, now),
                            order.price(),
                            position == null ? BigDecimal.ZERO : position.avgPrice(),
                            position == null ? BigDecimal.ZERO : position.unrealizedPnl(),
                            position == null ? BigDecimal.ZERO : pnlPct(position),
                            budgetUsed,
                            order.status().name(),
                            protectionStatus(order),
                            positionSync.available() ? lifecycleStatus(order, position) : "POSITION_SYNC_UNAVAILABLE",
                            positionSync.available() ? nextAction(order) : "WAIT_POSITION_SYNC_RECOVERY",
                            Duration.ofMinutes(configuredSidewaysMinutes()),
                            Duration.ofMinutes(effectiveSidewaysMinutes()),
                            entryFact,
                            protectionFacts,
                            positionFact,
                            closeFact,
                            budgetFact,
                            recentEvents,
                            manualAttentionRequired(order, protectionFacts, closeFact)
                    );
                })
                .toList();
    }

    /** Actual holding age starts at the first recorded entry fill, while entry timeout remains submit-based. */
    private Instant holdingStart(PendingOrder order) {
        Instant submitted = order.submittedAt();
        if (submitted == null || tradeFillRepository == null || tradeOrderRepository == null || order.id() == null) {
            return submitted == null ? order.createdAt() : submitted;
        }
        try {
            String pendingOrderId = order.id().toString();
            String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
            Set<String> entryOrderIds = tradeOrderRepository
                    .findByUserNameAndPendingOrderIdOrderByCreatedAtAsc(owner, pendingOrderId).stream()
                    .filter(tradeOrder -> !tradeOrder.isReduceOnly())
                    .map(TradeOrderEntity::getOkxOrdId)
                    .filter(AutoTradeLifecycleService::hasText)
                    .collect(java.util.stream.Collectors.toSet());
            if (entryOrderIds.isEmpty()) {
                return submitted;
            }
            return tradeFillRepository.findByPendingOrderId(pendingOrderId).stream()
                    .filter(fill -> entryOrderIds.contains(fill.getOrdId()))
                    .map(TradeFillEntity::getFillTime)
                    .filter(java.util.Objects::nonNull)
                    .min(Instant::compareTo)
                    .orElse(submitted);
        } catch (RuntimeException ignored) {
            return submitted;
        }
    }

    private EntryOrderFact entryFact(PendingOrder order, PositionSummary position) {
        return new EntryOrderFact(
                order.status().name(),
                order.externalOrderId(),
                order.clientOrderId(),
                order.size(),
                position == null ? BigDecimal.ZERO : decimal(position.size()),
                position == null ? BigDecimal.ZERO : position.avgPrice(),
                order.submittedAt()
        );
    }

    private List<ProtectionOrderFact> protectionFacts(PendingOrder order) {
        if (tradeOrderRepository == null || order == null) {
            return List.of();
        }
        return tradeOrderRepository.findByPendingOrderIdOrderByCreatedAtAsc(order.id().toString()).stream()
                .filter(AutoTradeLifecycleService::ownedByCurrentUser)
                .filter(TradeOrderEntity::isReduceOnly)
                .map(item -> new ProtectionOrderFact(
                        item.getId(),
                        item.getOrderRole(),
                        item.getStatus(),
                        item.getOkxState(),
                        item.getOkxOrdId(),
                        item.getClOrdId(),
                        item.getSize(),
                        item.getPrice(),
                        item.getCreatedAt(),
                        item.getErrorMessage()
                ))
                .toList();
    }

    private ClosePositionFact closeFact(PendingOrder order) {
        if (closePositionRecordRepository == null || order == null) {
            return null;
        }
        List<ClosePositionRecordEntity> records = closePositionRecordRepository
                .findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(currentUsername(), order.id().toString());
        if (records == null || records.isEmpty()) {
            return null;
        }
        ClosePositionRecordEntity record = records.get(0);
        return new ClosePositionFact(
                record.getId(),
                record.getStatus(),
                record.getSource(),
                record.getCloseOrderId(),
                record.getCloseClOrdId(),
                record.getRealizedPnl(),
                record.getFee(),
                record.getFundingFee(),
                record.getUpdatedAt(),
                record.getErrorMessage()
        );
    }

    private BudgetFact budgetFact(PendingOrder order, BudgetReservation reservation) {
        if (reservation == null) {
            return order.budgetReservationId() == null
                    ? null
                    : new BudgetFact(order.budgetReservationId().toString(), "UNKNOWN", order.marginAmount(), null, null);
        }
        return new BudgetFact(
                reservation.reservationId().toString(),
                reservation.status().name(),
                reservation.amount(),
                reservation.reason(),
                reservation.updatedAt()
        );
    }

    private List<TradeEventView> recentEvents(PendingOrder order) {
        if (tradeEventService == null || order == null) {
            return List.of();
        }
        try {
            return tradeEventService.recentForPendingOrder(order.id().toString(), 20);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static PositionLifecycleFact positionFact(PositionSyncResult positionSync, PositionSummary position) {
        if (!positionSync.available()) {
            return new PositionLifecycleFact(false, false, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, "POSITION_SYNC_UNAVAILABLE");
        }
        return new PositionLifecycleFact(
                true,
                position != null,
                position == null ? BigDecimal.ZERO : decimal(position.size()),
                position == null ? BigDecimal.ZERO : position.avgPrice(),
                position == null ? BigDecimal.ZERO : position.unrealizedPnl(),
                position == null ? BigDecimal.ZERO : pnlPct(position),
                position == null ? "NO_POSITION" : position.mode()
        );
    }

    private static boolean manualAttentionRequired(PendingOrder order, List<ProtectionOrderFact> protections,
                                                   ClosePositionFact closeFact) {
        if (order.status() == OrderStatus.EMERGENCY_ATTENTION_REQUIRED
                || order.status() == OrderStatus.PROTECTION_FAILED
                || order.status() == OrderStatus.PROTECTION_REPLAN_REQUIRED
                || order.status() == OrderStatus.PROTECTION_RULES_STALE) {
            return true;
        }
        boolean protectionAttention = protections != null && protections.stream()
                .anyMatch(item -> "EMERGENCY_ATTENTION_REQUIRED".equalsIgnoreCase(item.status())
                        || "PROTECTION_CANCEL_FAILED".equalsIgnoreCase(item.status()));
        boolean closeAttention = closeFact != null && hasText(closeFact.errorMessage())
                && closeFact.errorMessage().startsWith("POSITION_QUERY_FAILED");
        return protectionAttention || closeAttention;
    }

    private boolean shouldCancelStaleEntry(PendingOrder order, Duration age) {
        return order.status() == OrderStatus.SUBMITTED
                && age.toMinutes() >= Math.max(1, agentProperties.lifecycle().entryTimeoutMinutes());
    }

    private boolean shouldRecoverMissingProtection(PendingOrder order) {
        return (order.status() == OrderStatus.ENTRY_TIMEOUT_CANCELLED
                || order.status() == OrderStatus.PROTECTION_FAILED
                || order.status() == OrderStatus.PROTECTION_REPLAN_REQUIRED
                || order.status() == OrderStatus.PROTECTION_RULES_STALE);
    }

    private boolean hasMatchingPendingOrder(PositionSummary position) {
        return pendingOrderService.allOrders().stream().anyMatch(order ->
                PositionSideMatcher.matches(order.instId(), order.posSide(), order.side(), position));
    }

    private boolean recoverMissingProtection(PendingOrder order, PositionSummary position) {
        if (order.budgetReservationId() != null) {
            budgetService.markUsed(order.budgetReservationId());
        }
        OrderStatus previousStatus = order.status();
        try {
            BigDecimal positionSize = decimal(position.size());
            BigDecimal avgPx = positive(position.avgPrice(), order.price());
            EntryFill entryFill = queryEntryFill(order);
            if (!hasText(entryFill.state())) {
                throw new IllegalStateException("ENTRY_STATE_UNAVAILABLE_DURING_PROTECTION_RECOVERY");
            }
            ProtectionSubmitResult protection = submitProtection(order, avgPx, positionSize,
                    "MISSING_PROTECTION_RECOVERY", entryFill.complete());
            if (protection == ProtectionSubmitResult.COMPLETE) {
                order.markProtectionSubmitted("MISSING_PROTECTION_RECOVERED");
            }
            recordEvent(order, protection == ProtectionSubmitResult.COMPLETE
                            ? TradeEventType.PROTECTION_SUBMITTED : TradeEventType.PROTECTION_FAILED,
                    previousStatus.name(), order.status().name(),
                    protection == ProtectionSubmitResult.COMPLETE
                            ? "MISSING_PROTECTION_RECOVERED" : "MISSING_PROTECTION_REPLAN_REQUIRED",
                    "检测到已有持仓但无有效保护单，已按当前 canonical 保护状态处理",
                    order.externalOrderId(), order.clientOrderId(), null);
            return true;
        } catch (RuntimeException ex) {
            if (!triggerSafetyClose(order, position, "CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY", ex)) {
                order.markProtectionFailed("MISSING_PROTECTION_RECOVERY_FAILED: " + compact(ex.getMessage()));
                recordEvent(order, TradeEventType.PROTECTION_FAILED, previousStatus.name(),
                        OrderStatus.PROTECTION_FAILED.name(), "MISSING_PROTECTION_RECOVERY_FAILED",
                        ex.getMessage(), order.externalOrderId(), order.clientOrderId(), null);
            }
            return false;
        }
    }

    private boolean shouldAdjustSideways(PendingOrder order, PositionSummary position, Duration age) {
        if (order.status() == OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED
                || order.status() == OrderStatus.CLOSE_SUBMITTED
                || order.status() == OrderStatus.CLOSED) {
            return false;
        }
        if (age.toMinutes() < effectiveSidewaysMinutes()) {
            return false;
        }
        BigDecimal pnlPct = pnlPct(position);
        return pnlPct.abs().compareTo(agentProperties.lifecycle().sidewaysPnlRangePct()) <= 0;
    }

    private long configuredSidewaysMinutes() {
        return Math.max(1, agentProperties.lifecycle().sidewaysPositionHours()) * 60L;
    }

    private long effectiveSidewaysMinutes() {
        long maxHoldMinutes = Math.max(1, agentProperties.lifecycle().maxHoldMinutes());
        return Math.min(configuredSidewaysMinutes(), Math.max(1, maxHoldMinutes - 1));
    }

    private boolean shouldForceClose(PendingOrder order, PositionSummary position, Duration age) {
        if (order.status() == OrderStatus.CLOSE_SUBMITTED || order.status() == OrderStatus.CLOSED) {
            return false;
        }
        long minutes = age.toMinutes();
        if (minutes >= Math.max(1, agentProperties.lifecycle().maxHoldMinutes())) {
            return true;
        }
        return minutes >= Math.max(1, agentProperties.lifecycle().lossCloseMinutes())
                && position.unrealizedPnl() != null
                && position.unrealizedPnl().signum() < 0;
    }

    private int tightenTakeProfit(PendingOrder order, PositionSummary position) {
        BigDecimal avgPx = positive(position.avgPrice(), order.price());
        OkxInstrumentRules rules = instrumentRules(order.instId());
        BigDecimal size = rules.floorToLot(decimal(position.size()));
        if (size.signum() <= 0 || size.compareTo(rules.minSize()) < 0) {
            throw new IllegalStateException("SIDEWAYS_POSITION_MIN_SIZE_UNEXPRESSIBLE");
        }
        BigDecimal triggerPx = smallProfitTakeProfit(order.posSide(), avgPx);
        List<ActiveAlgoProtection> active = activeAlgoProtections(order);
        // A prior TP fill can reduce the live position while the original
        // stop still covers the entry quantity. Reconcile the canonical stop
        // quantity before changing profit-side protection so the managed
        // position never reports stale SL coverage.
        CanonicalExecutablePlan canonicalPlan = requireCanonicalPlan(order);
        ActiveAlgoProtection existingStop = active.stream()
                .filter(item -> "STOP_LOSS".equalsIgnoreCase(item.role()))
                .findFirst().orElse(null);
        if (existingStop == null || existingStop.size().compareTo(size) != 0) {
            BigDecimal canonicalStop = canonicalPlanCompiler.normalizeStopPrice(
                    canonicalPlan.stopLoss().intendedPrice(), order.action(), rules);
            Map<String, String> stopPayload = stopLossPayload(order, canonicalPlan, rules, size,
                    avgPx, existingStop == null ? "SIDEWAYS_SL" : "SIDEWAYS_REPLACE_SL",
                    preserveProtectedStop(order, existingStop, canonicalStop));
            placeProtection(order, stopPayload, TradingOperationType.PLACE_STOP_LOSS);
            requireActiveProtection(order, stopPayload.get("algoClOrdId"), "STOP_LOSS");
            if (existingStop != null) {
                cancelAlgo(order, existingStop.clientOrderId());
            }
            active = activeAlgoProtections(order);
        }
        BigDecimal existingSidewaysSize = active.stream()
                .filter(item -> "SIDEWAYS_TP".equalsIgnoreCase(item.role()))
                .map(ActiveAlgoProtection::size)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal supersededSize = active.stream()
                .filter(item -> isTakeProfitRole(item.role()))
                .filter(item -> !"SIDEWAYS_TP".equalsIgnoreCase(item.role()))
                .map(ActiveAlgoProtection::size)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal targetSidewaysSize = size.subtract(supersededSize).subtract(existingSidewaysSize).max(BigDecimal.ZERO);
        if (existingSidewaysSize.compareTo(size) == 0 && supersededSize.signum() == 0) {
            String sidewaysClientId = profitAlgoClientId(order, "SIDEWAYS_TP", size);
            cancelSupersededTakeProfits(order, active, sidewaysClientId);
            updateSidewaysIntent(order, sidewaysClientId, size, triggerPx, null, "PROTECTION_SUBMITTED");
            order.markSidewaysTimeoutTpAdjusted("SIDEWAYS_TP_ALREADY_ACTIVE");
            return 0;
        }
        if (targetSidewaysSize.signum() <= 0) {
            // The exchange adapter has no amend-algo primitive. Transfer one
            // existing TP tranche at a time: cancel that tranche, then place
            // an equal-size SIDEWAYS tranche. The durable intent makes the
            // intermediate state restartable and the canonical SL remains
            // untouched throughout.
            ActiveAlgoProtection tranche = active.stream()
                    .filter(item -> "TP1".equalsIgnoreCase(item.role())
                            || "TP2".equalsIgnoreCase(item.role())
                            || "TP3".equalsIgnoreCase(item.role()))
                    .findFirst().orElse(null);
            if (tranche == null || tranche.size().signum() <= 0) {
                throw new IllegalStateException("SIDEWAYS_REPLACEMENT_RECONCILE_REQUIRED");
            }
            // The quantity alone is not a stable identity while a staged
            // replacement is in progress: two different canonical legs may
            // have the same size. Bind the new tranche to the superseded
            // order id so a later scheduler pass cannot overwrite an
            // already-active SIDEWAYS tranche or create a duplicate.
            String trancheClientId = profitAlgoClientId(order, "SIDEWAYS_TP", tranche.size(),
                    tranche.clientOrderId());
            updateSidewaysIntent(order, trancheClientId, size, triggerPx, null,
                    "SIDEWAYS_REPLACEMENT_CANCEL_PENDING");
            cancelAlgo(order, tranche.clientOrderId());
            if (activeAlgoProtections(order).stream()
                    .anyMatch(item -> tranche.clientOrderId().equals(item.clientOrderId()))) {
                throw new IllegalStateException("SIDEWAYS_OLD_TP_CANCEL_NOT_CONFIRMED");
            }
            Map<String, String> tranchePayload = algoPayload(order, tranche.size(), "SIDEWAYS_TP");
            tranchePayload.put("algoClOrdId", trancheClientId);
            tranchePayload.put("tpTriggerPx", value(triggerPx));
            tranchePayload.put("tpOrdPx", "-1");
            JsonNode trancheResponse = okxOrderGateway.placeAlgoOrder(tranchePayload,
                    operationContext(order, TradingOperationType.PLACE_TAKE_PROFIT));
            requireActiveProtection(order, trancheClientId, "SIDEWAYS_TP");
            updateSidewaysIntent(order, trancheClientId, size, triggerPx, trancheResponse,
                    "PROTECTION_SUBMITTED");
            order.markSidewaysTimeoutTpAdjusted("SIDEWAYS_REPLACEMENT_STAGED");
            return 1;
        }
        if (targetSidewaysSize.compareTo(rules.minSize()) < 0
                || targetSidewaysSize.remainder(rules.lotSize()).signum() != 0) {
            throw new IllegalStateException("SIDEWAYS_TP_UNEXPRESSIBLE_MIN_OR_LOT_SIZE");
        }
        String sidewaysClientId = profitAlgoClientId(order, "SIDEWAYS_TP", targetSidewaysSize);
        updateSidewaysIntent(order, sidewaysClientId, size, triggerPx, null, "PROTECTION_SUBMITTING");
        Map<String, String> payload = algoPayload(order, targetSidewaysSize, "SIDEWAYS_TP");
        payload.put("algoClOrdId", sidewaysClientId);
        payload.put("tpTriggerPx", value(triggerPx));
        payload.put("tpOrdPx", "-1");
        JsonNode response = okxOrderGateway.placeAlgoOrder(payload,
                operationContext(order, TradingOperationType.PLACE_TAKE_PROFIT));
        requireActiveProtection(order, payload.get("algoClOrdId"), "SIDEWAYS_TP");
        // Persist the fact that the replacement is already active before
        // touching superseded orders. A crash after this point must retain
        // the new protection and only continue cleanup on restart.
        updateSidewaysIntent(order, sidewaysClientId, size, triggerPx, response,
                "TRANSITIONAL_REPLACEMENT");
        cancelSupersededTakeProfits(order, active, sidewaysClientId);
        boolean supersededStillActive = activeAlgoProtections(order).stream()
                .anyMatch(item -> isTakeProfitRole(item.role())
                        && !"SIDEWAYS_TP".equalsIgnoreCase(item.role()));
        if (supersededStillActive) {
            // The new reduce-only tranche is bounded by the current position;
            // do not claim reconciliation until the old set is observed gone.
            updateSidewaysIntent(order, sidewaysClientId, size, triggerPx, response,
                    "TRANSITIONAL_REPLACEMENT");
            throw new IllegalStateException("SIDEWAYS_OLD_TP_CANCEL_PENDING");
        }
        updateSidewaysIntent(order, sidewaysClientId, size, triggerPx, response, "PROTECTION_SUBMITTED");
        order.markSidewaysTimeoutTpAdjusted("SIDEWAYS_TIMEOUT_TP_ADJUSTED");
        recordEvent(order, TradeEventType.SIDEWAYS_TP_REPLACED, OrderStatus.PROTECTION_SUBMITTED.name(),
                OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED.name(), "SIDEWAYS_TP_REPLACED",
                "横盘超时后替换为小盈利止盈保护单", null, payload.get("algoClOrdId"), algoId(response));
        return 1;
    }

    /**
     * Reconciles profit-side protection without ever creating quantity above
     * the current position. Price/role semantics come exclusively from the
     * canonical legs; only the outstanding quantity is allocated here.
     */
    private boolean reconcileProfitProtection(PendingOrder order,
                                               List<CanonicalExecutablePlan.ExitLeg> desired,
                                               BigDecimal positionSize,
                                               List<ActiveAlgoProtection> active) {
        boolean changed = false;
        Set<String> desiredRoles = desired.stream().map(CanonicalExecutablePlan.ExitLeg::semanticId)
                .collect(java.util.stream.Collectors.toSet());
        for (CanonicalExecutablePlan.ExitLeg leg : desired) {
            BigDecimal activeSize = active.stream()
                    .filter(item -> leg.semanticId().equalsIgnoreCase(item.role()))
                    .map(ActiveAlgoProtection::size)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal target = leg.executableContractQuantity();
            BigDecimal delta = target.subtract(activeSize);
            if (delta.signum() > 0) {
                OkxInstrumentRules rules = instrumentRules(order.instId());
                if (delta.compareTo(rules.minSize()) < 0
                        || delta.remainder(rules.lotSize()).signum() != 0) {
                    throw new IllegalStateException("TP_INCREMENT_UNEXPRESSIBLE_MIN_OR_LOT_SIZE");
                }
                BigDecimal totalActive = active.stream().filter(item -> isTakeProfitRole(item.role()))
                        .map(ActiveAlgoProtection::size).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (totalActive.add(delta).compareTo(positionSize) > 0) {
                    throw new IllegalStateException("TP_PROTECTION_QUANTITY_EXCEEDS_POSITION");
                }
                Map<String, String> payload = algoPayload(order, delta,
                        "RECONCILE_" + leg.semanticId());
                payload.put("algoClOrdId", profitAlgoClientId(order, leg.semanticId(), delta));
                payload.put("orderRole", leg.semanticId());
                payload.put("tpTriggerPx", value(leg.executableTargetPrice()));
                payload.put("tpOrdPx", "-1");
                placeProtection(order, payload, TradingOperationType.PLACE_TAKE_PROFIT);
                // An accepted request is not protection.  Read the exchange
                // state back before treating this tranche as reconciled; a
                // lost/late response is retried by the same deterministic
                // client id on the next lifecycle pass while the old
                // protection remains in force.
                requireActiveProtection(order, payload.get("algoClOrdId"), leg.semanticId());
                changed = true;
            } else if (delta.signum() < 0) {
                // Reducing an existing leg is deliberately cancel-then-place;
                // the old protection remains in force until the replacement
                // is confirmed on the next pass. Never over-allocate while
                // waiting for that convergence.
                active.stream().filter(item -> leg.semanticId().equalsIgnoreCase(item.role()))
                        .forEach(item -> cancelAlgo(order, item.clientOrderId()));
                changed = true;
            }
        }
        active.stream().filter(item -> isTakeProfitRole(item.role()))
                .filter(item -> !desiredRoles.contains(item.role()))
                .forEach(item -> cancelAlgo(order, item.clientOrderId()));
        return changed;
    }

    private void cancelSupersededTakeProfits(PendingOrder order, List<ActiveAlgoProtection> active, String keepClientId) {
        active.stream()
                .filter(item -> "TP1".equalsIgnoreCase(item.role())
                        || "TP2".equalsIgnoreCase(item.role())
                        || "TP3".equalsIgnoreCase(item.role()))
                .filter(item -> !keepClientId.equals(item.clientOrderId()))
                .forEach(item -> cancelAlgo(order, item.clientOrderId()));
    }

    private void updateSidewaysIntent(PendingOrder order, String clientId, BigDecimal size,
                                      BigDecimal triggerPx, JsonNode response, String status) {
        if (tradeOrderRepository == null) {
            return;
        }
        List<TradeOrderEntity> intents = tradeOrderRepository
                .findByPendingOrderIdOrderByCreatedAtAsc(order.id().toString());
        TradeOrderEntity entity = (intents == null ? List.<TradeOrderEntity>of() : intents).stream()
                .filter(item -> "SIDEWAYS_TP".equals(item.getOrderRole()))
                .filter(item -> clientId.equals(item.getClOrdId()))
                .findFirst().orElseGet(TradeOrderEntity::new);
        Instant now = Instant.now();
        entity.setUserName(currentUsername());
        entity.setTradePlanId(order.tradePlanId() == null ? null : order.tradePlanId().toString());
        entity.setPendingOrderId(order.id().toString());
        entity.setOrderRole("SIDEWAYS_TP");
        entity.setInstId(order.instId());
        entity.setSide(closeSide(order));
        entity.setPosSide(order.posSide());
        entity.setOrdType("conditional");
        entity.setTdMode(order.tdMode());
        entity.setSize(size);
        entity.setPrice(triggerPx);
        entity.setReduceOnly(true);
        entity.setClOrdId(clientId);
        if (response != null) {
            entity.setOkxOrdId(algoId(response));
        }
        entity.setOkxState(status);
        entity.setStatus(status);
        entity.setCreatedAt(entity.getCreatedAt() == null ? now : entity.getCreatedAt());
        entity.setUpdatedAt(now);
        tradeOrderRepository.save(entity);
    }

    private int submitMaxHoldClose(PendingOrder order, PositionSummary position, Instant now) {
        Map<String, String> payload = new LinkedHashMap<>();
        String closeClOrdId = "MAXC" + shortHash(order.id().toString(), 24);
        ClosePositionRecordEntity unresolved = unresolvedCloseIntent(order);
        if (unresolved != null) {
            order.markCloseSubmitted(unresolved.getCloseOrderId(), "MAX_HOLD_CLOSE_RECOVERY_PENDING");
            return 0;
        }
        payload.put("instId", order.instId());
        payload.put("mgnMode", order.tdMode());
        payload.put("autoCxl", "true");
        payload.put("clOrdId", closeClOrdId);
        // Use the observed exchange mode: local order direction may be long/short in NET mode.
        if (hasText(position.posSide()) && !"net".equalsIgnoreCase(position.posSide())) {
            payload.put("posSide", position.posSide());
        }
        ClosePositionRecordEntity intent = createMaxHoldCloseIntent(order, position, closeClOrdId, now);
        if (intent != null) {
            String intentOwner = intent.getUserName();
            String pendingOrderId = intent.getPendingOrderId();
            try {
                closePositionRecordRepository.saveAndFlush(intent);
            } catch (DataIntegrityViolationException collision) {
                ClosePositionRecordEntity concurrentIntent = closePositionRecordRepository
                        .findByUserNameAndCloseClOrdId(intentOwner, closeClOrdId)
                        .or(() -> closePositionRecordRepository
                                .findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
                                        intentOwner, pendingOrderId).stream().findFirst())
                        .orElseThrow(() -> collision);
                if (isUnresolvedCloseIntent(concurrentIntent)) {
                    order.markCloseSubmitted(concurrentIntent.getCloseOrderId(),
                            "MAX_HOLD_CLOSE_RECOVERY_PENDING");
                    return 0;
                }
                if (!isRetryableCloseIntent(concurrentIntent)
                        || closePositionRecordRepository.claimRetry(concurrentIntent.getId(), now) != 1) {
                    order.markEmergencyAttentionRequired("MAX_HOLD_CLOSE_INTENT_CONFLICT");
                    return 0;
                }
                resetCloseIntentForRetry(concurrentIntent, intent, now);
                intent = concurrentIntent;
            }
        }
        try {
            JsonNode response = okxOrderGateway.closePosition(payload,
                    operationContext(order, TradingOperationType.CLOSE_POSITION));
            String closeOrderId = closeId(response);
            order.markCloseSubmitted(closeOrderId, "MAX_HOLD_TIMEOUT_CLOSE_SUBMITTED");
            if (intent != null) {
                intent.setCloseOrderId(closeOrderId);
                intent.setStatus("CLOSE_SUBMITTED");
                intent.setRequestSent(true);
                intent.setErrorClass(null);
                intent.setRecoveryResult("ACK_SUBMITTED");
                intent.setUpdatedAt(Instant.now());
                closePositionRecordRepository.save(intent);
            }
            recordEvent(order, TradeEventType.MAX_HOLD_CLOSE_SUBMITTED, OrderStatus.PROTECTION_SUBMITTED.name(),
                    OrderStatus.CLOSE_SUBMITTED.name(), "MAX_HOLD_TIMEOUT_CLOSE_SUBMITTED",
                    "达到最大持仓时间后提交平仓", closeOrderId, order.clientOrderId(), null);
            return 1;
        } catch (RuntimeException ex) {
            if (intent != null) {
                String error = String.valueOf(ex.getMessage());
                boolean beforeSend = error.contains("未发送") || error.contains("未向交易所发送")
                        || error.contains("NOT_CONFIRMED");
                boolean rejected = error.toLowerCase().contains("okx code=")
                        || error.toLowerCase().contains("okx http ");
                intent.setStatus(beforeSend ? "CLOSE_FAILED_BEFORE_SEND"
                        : rejected ? "CLOSE_REJECTED" : "CLOSE_SUBMIT_UNKNOWN");
                intent.setRequestSent(!beforeSend);
                intent.setErrorClass(beforeSend ? "LOCAL_VALIDATION"
                        : rejected ? "EXCHANGE_REJECTED" : "UNKNOWN_AFTER_SEND");
                intent.setRecoveryResult(beforeSend ? "NOT_SENT"
                        : rejected ? "TERMINAL_REJECTED" : "QUERY_REQUIRED");
                intent.setErrorMessage(ex.getMessage());
                intent.setUpdatedAt(Instant.now());
                closePositionRecordRepository.save(intent);
            }
            order.markEmergencyAttentionRequired("MAX_HOLD_TIMEOUT_CLOSE_FAILED: " + ex.getMessage());
            recordEvent(order, TradeEventType.RECOVERY_FAILED, order.status().name(),
                    OrderStatus.EMERGENCY_ATTENTION_REQUIRED.name(), "MAX_HOLD_TIMEOUT_CLOSE_FAILED",
                    ex.getMessage(), null, order.clientOrderId(), null);
            return 0;
        }
    }

    private ClosePositionRecordEntity unresolvedCloseIntent(PendingOrder order) {
        if (closePositionRecordRepository == null || order == null || order.id() == null) {
            return null;
        }
        String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        return closePositionRecordRepository
                .findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(owner, order.id().toString()).stream()
                .filter(record -> Set.of("CLOSE_SUBMITTING", "CLOSE_SUBMIT_UNKNOWN", "CLOSE_SUBMITTED")
                        .contains(record.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private static boolean isUnresolvedCloseIntent(ClosePositionRecordEntity record) {
        return record != null && Set.of("CLOSE_SUBMITTING", "CLOSE_SUBMIT_UNKNOWN", "CLOSE_SUBMITTED")
                .contains(record.getStatus());
    }

    private static boolean isRetryableCloseIntent(ClosePositionRecordEntity record) {
        return record != null && Set.of("CLOSE_FAILED_BEFORE_SEND", "CLOSE_REJECTED")
                .contains(record.getStatus());
    }

    private static void resetCloseIntentForRetry(ClosePositionRecordEntity existing,
                                                 ClosePositionRecordEntity current,
                                                 Instant now) {
        existing.setInstId(current.getInstId());
        existing.setPosSide(current.getPosSide());
        existing.setMarginMode(current.getMarginMode());
        existing.setSize(current.getSize());
        existing.setAvgPx(current.getAvgPx());
        existing.setStatus("CLOSE_SUBMITTING");
        existing.setRequestSent(false);
        existing.setErrorMessage(null);
        existing.setErrorClass(null);
        existing.setRecoveryResult("RETRY_CLAIMED");
        existing.setUpdatedAt(now);
    }

    private ClosePositionRecordEntity createMaxHoldCloseIntent(PendingOrder order, PositionSummary position,
                                                                String closeClOrdId, Instant now) {
        if (closePositionRecordRepository == null) {
            return null;
        }
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName(AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER));
        record.setInstId(order.instId());
        record.setPosSide(order.posSide());
        record.setMarginMode(order.tdMode());
        record.setCloseClOrdId(closeClOrdId);
        record.setSize(decimal(position.size()));
        record.setAvgPx(position.avgPrice());
        record.setStatus("CLOSE_SUBMITTING");
        record.setRequestSent(false);
        record.setSource("MAX_HOLD_TIMEOUT");
        record.setPendingOrderId(order.id().toString());
        record.setTradePlanId(order.tradePlanId() == null ? null : order.tradePlanId().toString());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    private ProtectionSubmitResult submitProtection(PendingOrder order, BigDecimal avgPx, BigDecimal filledSize,
                                                    String suffix, boolean attachedStopLossExpected) {
        OkxInstrumentRules rules = instrumentRules(order.instId());
        BigDecimal normalizedSize = rules.floorToLot(filledSize);
        if (normalizedSize.signum() <= 0 || normalizedSize.compareTo(rules.minSize()) < 0) {
            throw new IllegalStateException("POSITION_MIN_SIZE_UNEXPRESSIBLE");
        }
        CanonicalExecutablePlan canonicalPlan = requireCanonicalPlan(order);
        List<ActiveAlgoProtection> active = activeAlgoProtections(order);
        String attachedId = OkxTradeAdapter.attachedStopLossClientOrderId(order);
        boolean attachedActive = active.stream().anyMatch(item -> attachedId.equals(item.clientOrderId()));
        if (attachedStopLossExpected && !attachedActive) {
            log.warn("Attached SL missing after fill; using canonical standalone SL recovery pendingOrderId={}", order.id());
        }
        ActiveAlgoProtection existingStop = active.stream()
                .filter(item -> "STOP_LOSS".equals(item.role()))
                .findFirst().orElse(null);
        if (existingStop == null || existingStop.size().compareTo(normalizedSize) != 0) {
            String stopSuffix = existingStop == null ? suffix + "_SL" : suffix + "_REPLACE_SL";
            BigDecimal canonicalStop = canonicalPlanCompiler.normalizeStopPrice(
                    canonicalPlan.stopLoss().intendedPrice(), order.action(), rules);
            Map<String, String> stopLoss = stopLossPayload(order, canonicalPlan, rules, normalizedSize,
                    avgPx, stopSuffix, preserveProtectedStop(order, existingStop, canonicalStop));
            placeProtection(order, stopLoss, TradingOperationType.PLACE_STOP_LOSS);
            requireActiveProtection(order, stopLoss.get("algoClOrdId"), "STOP_LOSS");
            if (existingStop != null) {
                cancelAlgo(order, existingStop.clientOrderId());
            }
        }

        boolean rulesChanged = !canonicalPlanCompiler.matchesSnapshot(canonicalPlan, rules);
        ProtectionAllocation allocation = rulesChanged
                ? canonicalPlanCompiler.allocateProtectionForCurrentRules(canonicalPlan, normalizedSize, rules)
                : canonicalPlanCompiler.allocateProtection(canonicalPlan, normalizedSize, rules);
        if (allocation.status() != ProtectionAllocation.Status.FULL) {
            order.markProtectionReplanRequired("TP_LADDER_UNEXPRESSIBLE: "
                    + String.join(",", allocation.decisions()));
            return ProtectionSubmitResult.TP_REPLAN_REQUIRED;
        }
        if (exitLegLedgerService == null && ledgerFactsAvailable()) {
            throw new IllegalStateException("EXIT_LEDGER_SERVICE_UNAVAILABLE");
        }
        if (exitLegLedgerService == null) {
            reconcileProfitProtection(order, allocation.takeProfitLegs(), normalizedSize, active);
        } else {
            reconcileProfitProtectionFromLedger(order, canonicalPlan, allocation.takeProfitLegs(),
                    normalizedSize, active, Instant.now());
        }
        return ProtectionSubmitResult.COMPLETE;
    }

    private CanonicalExecutablePlan requireCanonicalPlan(PendingOrder order) {
        CanonicalExecutablePlan plan = order.canonicalExecutablePlan();
        if (plan == null || !plan.accepted()) {
            throw new IllegalStateException("CANONICAL_PLAN_MISSING");
        }
        return plan;
    }

    private JsonNode placeProtection(PendingOrder order, Map<String, String> payload,
                                      TradingOperationType operationType) {
        // Keep the exact identity through unknown ACK/local persistence failures.
        submittedProtectionIds.computeIfAbsent(order.id(), key -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(payload.get("algoClOrdId"));
        JsonNode response = okxOrderGateway.placeAlgoOrder(apiAlgoPayload(payload),
                operationContext(order, operationType));
        recordProtectionSubmitted(order, payload, response);
        String role = payload.get("orderRole");
        if (exitLegLedgerService != null && isTakeProfitRole(role)) {
            exitLegLedgerService.recordSubmitted(currentUsername(), order.id().toString(), role,
                    payload.get("algoClOrdId"), algoId(response), Instant.now());
        }
        return response;
    }

    private void requireActiveProtection(PendingOrder order, String clientOrderId, String role) {
        boolean active = activeAlgoProtections(order).stream()
                .anyMatch(item -> clientOrderId.equals(item.clientOrderId())
                        && role.equals(item.role()));
        if (!active) {
            throw new IllegalStateException(role + "_NOT_RECONCILED");
        }
    }

    private Set<String> activeAlgoClientOrderIds() {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("instType", "SWAP");
        request.put("ordType", "conditional");
        JsonNode root = okxOrderGateway.currentAlgoOrders(request);
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.isArray()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : data) {
            String state = item.path("state").asText("");
            String clientOrderId = item.path("algoClOrdId").asText("");
            if (hasText(clientOrderId) && (state.isBlank() || "live".equalsIgnoreCase(state))) {
                ids.add(clientOrderId);
            }
        }
        return ids;
    }

    private List<ActiveAlgoProtection> activeAlgoProtections(PendingOrder order) {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("instType", "SWAP");
        request.put("ordType", "conditional");
        JsonNode root = okxOrderGateway.currentAlgoOrders(request);
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }
        Set<String> ownedIds = ownedProtectionIds(order);
        List<ActiveAlgoProtection> result = new ArrayList<>();
        for (JsonNode item : data) {
            String state = item.path("state").asText("");
            if (!(state.isBlank() || "live".equalsIgnoreCase(state)
                    || "effective".equalsIgnoreCase(state)
                    || "partially_effective".equalsIgnoreCase(state))) {
                continue;
            }
            String clientId = item.path("algoClOrdId").asText("");
            if (!hasText(clientId)) {
                continue;
            }
            String itemInstId = item.path("instId").asText("");
            if (!order.instId().equalsIgnoreCase(itemInstId)
                    || !closeSide(order).equalsIgnoreCase(item.path("side").asText(""))
                    || !(order.posSide().equalsIgnoreCase(item.path("posSide").asText(""))
                         || "net".equalsIgnoreCase(item.path("posSide").asText("")))
                    || !(ownedIds.contains(clientId) || isOrderBoundProfitId(order, clientId))) {
                continue;
            }
            String role;
            if (hasText(item.path("slTriggerPx").asText("")) || clientId.toLowerCase().endsWith("sl")) {
                role = "STOP_LOSS";
            } else if (clientId.toLowerCase().endsWith("tp1")) {
                role = "TP1";
            } else if (clientId.toLowerCase().endsWith("tp2")) {
                role = "TP2";
            } else if (clientId.toLowerCase().endsWith("tp3")) {
                role = "TP3";
            } else if (clientId.toLowerCase().endsWith("swt")) {
                role = "SIDEWAYS_TP";
            } else {
                role = "TAKE_PROFIT";
            }
            String trigger = item.path("slTriggerPx").asText("");
            if (!hasText(trigger)) {
                trigger = item.path("tpTriggerPx").asText("");
            }
            BigDecimal activeSize = decimal(item, "actualSz");
            if (activeSize.signum() <= 0) {
                activeSize = decimal(item, "sz");
            }
            result.add(new ActiveAlgoProtection(clientId, role, activeSize, decimal(trigger)));
        }
        return List.copyOf(result);
    }

    private Set<String> ownedProtectionIds(PendingOrder order) {
        Set<String> ids = new LinkedHashSet<>(submittedProtectionIds.getOrDefault(order.id(), Set.of()));
        ids.add(OkxTradeAdapter.attachedStopLossClientOrderId(order));
        for (String role : List.of("SL", "REPLACE_SL", "SIDEWAYS_TP", "TP1", "TP2", "TP3")) {
            ids.add(lifecycleAlgoClOrdId(order, role));
        }
        if (tradeOrderRepository != null) {
            tradeOrderRepository.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc(currentUsername(), order.id().toString())
                    .stream().filter(row -> currentUsername().equals(row.getUserName()))
                    .filter(row -> order.id().toString().equals(row.getPendingOrderId()))
                    .filter(row -> order.instId().equalsIgnoreCase(row.getInstId()) && row.isReduceOnly())
                    .filter(row -> closeSide(order).equalsIgnoreCase(row.getSide()))
                    .filter(row -> order.posSide().equalsIgnoreCase(row.getPosSide()) || "net".equalsIgnoreCase(row.getPosSide()))
                    .map(TradeOrderEntity::getClOrdId).filter(AutoTradeLifecycleService::hasText).forEach(ids::add);
        }
        return ids;
    }

    private static boolean isOrderBoundProfitId(PendingOrder order, String clientId) {
        return clientId.matches("lc" + shortHash(order.id().toString(), 12) + "[a-f0-9]{12}(TP1|TP2|TP3|SWT)");
    }

    private Map<String, String> stopLossPayload(PendingOrder order, CanonicalExecutablePlan plan,
                                                OkxInstrumentRules rules, BigDecimal size,
                                                BigDecimal actualEntryPrice, String suffix) {
        return stopLossPayload(order, plan, rules, size, actualEntryPrice, suffix, null);
    }

    /**
     * @param stopOverride explicit stop price, used when a profit-protection
     *                     move must survive a canonical re-placement. The
     *                     canonical initial stop is the default and stays the
     *                     source of R; the override only prevents an already
     *                     locked profit from being released.
     */
    private Map<String, String> stopLossPayload(PendingOrder order, CanonicalExecutablePlan plan,
                                                OkxInstrumentRules rules, BigDecimal size,
                                                BigDecimal actualEntryPrice, String suffix,
                                                BigDecimal stopOverride) {
        Map<String, String> payload = algoPayload(order, size, suffix);
        payload.put("orderRole", "STOP_LOSS");
        BigDecimal intended = stopOverride != null && stopOverride.signum() > 0
                ? stopOverride
                : plan.stopLoss().intendedPrice();
        BigDecimal stop = canonicalPlanCompiler.normalizeStopPrice(intended, order.action(), rules);
        if (stop == null || stop.signum() <= 0) {
            throw new IllegalStateException("CANONICAL_STOP_LOSS_UNEXPRESSIBLE");
        }
        BigDecimal riskBudget = plan.risk() == null ? null : plan.risk().riskBudget();
        BigDecimal riskEntry = actualEntryPrice != null && actualEntryPrice.signum() > 0
                ? actualEntryPrice
                : plan.entry() == null ? order.price() : plan.entry().normalizedEntryPrice();
        BigDecimal executableRisk = canonicalPlanCompiler.calculateExecutableStopLossAmount(
                riskEntry,
                stop, size, rules);
        if (riskBudget != null && riskBudget.signum() > 0 && executableRisk.compareTo(riskBudget) > 0) {
            throw new IllegalStateException("EXECUTABLE_STOP_LOSS_RISK_EXCEEDS_BUDGET");
        }
        payload.put("slTriggerPx", value(stop));
        payload.put("slOrdPx", "-1");
        return payload;
    }

    private boolean reconcileCanonicalProtection(PendingOrder order, PositionSummary position) {
        CanonicalExecutablePlan plan;
        try {
            plan = requireCanonicalPlan(order);
        } catch (RuntimeException ex) {
            List<ActiveAlgoProtection> active;
            try {
                active = activeAlgoProtections(order);
            } catch (RuntimeException queryFailure) {
                triggerSafetyClose(order, position, "CANONICAL_PLAN_UNAVAILABLE_AFTER_ENTRY", queryFailure);
                return true;
            }
            if (hasValidStop(active, decimal(position.size()))) {
                order.markEmergencyAttentionRequired("CANONICAL_PLAN_UNAVAILABLE_WITH_VALID_SL");
                return true;
            }
            if (triggerSafetyClose(order, position, "CANONICAL_PLAN_UNAVAILABLE_AFTER_ENTRY", ex)) {
                return true;
            }
            order.markEmergencyAttentionRequired("CANONICAL_PLAN_MISSING_ACTIVE_POSITION");
            return true;
        }
        OkxInstrumentRules rules = instrumentRules(order.instId());
        BigDecimal size = rules.floorToLot(decimal(position.size()));
        if (size.signum() <= 0) {
            return false;
        }
        List<ActiveAlgoProtection> active = activeAlgoProtections(order);
        boolean changed = false;
        ActiveAlgoProtection stop = active.stream()
                .filter(item -> "STOP_LOSS".equals(item.role()))
                .findFirst().orElse(null);
        BigDecimal canonicalStop = canonicalPlanCompiler.normalizeStopPrice(
                plan.stopLoss().intendedPrice(), order.action(), rules);
        // Profit protection may already have moved the stop toward profit. The
        // canonical initial stop is the floor, never a target to come back to:
        // re-placing protection must not release locked profit.
        BigDecimal desiredStop = preserveProtectedStop(order, stop, canonicalStop);
        boolean priceNeedsFix = stop != null && stop.triggerPrice().signum() > 0
                && !improvesOrEquals(stop.triggerPrice(), desiredStop, order.action());
        try {
            if (stop == null) {
                Map<String, String> stopPayload = stopLossPayload(order, plan, rules, size,
                        position.avgPrice(), "RECONCILE_SL", desiredStop);
                placeProtection(order, stopPayload, TradingOperationType.PLACE_STOP_LOSS);
                requireActiveProtection(order, stopPayload.get("algoClOrdId"), "STOP_LOSS");
                changed = true;
            } else if (stop.size().compareTo(size) != 0 || priceNeedsFix) {
                // The gateway exposes OKX's amend-algos endpoint. Prefer an
                // in-place size/price amendment so the old and new stop can
                // never coexist with aggregate close quantity above the live
                // position. If read-back cannot prove the amendment, fail
                // closed and retain the old stop.
                if (amendCanonicalStopInPlace(order, stop, desiredStop, size)) {
                    changed = true;
                } else {
                    // Fallback is still place-new-first.  Both the existing
                    // and replacement payloads are reduceOnly/close-side
                    // protection orders, so a later trigger can only reduce
                    // the remaining position; it cannot open the opposite
                    // side.  The old order is cancelled only after the new
                    // order is read back as active.
                    Map<String, String> replacement = stopLossPayload(order, plan, rules, size,
                            position.avgPrice(), "REPLACE_SL", desiredStop);
                    placeProtection(order, replacement, TradingOperationType.PLACE_STOP_LOSS);
                    requireActiveProtection(order, replacement.get("algoClOrdId"), "STOP_LOSS");
                    cancelAlgo(order, stop.clientOrderId());
                    changed = true;
                }
            }
        } catch (RuntimeException ex) {
            if (triggerSafetyClose(order, position, "CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY", ex)) {
                return true;
            }
            throw ex;
        }
        boolean rulesChanged = !canonicalPlanCompiler.matchesSnapshot(plan, rules);
        ProtectionAllocation allocation = rulesChanged
                ? canonicalPlanCompiler.allocateProtectionForCurrentRules(plan, size, rules)
                : canonicalPlanCompiler.allocateProtection(plan, size, rules);
        if (allocation.status() != ProtectionAllocation.Status.FULL) {
            order.markProtectionReplanRequired("TP_LADDER_UNEXPRESSIBLE: "
                    + String.join(",", allocation.decisions()));
            return true;
        }
        if (exitLegLedgerService == null && ledgerFactsAvailable()) {
            throw new IllegalStateException("EXIT_LEDGER_SERVICE_UNAVAILABLE");
        }
        if (exitLegLedgerService == null) {
            changed |= reconcileProfitProtection(order, allocation.takeProfitLegs(), size, active);
        } else {
            changed |= reconcileProfitProtectionFromLedger(order, plan, allocation.takeProfitLegs(),
                    size, active, Instant.now());
        }
        return changed;
    }

    private boolean ledgerFactsAvailable() {
        return tradeOrderRepository != null && tradeFillRepository != null;
    }

    private boolean amendCanonicalStopInPlace(PendingOrder order, ActiveAlgoProtection stop,
                                               BigDecimal desiredStop, BigDecimal desiredSize) {
        if (stop == null || !hasText(stop.clientOrderId())) {
            return false;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("algoClOrdId", stop.clientOrderId());
        payload.put("newSlTriggerPx", value(desiredStop));
        payload.put("newSz", value(desiredSize));
        payload.put("reqId", "amend-" + shortHash(order.id() + ":" + value(desiredStop)
                + ":" + value(desiredSize), 16));
        try {
            JsonNode response = okxOrderGateway.amendAlgoOrder(payload,
                    operationContext(order, TradingOperationType.AMEND_PROTECTION));
            if (response == null) {
                return false;
            }
            return activeAlgoProtections(order).stream()
                    .filter(item -> stop.clientOrderId().equals(item.clientOrderId()))
                    .anyMatch(item -> item.triggerPrice() != null
                            && item.triggerPrice().compareTo(desiredStop) == 0
                            && item.size() != null && item.size().compareTo(desiredSize) == 0);
        } catch (RuntimeException ex) {
            log.warn("canonical stop amend uncertain pendingOrderId={} reason={}", order.id(), ex.getMessage());
            return false;
        }
    }

    private boolean reconcileProfitProtectionFromLedger(PendingOrder order,
                                                         CanonicalExecutablePlan plan,
                                                         List<CanonicalExecutablePlan.ExitLeg> legs,
                                                         BigDecimal positionSize,
                                                         List<ActiveAlgoProtection> active,
                                                         Instant now) {
        List<ExitLegLedgerService.ActiveProtection> activeLegs = active.stream()
                .filter(item -> isTakeProfitRole(item.role()))
                .map(item -> new ExitLegLedgerService.ActiveProtection(item.clientOrderId(), item.role(), item.size()))
                .toList();
        ExitLegLedgerService.Reconciliation reconciliation = exitLegLedgerService.reconcile(
                currentUsername(), order.id().toString(),
                plan.planId() == null ? null : plan.planId().toString(), order.instId(), legs,
                positionSize, activeLegs, now);
        boolean changed = false;
        for (String role : reconciliation.cancelRoles()) {
            active.stream().filter(item -> role.equalsIgnoreCase(item.role()))
                    .forEach(item -> cancelAlgo(order, item.clientOrderId()));
            changed = true;
        }
        OkxInstrumentRules rules = instrumentRules(order.instId());
        for (Map.Entry<String, BigDecimal> entry : reconciliation.submitQuantities().entrySet()) {
            BigDecimal quantity = entry.getValue();
            if (quantity.signum() <= 0 || quantity.compareTo(rules.minSize()) < 0
                    || quantity.remainder(rules.lotSize()).signum() != 0) {
                throw new IllegalStateException("TP_LEDGER_INCREMENT_UNEXPRESSIBLE_MIN_OR_LOT_SIZE");
            }
            CanonicalExecutablePlan.ExitLeg leg = legs.stream()
                    .filter(item -> entry.getKey().equals(item.semanticId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("TP_LEDGER_LEG_NOT_IN_PLAN:" + entry.getKey()));
            Map<String, String> payload = algoPayload(order, quantity, "RECONCILE_" + leg.semanticId());
            payload.put("algoClOrdId", profitAlgoClientId(order, leg.semanticId(), quantity));
            payload.put("orderRole", leg.semanticId());
            payload.put("tpTriggerPx", value(leg.executableTargetPrice()));
            payload.put("tpOrdPx", "-1");
            placeProtection(order, payload, TradingOperationType.PLACE_TAKE_PROFIT);
            requireActiveProtection(order, payload.get("algoClOrdId"), leg.semanticId());
            changed = true;
        }
        return changed;
    }

    private void reconcilePositionGone(PendingOrder order, Instant now) {
        try {
            List<ActiveAlgoProtection> active = activeAlgoProtections(order);
            for (ActiveAlgoProtection item : active) {
                if (isTakeProfitRole(item.role()) || "STOP_LOSS".equalsIgnoreCase(item.role())) {
                    cancelAlgo(order, item.clientOrderId());
                }
            }
            if (exitLegLedgerService != null) {
                exitLegLedgerService.markFlat(currentUsername(), order.id().toString(), now);
            }
        } catch (RuntimeException ex) {
            order.markEmergencyAttentionRequired("POSITION_GONE_PROTECTION_CANCEL_UNCONFIRMED");
            log.warn("position gone protection cleanup uncertain pendingOrderId={} reason={}",
                    order.id(), ex.getMessage());
        }
    }

    private boolean triggerSafetyClose(PendingOrder order, PositionSummary position, String reason,
                                       RuntimeException cause) {
        if (positionCloseService == null) {
            order.markEmergencyAttentionRequired(reason + ": SAFETY_CLOSE_SERVICE_UNAVAILABLE");
            return false;
        }
        try {
            OrderStatus before = order.status();
            com.example.quant.order.OrderExecutionResult result =
                    positionCloseService.closePositionForSafety(order, position, reason);
            order.markCloseSubmitted(result.externalOrderId(), reason);
            recordEvent(order, TradeEventType.RECOVERY_FAILED, before.name(),
                    OrderStatus.CLOSE_SUBMITTED.name(), "TERMINAL_SAFETY_REDUCTION",
                    reason + (cause == null ? "" : ": " + compact(cause.getMessage())),
                    null, order.clientOrderId(), null);
            return true;
        } catch (RuntimeException safetyEx) {
            order.markEmergencyAttentionRequired(reason + ": SAFETY_CLOSE_FAILED: "
                    + compact(safetyEx.getMessage()));
            recordEvent(order, TradeEventType.RECOVERY_FAILED, order.status().name(),
                    OrderStatus.EMERGENCY_ATTENTION_REQUIRED.name(), "TERMINAL_SAFETY_REDUCTION_FAILED",
                    reason + ": " + compact(safetyEx.getMessage()), null, order.clientOrderId(), null);
            return false;
        }
    }

    private static boolean isProtectionSafetyEmergency(PendingOrder order) {
        String reason = order.rejectReason();
        return hasText(reason) && (reason.toUpperCase().contains("CANONICAL")
                || reason.toUpperCase().contains("SAFETY_CLOSE"));
    }

    private static boolean canonicalPlanAvailable(PendingOrder order) {
        try {
            CanonicalExecutablePlan plan = order.canonicalExecutablePlan();
            return plan != null && plan.accepted();
        } catch (RuntimeException ex) {
            // Unknown schema/invalid snapshot is deliberately unavailable to
            // active lifecycle code. Never reconstruct it from legacy fields.
            return false;
        }
    }

    private static boolean hasValidStop(List<ActiveAlgoProtection> protections, BigDecimal positionSize) {
        if (protections == null || positionSize == null || positionSize.signum() <= 0) {
            return false;
        }
        return protections.stream()
                .filter(item -> "STOP_LOSS".equalsIgnoreCase(item.role()))
                .anyMatch(item -> item.size() != null && item.size().compareTo(positionSize) == 0
                        && item.triggerPrice() != null && item.triggerPrice().signum() > 0);
    }

    private void cancelAlgo(PendingOrder order, String clientOrderId) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("algoClOrdId", clientOrderId);
        OkxCancelAcknowledgement.requireSuccess(okxOrderGateway.cancelAlgoOrder(payload));
    }

    /**
     * Keeps whichever stop is more protective: the canonical initial stop or the
     * one profit protection already secured on the exchange.
     */
    private static BigDecimal preserveProtectedStop(PendingOrder order, ActiveAlgoProtection stop,
                                                    BigDecimal canonicalStop) {
        if (stop == null || stop.triggerPrice() == null || stop.triggerPrice().signum() <= 0) {
            return canonicalStop;
        }
        return improvesOrEquals(stop.triggerPrice(), canonicalStop, order.action())
                ? stop.triggerPrice()
                : canonicalStop;
    }

    /** LONG: higher is better. SHORT: lower is better. */
    private static boolean improvesOrEquals(BigDecimal candidate, BigDecimal reference,
                                            com.example.quant.tradeplan.TradePlanType action) {
        if (reference == null || reference.signum() <= 0) {
            return true;
        }
        return action == com.example.quant.tradeplan.TradePlanType.OPEN_SHORT
                ? candidate.compareTo(reference) <= 0
                : candidate.compareTo(reference) >= 0;
    }

    private static ExchangeStopView exchangeStopView(List<ActiveAlgoProtection> active) {
        if (active == null) {
            return null;
        }
        ActiveAlgoProtection stop = active.stream()
                .filter(item -> "STOP_LOSS".equalsIgnoreCase(item.role()))
                .findFirst()
                .orElse(null);
        if (stop == null) {
            return null;
        }
        return new ExchangeStopView(stop.clientOrderId(), null, stop.triggerPrice(), stop.size());
    }

    private static boolean managedProtectionStatus(OrderStatus status) {
        return status == OrderStatus.PROTECTION_SUBMITTED
                || status == OrderStatus.ACTIVE
                || status == OrderStatus.ACTIVE_WITH_TP_WARNING
                || status == OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED
                || status == OrderStatus.PROTECTION_REPLAN_REQUIRED
                || status == OrderStatus.PROTECTION_RULES_STALE;
    }

    private static TradingOperationContext operationContext(PendingOrder order,
                                                            TradingOperationType operationType) {
        return TradingOperationContext.of(
                operationType,
                currentUsername(),
                order == null ? null : order.id().toString(),
                order == null || order.tradePlanId() == null ? null : order.tradePlanId().toString());
    }

    private void recordProtectionSubmitted(PendingOrder order, Map<String, String> payload, JsonNode response) {
        if (tradeOrderRepository == null) {
            return;
        }
        Instant now = Instant.now();
        TradeOrderEntity entity = new TradeOrderEntity();
        entity.setUserName(currentUsername());
        entity.setTradePlanId(order.tradePlanId() == null ? null : order.tradePlanId().toString());
        entity.setPendingOrderId(order.id().toString());
        entity.setOrderRole(payload.getOrDefault("orderRole", "TAKE_PROFIT"));
        entity.setInstId(order.instId());
        entity.setSide(payload.get("side"));
        entity.setPosSide(order.posSide());
        entity.setOrdType(payload.get("ordType"));
        entity.setTdMode(order.tdMode());
        entity.setSize(decimal(payload.get("sz")));
        entity.setPrice(triggerPrice(payload));
        entity.setReduceOnly(true);
        entity.setClOrdId(payload.get("algoClOrdId"));
        entity.setOkxOrdId(algoId(response));
        entity.setOkxState("live");
        entity.setStatus("PROTECTION_SUBMITTED");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        tradeOrderRepository.save(entity);
    }

    private OkxInstrumentRules instrumentRules(String instId) {
        OkxInstrumentRules rules = instrumentRulesProvider.rules(instId);
        if (rules == null) {
            throw new IllegalStateException("无法加载OKX合约规则，禁止提交保护单：" + instId);
        }
        return rules;
    }

    private static BigDecimal triggerPrice(Map<String, String> payload) {
        String value = payload.getOrDefault("slTriggerPx", payload.getOrDefault("tpTriggerPx", "0"));
        return decimal(value);
    }

    private Map<String, String> algoPayload(PendingOrder order, BigDecimal size, String suffix) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("tdMode", order.tdMode());
        payload.put("side", closeSide(order));
        if (positionMode() == OkxPositionMode.LONG_SHORT) {
            payload.put("posSide", order.posSide());
        }
        payload.put("ordType", "conditional");
        payload.put("reduceOnly", "true");
        payload.put("sz", value(size));
        payload.put("algoClOrdId", lifecycleAlgoClOrdId(order, suffix));
        return payload;
    }

    private static Map<String, String> apiAlgoPayload(Map<String, String> payload) {
        Map<String, String> apiPayload = new LinkedHashMap<>(payload);
        apiPayload.remove("orderRole");
        return apiPayload;
    }

    private EntryFill queryEntryFill(PendingOrder order) {
        if (okxOrderGateway == null) {
            return new EntryFill(BigDecimal.ZERO, BigDecimal.ZERO, "");
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        if (hasText(order.externalOrderId())) {
            payload.put("ordId", order.externalOrderId());
        } else if (hasText(order.clientOrderId())) {
            payload.put("clOrdId", order.clientOrderId());
        }
        JsonNode item = firstDataItem(okxOrderGateway.queryOrder(payload));
        if (item == null) {
            return new EntryFill(BigDecimal.ZERO, BigDecimal.ZERO, "");
        }
        return new EntryFill(decimal(item, "avgPx"), decimal(item, "accFillSz"), item.path("state").asText(""));
    }

    private EntryFill cancelEntryOrder(PendingOrder order) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        if (hasText(order.externalOrderId())) {
            payload.put("ordId", order.externalOrderId());
        } else if (hasText(order.clientOrderId())) {
            payload.put("clOrdId", order.clientOrderId());
        }
        try {
            requireCancelAcknowledgement(okxOrderGateway.cancelOrder(payload));
        } catch (RuntimeException ex) {
            if (!isAlreadyClosedCancel(ex)) {
                throw ex;
            }
        }
        EntryFill reconciled = queryEntryFill(order);
        return reconciled.complete() || "canceled".equalsIgnoreCase(reconciled.state()) ? reconciled : null;
    }

    private static void requireCancelAcknowledgement(JsonNode response) {
        JsonNode item = firstDataItem(response);
        String code = response == null ? "" : response.path("code").asText("");
        String sCode = item == null ? "" : item.path("sCode").asText("");
        if (!"0".equals(code) || !"0".equals(sCode)) {
            String message = item == null ? "" : item.path("sMsg").asText("");
            throw new IllegalStateException("OKX cancel not acknowledged: code=" + code
                    + ", sCode=" + sCode + ", sMsg=" + compact(message));
        }
    }

    private static boolean isAlreadyClosedCancel(RuntimeException ex) {
        String message = ex.getMessage();
        if (!hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("51400") || lower.contains("has been filled, canceled or does not exist");
    }

    private PositionSummary matchingPosition(PendingOrder order, List<PositionSummary> positions) {
        return positions.stream()
                .filter(position -> PositionSideMatcher.matches(order.instId(), order.posSide(), order.side(), position))
                .findFirst()
                .orElse(null);
    }

    private static String protectionStatus(PendingOrder order) {
        return switch (order.status()) {
            case PROTECTION_SUBMITTED, ACTIVE, ACTIVE_WITH_TP_WARNING, SIDEWAYS_TIMEOUT_TP_ADJUSTED -> "PROTECTION_SUBMITTED";
            case PROTECTION_FAILED, PROTECTION_REPLAN_REQUIRED, PROTECTION_RULES_STALE,
                    EMERGENCY_ATTENTION_REQUIRED -> "PROTECTION_FAILED";
            default -> "PROTECTION_PENDING";
        };
    }

    private static String lifecycleStatus(PendingOrder order, PositionSummary position) {
        if (order.status() == OrderStatus.SUBMITTED) {
            return "ENTRY_PENDING";
        }
        return order.status().name();
    }

    private static String nextAction(PendingOrder order) {
        return switch (order.status()) {
            case SUBMITTED -> "WAIT_ENTRY_FILL_OR_TIMEOUT";
            case ENTRY_TIMEOUT_CANCELLED -> "FALLBACK_NEXT_CANDIDATE_ALLOWED";
            case SIDEWAYS_TIMEOUT_TP_ADJUSTED -> "WAIT_SMALL_PROFIT_TP_OR_MAX_HOLD";
            case CLOSE_SUBMITTED -> "WAIT_CLOSE_SYNC";
            case EMERGENCY_ATTENTION_REQUIRED -> "MANUAL_ATTENTION_REQUIRED";
            case PROTECTION_REPLAN_REQUIRED -> "PROTECTION_REPLAN_REQUIRED";
            case PROTECTION_RULES_STALE -> "PROTECTION_RULES_STALE";
            default -> "MONITOR";
        };
    }

    private PositionSyncResult positions() {
        if (positionSnapshotService == null) {
            return PositionSyncResult.available(List.of());
        }
        try {
            return PositionSyncResult.available(positionSnapshotService.positions());
        } catch (RuntimeException ex) {
            return PositionSyncResult.unavailable();
        }
    }

    private OkxPositionMode positionMode() {
        try {
            OkxPositionMode mode = positionModeProvider.positionMode();
            return mode == null ? OkxPositionMode.NET : mode;
        } catch (RuntimeException ex) {
            return OkxPositionMode.NET;
        }
    }

    private void recordEvent(PendingOrder order, TradeEventType eventType, String oldStatus, String newStatus,
                             String reasonCode, String reasonMessage, String okxOrdId, String clOrdId, String algoId) {
        if (tradeEventService == null || order == null) {
            return;
        }
        try {
            tradeEventService.record(new TradeEventPayload(
                    null,
                    order.instId(),
                    order.id().toString(),
                    null,
                    null,
                    eventType,
                    oldStatus,
                    newStatus,
                    reasonCode,
                    reasonMessage,
                    okxOrdId,
                    clOrdId,
                    algoId
            ));
        } catch (RuntimeException ex) {
            // Event persistence must not interrupt recovery/lifecycle handling.
        }
    }

    private BigDecimal smallProfitTakeProfit(String posSide, BigDecimal avgPx) {
        BigDecimal profitRate = agentProperties.lifecycle().sidewaysExitProfitPct()
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal factor = "short".equalsIgnoreCase(posSide)
                ? BigDecimal.ONE.subtract(profitRate)
                : BigDecimal.ONE.add(profitRate);
        return avgPx.multiply(factor).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static BigDecimal pnlPct(PositionSummary position) {
        BigDecimal notional = position.notionalUsd() == null ? BigDecimal.ZERO : position.notionalUsd().abs();
        if (notional.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal pnl = position.unrealizedPnl() == null ? BigDecimal.ZERO : position.unrealizedPnl();
        return pnl.multiply(BigDecimal.valueOf(100)).divide(notional, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal positive(BigDecimal preferred, BigDecimal fallback) {
        return preferred != null && preferred.signum() > 0 ? preferred : fallback;
    }

    private static String closeSide(PendingOrder order) {
        return "buy".equalsIgnoreCase(order.side()) ? "sell" : "buy";
    }

    private static String lifecycleAlgoClOrdId(PendingOrder order, String suffix) {
        String cleanSuffix = suffix == null ? "" : suffix.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        String typeCode = lifecycleTypeCode(cleanSuffix);
        return "lc" + shortHash(order.id().toString(), 12) + typeCode;
    }

    private static String profitAlgoClientId(PendingOrder order, String role, BigDecimal quantity) {
        return profitAlgoClientId(order, role, quantity, null);
    }

    private static String profitAlgoClientId(PendingOrder order, String role, BigDecimal quantity,
                                             String identitySeed) {
        String cleanRole = role == null ? "TP" : role.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        String typeCode = lifecycleTypeCode(cleanRole);
        String quantityKey = quantity == null ? "0" : quantity.stripTrailingZeros().toPlainString();
        String seed = hasText(identitySeed) ? identitySeed : cleanRole + ":" + quantityKey;
        return "lc" + shortHash(order.id().toString(), 12) + shortHash(seed, 12) + typeCode;
    }

    private static String lifecycleTypeCode(String cleanSuffix) {
        if (cleanSuffix.contains("REPLACE") && cleanSuffix.contains("SL")) {
            return "SLR";
        }
        if (cleanSuffix.contains("SL") || cleanSuffix.contains("STOP")) {
            return "SL";
        }
        if (cleanSuffix.contains("SIDEWAYS")) {
            return "SWT";
        }
        if (cleanSuffix.contains("TP1")) {
            return "TP1";
        }
        if (cleanSuffix.contains("TP2")) {
            return "TP2";
        }
        if (cleanSuffix.contains("TP3")) {
            return "TP3";
        }
        return "LC";
    }

    private static String shortHash(String value, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(Character.forDigit((item >> 4) & 0xf, 16));
                builder.append(Character.forDigit(item & 0xf, 16));
            }
            return builder.substring(0, Math.min(length, builder.length()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static JsonNode firstDataItem(JsonNode response) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return null;
        }
        return data.get(0);
    }

    private static String closeId(JsonNode response) {
        JsonNode item = firstDataItem(response);
        return item == null ? null : item.path("ordId").asText("");
    }

    private static String algoId(JsonNode response) {
        JsonNode item = firstDataItem(response);
        return item == null ? null : item.path("algoId").asText("");
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null) {
            return BigDecimal.ZERO;
        }
        return decimal(node.path(field).asText(""));
    }

    private static BigDecimal decimal(String value) {
        if (!hasText(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value).abs();
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static String value(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    private static boolean ownedByCurrentUser(TradeOrderEntity row) {
        return row != null && currentUsername().equals(row.getUserName());
    }

    private static boolean isTakeProfitRole(String role) {
        return "TP1".equalsIgnoreCase(role) || "TP2".equalsIgnoreCase(role)
                || "TP3".equalsIgnoreCase(role) || "SIDEWAYS_TP".equalsIgnoreCase(role);
    }

    private static String compact(String value) {
        if (!hasText(value)) {
            return "unknown";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private record EntryFill(BigDecimal avgPx, BigDecimal filledSize, String state) {
        boolean complete() {
            return filledSize != null && filledSize.signum() > 0 && "filled".equalsIgnoreCase(state);
        }
    }

    private enum ProtectionSubmitResult {
        COMPLETE,
        TP_REPLAN_REQUIRED,
        RULES_STALE
    }

    private record ActiveAlgoProtection(String clientOrderId, String role, BigDecimal size,
                                        BigDecimal triggerPrice) {
    }

    private record PositionSyncResult(boolean available, List<PositionSummary> positions) {
        static PositionSyncResult available(List<PositionSummary> positions) {
            return new PositionSyncResult(true, positions == null ? List.of() : positions);
        }

        static PositionSyncResult unavailable() {
            return new PositionSyncResult(false, List.of());
        }
    }

    public record LifecycleRunResult(
            int entryTimeoutCancelled,
            int entryFilledProtected,
            int partialFillProtected,
            int sidewaysTpAdjusted,
            int maxHoldCloseSubmitted,
            int positionSyncUnavailable,
            int orphanPositionProtected
    ) {
        public LifecycleRunResult(int entryTimeoutCancelled, int partialFillProtected, int sidewaysTpAdjusted,
                                  int maxHoldCloseSubmitted) {
            this(entryTimeoutCancelled, 0, partialFillProtected, sidewaysTpAdjusted, maxHoldCloseSubmitted, 0, 0);
        }

        public LifecycleRunResult(int entryTimeoutCancelled, int entryFilledProtected, int partialFillProtected,
                                  int sidewaysTpAdjusted, int maxHoldCloseSubmitted, int positionSyncUnavailable) {
            this(entryTimeoutCancelled, entryFilledProtected, partialFillProtected, sidewaysTpAdjusted,
                    maxHoldCloseSubmitted, positionSyncUnavailable, 0);
        }
    }
}

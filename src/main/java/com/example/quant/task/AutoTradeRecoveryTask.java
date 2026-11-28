package com.example.quant.task;

import com.example.quant.account.ClosePositionRecoveryService;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.budget.BudgetReservationStatus;
import com.example.quant.agent.event.TradeEventPayload;
import com.example.quant.agent.event.TradeEventService;
import com.example.quant.agent.event.TradeEventType;
import com.example.quant.agent.lifecycle.AutoTradeLifecycleService;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.okxtrade.OkxCurrentOrderSyncService;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.system.SystemControlService;
import java.time.Instant;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AutoTradeRecoveryTask {
    private static final Logger log = LoggerFactory.getLogger(AutoTradeRecoveryTask.class);
    private static final Set<OrderStatus> RELEASE_RESERVED_STATUSES = EnumSet.of(
            OrderStatus.REJECTED,
            OrderStatus.EXPIRED,
            OrderStatus.CANCELLED,
            OrderStatus.FAILED
    );

    private final PendingOrderService pendingOrderService;
    private final AutoTradeBudgetService budgetService;
    private final AgentProperties agentProperties;
    private final OkxTradeAdapter okxTradeAdapter;
    private final AutoTradeLifecycleService lifecycleService;
    private final ClosePositionRecoveryService closePositionRecoveryService;
    private final OkxCurrentOrderSyncService currentOrderSyncService;
    private final SystemControlService systemControlService;
    private final TradeEventService tradeEventService;
    private com.example.quant.agent.platform.TradingAgentRepository tradingAgentRepository;

    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties) {
        this(pendingOrderService, budgetService, agentProperties, null, null, null, null, null);
    }

    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties, OkxTradeAdapter okxTradeAdapter,
                                 AutoTradeLifecycleService lifecycleService,
                                 ClosePositionRecoveryService closePositionRecoveryService) {
        this(pendingOrderService, budgetService, agentProperties, okxTradeAdapter, lifecycleService,
                closePositionRecoveryService, null, null);
    }

    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties, OkxTradeAdapter okxTradeAdapter,
                                 AutoTradeLifecycleService lifecycleService,
                                 ClosePositionRecoveryService closePositionRecoveryService,
                                 OkxCurrentOrderSyncService currentOrderSyncService,
                                 SystemControlService systemControlService) {
        this(pendingOrderService, budgetService, agentProperties, okxTradeAdapter, lifecycleService,
                closePositionRecoveryService, currentOrderSyncService, systemControlService, null);
    }

    @Autowired
    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties, OkxTradeAdapter okxTradeAdapter,
                                 AutoTradeLifecycleService lifecycleService,
                                 ClosePositionRecoveryService closePositionRecoveryService,
                                 OkxCurrentOrderSyncService currentOrderSyncService,
                                 SystemControlService systemControlService,
                                 TradeEventService tradeEventService) {
        this.pendingOrderService = pendingOrderService;
        this.budgetService = budgetService;
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
        this.okxTradeAdapter = okxTradeAdapter;
        this.lifecycleService = lifecycleService;
        this.closePositionRecoveryService = closePositionRecoveryService;
        this.currentOrderSyncService = currentOrderSyncService;
        this.systemControlService = systemControlService;
        this.tradeEventService = tradeEventService;
    }

    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties, OkxTradeAdapter okxTradeAdapter,
                                 AutoTradeLifecycleService lifecycleService,
                                 ClosePositionRecoveryService closePositionRecoveryService,
                                 OkxCurrentOrderSyncService currentOrderSyncService) {
        this(pendingOrderService, budgetService, agentProperties, okxTradeAdapter, lifecycleService,
                closePositionRecoveryService, currentOrderSyncService, null);
    }

    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties, OkxTradeAdapter okxTradeAdapter) {
        this(pendingOrderService, budgetService, agentProperties, okxTradeAdapter, null, null, null, null);
    }

    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties, OkxTradeAdapter okxTradeAdapter,
                                 AutoTradeLifecycleService lifecycleService) {
        this(pendingOrderService, budgetService, agentProperties, okxTradeAdapter, lifecycleService, null, null, null);
    }

    @Autowired(required = false)
    void configureTradingAgentRepository(com.example.quant.agent.platform.TradingAgentRepository tradingAgentRepository) {
        this.tradingAgentRepository = tradingAgentRepository;
    }

    @Scheduled(
            fixedRateString = "${quant.agent.recovery.interval-seconds:30}",
            initialDelayString = "${quant.agent.recovery.interval-seconds:30}",
            timeUnit = TimeUnit.SECONDS
    )
    public void runScheduled() {
        runOnce();
        recoverActiveAgentOwners();
    }

    void recoverActiveAgentOwners() {
        if (tradingAgentRepository == null || !agentProperties.recovery().enabled()) {
            return;
        }
        String primaryOwner = systemControlService == null ? "" : systemControlService.autoTradeOwnerUsername();
        tradingAgentRepository.findByStatusOrderByUpdatedAtAsc(com.example.quant.agent.platform.TradingAgentStatus.RUNNING)
                .stream()
                .filter(agent -> agent.getRunMode() != com.example.quant.agent.platform.TradingAgentRunMode.PAPER)
                .map(com.example.quant.agent.platform.TradingAgentEntity::getUserName)
                .filter(AutoTradeRecoveryTask::hasText)
                .filter(user -> !user.equals(primaryOwner))
                .distinct()
                .forEach(user -> {
                    try {
                        runOnceForOwner(user);
                    } catch (RuntimeException ex) {
                        log.warn("Isolated agent recovery failed for user={}: {}", user, ex.getMessage());
                        recordRecoveryFailed(user, null, "AGENT_USER_RECOVERY_FAILED", ex.getMessage());
                    }
                });
    }

    public RecoveryResult runOnce() {
        if (!agentProperties.recovery().enabled()) {
            return new RecoveryResult(0, 0, 0, 0, false);
        }
        Instant now = Instant.now();
        if (systemControlService != null) {
            String ownerUsername = systemControlService.autoTradeOwnerUsername();
            if (!hasText(ownerUsername)) {
                log.warn("AutoTrade recovery skipped: autoTradeOwnerUsername missing");
                recordRecoveryFailed(null, null, "AUTO_TRADE_OWNER_MISSING", "autoTradeOwnerUsername missing");
                return new RecoveryResult(0, 0, 1, 0, false);
            }
            try {
                return AuthUserContext.callAs(ownerUsername, () -> runOnceAsOwner(now));
            } catch (RuntimeException ex) {
                log.warn("AutoTrade recovery skipped for owner={} because recovery context failed: {}",
                        ownerUsername, ex.getMessage(), ex);
                recordRecoveryFailed(ownerUsername, null, "RECOVERY_CONTEXT_FAILED", ex.getMessage());
                return new RecoveryResult(0, 0, 1, 0, false);
            }
        }
        return runOnceAsOwner(now);
    }

    public RecoveryResult runOnceForOwner(String ownerUsername) {
        if (!agentProperties.recovery().enabled()) {
            return new RecoveryResult(0, 0, 0, 0, false);
        }
        if (!hasText(ownerUsername)) {
            return new RecoveryResult(0, 0, 1, 0, false);
        }
        return AuthUserContext.callAs(ownerUsername, () -> runOnceAsOwner(Instant.now()));
    }

    /** Active pre-canonical lifecycles are not reconstructed from legacy fields. */
    public int legacyActiveLifecycleCount() {
        return pendingOrderService.allOrders().stream()
                .filter(order -> order.submittedAt() != null)
                .filter(order -> order.status() == OrderStatus.SUBMITTED
                        || order.status() == OrderStatus.PROTECTION_SUBMITTED
                        || order.status() == OrderStatus.ACTIVE
                        || order.status() == OrderStatus.ACTIVE_WITH_TP_WARNING
                        || order.status() == OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED)
                .filter(order -> order.canonicalExecutablePlan() == null)
                .mapToInt(order -> 1)
                .sum();
    }

    /** V34 deployment is only safe at a flat, unresolved-lifecycle boundary. */
    public int unresolvedPendingOrderCount() {
        return (int) pendingOrderService.allOrders().stream()
                .filter(order -> switch (order.status()) {
                    case CANCELLED, EXPIRED, REJECTED, FAILED, CLOSED -> false;
                    default -> true;
                })
                .count();
    }

    public int unresolvedCloseIntentCount() {
        return closePositionRecoveryService == null
                ? 0 : closePositionRecoveryService.unresolvedCloseIntentCount();
    }

    private RecoveryResult runOnceAsOwner(Instant now) {
        int expiredOrders = 0;
        int releasedReservations = 0;
        int attentionRequired = 0;
        int recoveredUnknownSubmits = 0;
        boolean exchangeSynchronized = syncCurrentOkxOrders();
        for (PendingOrder order : pendingOrderService.allOrders()) {
        synchronized (order) {
                // CONFIRMING is persisted before risk checks and before any exchange call.
                // A restart in this state can therefore safely resume from the existing reservation.
                if (order.status() == OrderStatus.CONFIRMING) {
                    order.transition(OrderStatus.CONFIRMING, OrderStatus.BUDGET_RESERVED);
                }
                if (order.status() == OrderStatus.CONFIRMED) {
                    order.transition(OrderStatus.CONFIRMED, OrderStatus.BUDGET_RESERVED);
                }
                if (isReservablePendingStatus(order.status()) && order.isExpired(now)) {
                    order.markExpired();
                    expiredOrders++;
                    releasedReservations += releaseReservedBudget(order, "PENDING_ORDER_EXPIRED");
                    log.warn("AutoTrade recovery expired pending order: symbol={}, pendingOrderId={}, reservationId={}",
                            order.instId(), order.id(), order.budgetReservationId());
                    continue;
                }
                if (RELEASE_RESERVED_STATUSES.contains(order.status())) {
                    releasedReservations += releaseReservedBudget(order, "PENDING_ORDER_" + order.status());
                    continue;
                }
                if (order.status() == OrderStatus.CLOSED) {
                    releasedReservations += releaseAnyBudget(order, "POSITION_CLOSED");
                    continue;
                }
                if (order.status() == OrderStatus.SUBMITTING) {
                    order.markUnknownSubmitStatus("APPLICATION_RESTARTED_DURING_SUBMIT; reconcile by clOrdId="
                            + order.clientOrderId());
                }
                if (order.status() == OrderStatus.UNKNOWN_SUBMIT_STATUS) {
                    RecoveryDecision decision = recoverUnknownSubmit(order, now);
                    recoveredUnknownSubmits += decision.recovered();
                    releasedReservations += decision.released();
                    attentionRequired += decision.attentionRequired();
                    continue;
                }
                if (order.status() == OrderStatus.PROTECTION_FAILED
                        || order.status() == OrderStatus.PROTECTION_REPLAN_REQUIRED
                        || order.status() == OrderStatus.PROTECTION_RULES_STALE
                        || order.status() == OrderStatus.EMERGENCY_ATTENTION_REQUIRED
                ) {
                    attentionRequired++;
                }
            }
        }
        if (lifecycleService != null) {
            lifecycleService.runOnce(now);
        }
        if (closePositionRecoveryService != null) {
            closePositionRecoveryService.runOnce(now);
        }
        return new RecoveryResult(expiredOrders, releasedReservations, attentionRequired, recoveredUnknownSubmits,
                exchangeSynchronized);
    }

    private boolean syncCurrentOkxOrders() {
        if (currentOrderSyncService == null) {
            return true;
        }
        OkxCurrentOrderSyncService.SyncResult result = currentOrderSyncService.syncOnce();
        if (result == null || result.failed() || result.errorMessage() != null) {
            String message = result == null ? "NULL_SYNC_RESULT" : result.errorMessage();
            log.warn("AutoTrade recovery current OKX order sync failed: {}", message);
            recordRecoveryFailed(null, null, "CURRENT_OKX_ORDER_SYNC_FAILED", message);
            return false;
        }
        return true;
    }

    private RecoveryDecision recoverUnknownSubmit(PendingOrder order, Instant now) {
        if (order.requiresLegacyAccountingReconciliation()) {
            return new RecoveryDecision(0, 0, 1);
        }
        if (okxTradeAdapter == null || order.clientOrderId() == null || order.clientOrderId().isBlank()) {
            return new RecoveryDecision(0, 0, 1);
        }
        try {
            OrderExecutionResult result = okxTradeAdapter.recoverUnknownSubmitStatus(order);
            if (result.submitted()) {
                order.markSubmitted(Instant.now(), result.externalOrderId());
                if (result.filled() && order.budgetReservationId() != null) {
                    budgetService.markUsed(order.budgetReservationId());
                }
                log.warn("AutoTrade recovery restored unknown OKX submit: symbol={}, pendingOrderId={}, clOrdId={}, okxOrdId={}",
                        order.instId(), order.id(), order.clientOrderId(), result.externalOrderId());
                recordEvent(order, TradeEventType.ENTRY_SUBMITTED, OrderStatus.UNKNOWN_SUBMIT_STATUS.name(),
                        OrderStatus.SUBMITTED.name(), "UNKNOWN_SUBMIT_RECOVERED", result.message(),
                        result.externalOrderId(), order.clientOrderId(), null);
                return new RecoveryDecision(1, 0, 0);
            }
            if (result.message() != null && result.message().contains("OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT")) {
                if (!unknownSubmitSafeWaitElapsed(order, now)) {
                    return new RecoveryDecision(0, 0, 1);
                }
                order.markUnknownSubmitStatus(result.message()
                        + "; 自动释放已禁止，请人工核对OKX订单、成交和仓位后处理");
                return new RecoveryDecision(0, 0, 1);
            }
            return new RecoveryDecision(0, 0, 1);
        } catch (RuntimeException ex) {
            log.warn("AutoTrade recovery could not resolve unknown submit status: symbol={}, pendingOrderId={}, clOrdId={}, message={}",
                    order.instId(), order.id(), order.clientOrderId(), ex.getMessage());
            recordEvent(order, TradeEventType.RECOVERY_FAILED, OrderStatus.UNKNOWN_SUBMIT_STATUS.name(),
                    OrderStatus.UNKNOWN_SUBMIT_STATUS.name(), "UNKNOWN_SUBMIT_RECOVERY_FAILED", ex.getMessage(),
                    null, order.clientOrderId(), null);
            return new RecoveryDecision(0, 0, 1);
        }
    }

    private int releaseReservedBudget(PendingOrder order, String reason) {
        if (order.budgetReservationId() == null) {
            return 0;
        }
        return budgetService.reservation(order.budgetReservationId())
                .filter(reservation -> reservation.status() == BudgetReservationStatus.RESERVED)
                .map(reservation -> {
                    budgetService.release(reservation.reservationId(), reason);
                    log.warn("AutoTrade recovery released reserved budget: symbol={}, pendingOrderId={}, reservationId={}, reason={}",
                            order.instId(), order.id(), reservation.reservationId(), reason);
                    recordEvent(order, TradeEventType.BUDGET_RELEASED, order.status().name(), order.status().name(),
                            reason, reason, null, order.clientOrderId(), null);
                    return 1;
                })
                .orElse(0);
    }

    private int releaseAnyBudget(PendingOrder order, String reason) {
        if (order.budgetReservationId() == null) {
            return 0;
        }
        BudgetReservation before = budgetService.reservation(order.budgetReservationId()).orElse(null);
        if (before == null || before.status() == BudgetReservationStatus.RELEASED) {
            return 0;
        }
        budgetService.release(before.reservationId(), reason);
        log.warn("AutoTrade recovery released budget: symbol={}, pendingOrderId={}, reservationId={}, previousStatus={}, reason={}",
                order.instId(), order.id(), before.reservationId(), before.status(), reason);
        recordEvent(order, TradeEventType.BUDGET_RELEASED, before.status().name(), "RELEASED",
                reason, reason, null, order.clientOrderId(), null);
        return 1;
    }

    private void recordRecoveryFailed(String userName, String instId, String reasonCode, String message) {
        if (tradeEventService == null) {
            return;
        }
        try {
            tradeEventService.record(new TradeEventPayload(
                    userName,
                    instId,
                    null,
                    null,
                    null,
                    TradeEventType.RECOVERY_FAILED,
                    null,
                    null,
                    reasonCode,
                    message,
                    null,
                    null,
                    null
            ));
        } catch (RuntimeException ex) {
            log.warn("Failed to write trade event: {}", ex.getMessage());
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
            log.warn("Failed to write trade event: {}", ex.getMessage());
        }
    }

    private static boolean isReservablePendingStatus(OrderStatus status) {
        return status == OrderStatus.PENDING_CONFIRM || status == OrderStatus.BUDGET_RESERVED;
    }

    private boolean unknownSubmitSafeWaitElapsed(PendingOrder order, Instant now) {
        Instant from = order.submittedAt() != null
                ? order.submittedAt()
                : order.confirmedAt() != null ? order.confirmedAt() : order.createdAt();
        long timeoutSeconds = Math.max(0, agentProperties.recovery().unknownSubmitStatusTimeoutSeconds());
        return Duration.between(from, now).getSeconds() >= timeoutSeconds;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record RecoveryDecision(int recovered, int released, int attentionRequired) {
    }

    public record RecoveryResult(int expiredOrders, int releasedReservations, int attentionRequired,
                                 int recoveredUnknownSubmits, boolean exchangeSynchronized) {
    }
}

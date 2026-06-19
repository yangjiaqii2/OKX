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

    @Scheduled(
            fixedRateString = "${quant.agent.recovery.interval-seconds:30}",
            initialDelayString = "${quant.agent.recovery.interval-seconds:30}",
            timeUnit = TimeUnit.SECONDS
    )
    public void runScheduled() {
        runOnce();
    }

    public RecoveryResult runOnce() {
        if (!agentProperties.recovery().enabled()) {
            return new RecoveryResult(0, 0, 0, 0);
        }
        Instant now = Instant.now();
        if (systemControlService != null) {
            String ownerUsername = systemControlService.autoTradeOwnerUsername();
            if (!hasText(ownerUsername)) {
                log.warn("AutoTrade recovery skipped: autoTradeOwnerUsername missing");
                recordRecoveryFailed(null, null, "AUTO_TRADE_OWNER_MISSING", "autoTradeOwnerUsername missing");
                return new RecoveryResult(0, 0, 1, 0);
            }
            try {
                return AuthUserContext.callAs(ownerUsername, () -> runOnceAsOwner(now));
            } catch (RuntimeException ex) {
                log.warn("AutoTrade recovery skipped for owner={} because recovery context failed: {}",
                        ownerUsername, ex.getMessage(), ex);
                recordRecoveryFailed(ownerUsername, null, "RECOVERY_CONTEXT_FAILED", ex.getMessage());
                return new RecoveryResult(0, 0, 1, 0);
            }
        }
        return runOnceAsOwner(now);
    }

    private RecoveryResult runOnceAsOwner(Instant now) {
        int expiredOrders = 0;
        int releasedReservations = 0;
        int attentionRequired = 0;
        int recoveredUnknownSubmits = 0;
        syncCurrentOkxOrders();
        for (PendingOrder order : pendingOrderService.allOrders()) {
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
            if (order.status() == OrderStatus.UNKNOWN_SUBMIT_STATUS) {
                RecoveryDecision decision = recoverUnknownSubmit(order, now);
                recoveredUnknownSubmits += decision.recovered();
                releasedReservations += decision.released();
                attentionRequired += decision.attentionRequired();
                continue;
            }
            if (order.status() == OrderStatus.PROTECTION_FAILED
                    || order.status() == OrderStatus.EMERGENCY_ATTENTION_REQUIRED
            ) {
                attentionRequired++;
            }
        }
        if (lifecycleService != null) {
            lifecycleService.runOnce(now);
        }
        if (closePositionRecoveryService != null) {
            closePositionRecoveryService.runOnce(now);
        }
        return new RecoveryResult(expiredOrders, releasedReservations, attentionRequired, recoveredUnknownSubmits);
    }

    private void syncCurrentOkxOrders() {
        if (currentOrderSyncService == null) {
            return;
        }
        OkxCurrentOrderSyncService.SyncResult result = currentOrderSyncService.syncOnce();
        if (result != null && result.failed()) {
            log.warn("AutoTrade recovery current OKX order sync failed: {}", result.errorMessage());
            recordRecoveryFailed(null, null, "CURRENT_OKX_ORDER_SYNC_FAILED", result.errorMessage());
        }
    }

    private RecoveryDecision recoverUnknownSubmit(PendingOrder order, Instant now) {
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
                order.markRejected(result.message());
                return new RecoveryDecision(0, releaseAnyBudget(order, "OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT"), 0);
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
                                 int recoveredUnknownSubmits) {
    }
}

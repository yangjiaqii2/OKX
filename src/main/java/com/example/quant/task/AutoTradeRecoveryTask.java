package com.example.quant.task;

import com.example.quant.account.ClosePositionRecoveryService;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.budget.BudgetReservationStatus;
import com.example.quant.agent.lifecycle.AutoTradeLifecycleService;
import com.example.quant.config.AgentProperties;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.okxtrade.OkxCurrentOrderSyncService;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.order.OrderExecutionResult;
import java.time.Instant;
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

    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties) {
        this(pendingOrderService, budgetService, agentProperties, null, null, null, null);
    }

    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties, OkxTradeAdapter okxTradeAdapter,
                                 AutoTradeLifecycleService lifecycleService,
                                 ClosePositionRecoveryService closePositionRecoveryService) {
        this(pendingOrderService, budgetService, agentProperties, okxTradeAdapter, lifecycleService,
                closePositionRecoveryService, null);
    }

    @Autowired
    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties, OkxTradeAdapter okxTradeAdapter,
                                 AutoTradeLifecycleService lifecycleService,
                                 ClosePositionRecoveryService closePositionRecoveryService,
                                 OkxCurrentOrderSyncService currentOrderSyncService) {
        this.pendingOrderService = pendingOrderService;
        this.budgetService = budgetService;
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
        this.okxTradeAdapter = okxTradeAdapter;
        this.lifecycleService = lifecycleService;
        this.closePositionRecoveryService = closePositionRecoveryService;
        this.currentOrderSyncService = currentOrderSyncService;
    }

    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties, OkxTradeAdapter okxTradeAdapter) {
        this(pendingOrderService, budgetService, agentProperties, okxTradeAdapter, null, null, null);
    }

    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties, OkxTradeAdapter okxTradeAdapter,
                                 AutoTradeLifecycleService lifecycleService) {
        this(pendingOrderService, budgetService, agentProperties, okxTradeAdapter, lifecycleService, null, null);
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
        int expiredOrders = 0;
        int releasedReservations = 0;
        int attentionRequired = 0;
        int recoveredUnknownSubmits = 0;
        Instant now = Instant.now();
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
                RecoveryDecision decision = recoverUnknownSubmit(order);
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
        }
    }

    private RecoveryDecision recoverUnknownSubmit(PendingOrder order) {
        if (okxTradeAdapter == null || order.clientOrderId() == null || order.clientOrderId().isBlank()) {
            return new RecoveryDecision(0, 0, 1);
        }
        try {
            OrderExecutionResult result = okxTradeAdapter.recoverUnknownSubmitStatus(order);
            if (result.executed()) {
                order.markSubmitted(Instant.now(), result.externalOrderId());
                if (order.budgetReservationId() != null) {
                    budgetService.markUsed(order.budgetReservationId());
                }
                log.warn("AutoTrade recovery restored unknown OKX submit: symbol={}, pendingOrderId={}, clOrdId={}, okxOrdId={}",
                        order.instId(), order.id(), order.clientOrderId(), result.externalOrderId());
                return new RecoveryDecision(1, 0, 0);
            }
            if (result.message() != null && result.message().contains("OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT")) {
                order.markRejected(result.message());
                int released = releaseAnyBudget(order, "OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT");
                return new RecoveryDecision(0, released, 0);
            }
            return new RecoveryDecision(0, 0, 1);
        } catch (RuntimeException ex) {
            log.warn("AutoTrade recovery could not resolve unknown submit status: symbol={}, pendingOrderId={}, clOrdId={}, message={}",
                    order.instId(), order.id(), order.clientOrderId(), ex.getMessage());
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
        return 1;
    }

    private static boolean isReservablePendingStatus(OrderStatus status) {
        return status == OrderStatus.PENDING_CONFIRM || status == OrderStatus.BUDGET_RESERVED;
    }

    private record RecoveryDecision(int recovered, int released, int attentionRequired) {
    }

    public record RecoveryResult(int expiredOrders, int releasedReservations, int attentionRequired,
                                 int recoveredUnknownSubmits) {
    }
}

package com.example.quant.task;

import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.budget.BudgetReservationStatus;
import com.example.quant.config.AgentProperties;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

    public AutoTradeRecoveryTask(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                 AgentProperties agentProperties) {
        this.pendingOrderService = pendingOrderService;
        this.budgetService = budgetService;
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
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
            return new RecoveryResult(0, 0, 0);
        }
        int expiredOrders = 0;
        int releasedReservations = 0;
        int attentionRequired = 0;
        Instant now = Instant.now();
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
            if (order.status() == OrderStatus.PROTECTION_FAILED
                    || order.status() == OrderStatus.EMERGENCY_ATTENTION_REQUIRED
                    || order.status() == OrderStatus.UNKNOWN_SUBMIT_STATUS) {
                attentionRequired++;
            }
        }
        return new RecoveryResult(expiredOrders, releasedReservations, attentionRequired);
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

    public record RecoveryResult(int expiredOrders, int releasedReservations, int attentionRequired) {
    }
}

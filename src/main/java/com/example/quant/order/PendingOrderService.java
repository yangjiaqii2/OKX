package com.example.quant.order;

import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.plan.TradePlanRecordService;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingProperties;
import com.example.quant.market.MarketType;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PendingOrderService {
    private final Map<UUID, PendingOrder> orders = new ConcurrentHashMap<>();
    private final Map<UUID, String> orderOwners = new ConcurrentHashMap<>();
    private final int expireSeconds;
    private final Clock clock;
    private final TradePlanRecordService tradePlanRecordService;
    private final PendingOrderRepository pendingOrderRepository;

    @Autowired
    public PendingOrderService(TradingProperties tradingProperties, TradePlanRecordService tradePlanRecordService,
                               PendingOrderRepository pendingOrderRepository) {
        this(tradingProperties.orderExpireSeconds(), Clock.systemUTC(), tradePlanRecordService, pendingOrderRepository);
    }

    public PendingOrderService(TradingProperties tradingProperties) {
        this(tradingProperties.orderExpireSeconds(), Clock.systemUTC(), null, null);
    }

    public PendingOrderService(int expireSeconds) {
        this(expireSeconds, Clock.systemUTC(), null, null);
    }

    public PendingOrderService(int expireSeconds, PendingOrderRepository pendingOrderRepository) {
        this(expireSeconds, Clock.systemUTC(), null, pendingOrderRepository);
    }

    public PendingOrderService(int expireSeconds, Clock clock) {
        this(expireSeconds, clock, null, null);
    }

    public PendingOrderService(int expireSeconds, Clock clock, TradePlanRecordService tradePlanRecordService) {
        this(expireSeconds, clock, tradePlanRecordService, null);
    }

    public PendingOrderService(int expireSeconds, Clock clock, TradePlanRecordService tradePlanRecordService,
                               PendingOrderRepository pendingOrderRepository) {
        this.expireSeconds = expireSeconds;
        this.clock = clock;
        this.tradePlanRecordService = tradePlanRecordService;
        this.pendingOrderRepository = pendingOrderRepository;
        restorePendingOrders();
    }

    public PendingOrder createPendingOrder(MarketType marketType, TradePlan plan) {
        requireExecutablePlan(marketType, plan);
        Instant now = clock.instant();
        String side = plan.action() == TradePlanType.OPEN_SHORT ? "sell" : "buy";
        String posSide = plan.action() == TradePlanType.OPEN_SHORT ? "short" : "long";
        PendingOrder order = buildPendingOrder(UUID.randomUUID(), marketType, plan, now);
        orderOwners.put(order.id(), currentUsername());
        attach(order);
        orders.put(order.id(), order);
        persist(order);
        if (tradePlanRecordService != null) {
            tradePlanRecordService.markPendingOrderCreated(plan.id());
        }
        return order;
    }

    public PendingOrder createAutoPendingOrder(MarketType marketType, TradePlan plan, UUID pendingOrderId,
                                               BigDecimal orderMarginUsdt, UUID budgetReservationId,
                                               BudgetAllocation allocation, String clientOrderId) {
        requireExecutablePlan(marketType, plan);
        Instant now = clock.instant();
        PendingOrder order = buildPendingOrder(pendingOrderId, marketType, plan, now);
        order.applyBudgetReservation(orderMarginUsdt, budgetReservationId, budgetAllocationJson(allocation), clientOrderId);
        orderOwners.put(order.id(), currentUsername());
        attach(order);
        orders.put(order.id(), order);
        persist(order);
        if (tradePlanRecordService != null) {
            tradePlanRecordService.markPendingOrderCreated(plan.id());
        }
        return order;
    }

    private static void requireExecutablePlan(MarketType marketType, TradePlan plan) {
        if (marketType == MarketType.A_SHARE) {
            throw new IllegalArgumentException("A股只做分析，不能生成PendingOrder");
        }
        if (marketType != MarketType.OKX_SWAP) {
            throw new IllegalArgumentException("仅OKX合约支持待确认订单");
        }
        if (plan == null || plan.action() == TradePlanType.WATCH || "NO_ENTRY".equalsIgnoreCase(plan.orderType())) {
            throw new IllegalArgumentException("观察计划不能进入订单生命周期");
        }
    }

    public PendingOrder createAutoPendingOrder(MarketType marketType, TradePlan plan, BigDecimal orderMarginUsdt,
                                               UUID budgetReservationId, BudgetAllocation allocation,
                                               String clientOrderId) {
        return createAutoPendingOrder(marketType, plan, UUID.randomUUID(), orderMarginUsdt,
                budgetReservationId, allocation, clientOrderId);
    }

    private PendingOrder buildPendingOrder(UUID id, MarketType marketType, TradePlan plan, Instant now) {
        String side = plan.action() == TradePlanType.OPEN_SHORT ? "sell" : "buy";
        String posSide = plan.action() == TradePlanType.OPEN_SHORT ? "short" : "long";
        PendingOrder order = new PendingOrder(
                id,
                marketType,
                plan.instId(),
                plan.action(),
                side,
                posSide,
                plan.orderType(),
                plan.entryPrice(),
                plan.suggestedSize(),
                plan.suggestedLeverage(),
                plan.tdMode(),
                plan.stopLossPrice(),
                plan.takeProfitPrice(),
                plan.maxLossAmount(),
                plan.riskRewardRatio(),
                plan.signalScore(),
                plan.fundingRate(),
                plan.volatility(),
                plan.volume24h(),
                plan.id(),
                UUID.randomUUID().toString(),
                now,
                now.plusSeconds(expireSeconds)
        );
        order.assignClientOrderId("qa" + id.toString().replace("-", "").substring(0, 30));
        return order;
    }

    public PendingOrder get(UUID id) {
        PendingOrder order = orders.get(id);
        if (order == null || !currentUsername().equals(orderOwners.get(id))) {
            throw new IllegalArgumentException("订单不存在");
        }
        return order;
    }

    public List<PendingOrder> pendingOrders() {
        return new ArrayList<>(orders.values()).stream()
                .filter(order -> currentUsername().equals(orderOwners.get(order.id())))
                .filter(order -> order.status() == OrderStatus.PENDING_CONFIRM)
                .toList();
    }

    public List<PendingOrder> allOrders() {
        String owner = currentUsername();
        return new ArrayList<>(orders.values()).stream()
                .filter(order -> owner.equals(orderOwners.get(order.id())))
                .toList();
    }

    public void cancel(UUID id) {
        get(id).markCancelled("用户取消");
    }

    private void restorePendingOrders() {
        if (pendingOrderRepository == null) {
            return;
        }
        for (PendingOrderEntity entity : pendingOrderRepository.findAll()) {
            PendingOrder order = entity.toDomain();
            boolean rejectedWithAcknowledgement = order.status() == OrderStatus.REJECTED
                    && order.externalOrderId() != null && !order.externalOrderId().isBlank();
            if (isTerminal(order.status()) && !recoverableTimedOutEntry(order) && !rejectedWithAcknowledgement) {
                continue;
            }
            orderOwners.put(order.id(), normalizeUsername(entity.getUserName()));
            attach(order);
            orders.put(order.id(), order);
            if (rejectedWithAcknowledgement) {
                // The old rejection path released budget and daily-risk claims.
                // Retain ACK and block new entries until that accounting is reconciled.
                order.markUnknownSubmitStatus("LEGACY_ACK_ACCOUNTING_RECONCILIATION_REQUIRED");
            } else if (order.status() == OrderStatus.SUBMITTING) {
                order.markUnknownSubmitStatus("APPLICATION_RESTARTED_DURING_ENTRY_SUBMIT");
            }
        }
    }

    private PendingOrder attach(PendingOrder order) {
        order.onChange(() -> persist(order));
        return order;
    }

    private void persist(PendingOrder order) {
        if (pendingOrderRepository != null) {
            pendingOrderRepository.save(PendingOrderEntity.from(order,
                    orderOwners.getOrDefault(order.id(), currentUsername())));
        }
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    private static String normalizeUsername(String username) {
        return username == null || username.isBlank() ? OkxCredentialStore.SYSTEM_USER : username.trim();
    }

    private static boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.CANCELLED
                || status == OrderStatus.EXPIRED
                || status == OrderStatus.REJECTED
                || status == OrderStatus.FAILED
                || status == OrderStatus.ENTRY_TIMEOUT_CANCELLED
                || status == OrderStatus.CLOSED;
    }

    private static boolean recoverableTimedOutEntry(PendingOrder order) {
        return order.status() == OrderStatus.ENTRY_TIMEOUT_CANCELLED && order.submittedAt() != null;
    }

    private static String budgetAllocationJson(BudgetAllocation allocation) {
        if (allocation == null) {
            return "{}";
        }
        return "{"
                + "\"totalBudgetUsdt\":" + number(allocation.totalBudgetUsdt()) + ","
                + "\"targetUsedBudgetUsdt\":" + number(allocation.targetUsedBudgetUsdt()) + ","
                + "\"minTargetUsedBudgetUsdt\":" + number(allocation.minTargetUsedBudgetUsdt()) + ","
                + "\"usedBudgetBefore\":" + number(allocation.usedBudgetBefore()) + ","
                + "\"inFlightReservedBefore\":" + number(allocation.inFlightReservedBefore()) + ","
                + "\"remainingBudgetBefore\":" + number(allocation.remainingBudgetBefore()) + ","
                + "\"slotIndex\":" + allocation.slotIndex() + ","
                + "\"slotWeight\":" + number(allocation.slotWeight()) + ","
                + "\"slotBudgetUsdt\":" + number(allocation.slotBudgetUsdt()) + ","
                + "\"scoreFactor\":" + number(allocation.scoreFactor()) + ","
                + "\"qualityAdjustedBudgetUsdt\":" + number(allocation.qualityAdjustedBudgetUsdt()) + ","
                + "\"riskBasedMaxMarginUsdt\":" + number(allocation.riskBasedMaxMarginUsdt()) + ","
                + "\"maxSinglePositionBudgetUsdt\":" + number(allocation.maxSinglePositionBudgetUsdt()) + ","
                + "\"redistributedExtraUsdt\":" + number(allocation.redistributedExtraUsdt()) + ","
                + "\"finalOrderMarginUsdt\":" + number(allocation.finalOrderMarginUsdt()) + ","
                + "\"budgetUtilizationAfter\":" + number(allocation.budgetUtilizationAfter()) + ","
                + "\"allocationMode\":\"" + allocation.allocationMode() + "\","
                + "\"status\":\"" + allocation.status() + "\","
                + "\"underUtilizedReasons\":\"" + String.join(",", allocation.underUtilizedReasons()) + "\","
                + "\"reason\":\"" + escape(allocation.reason()) + "\""
                + "}";
    }

    private static String number(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

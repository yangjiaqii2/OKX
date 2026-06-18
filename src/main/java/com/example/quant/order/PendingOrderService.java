package com.example.quant.order;

import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.plan.TradePlanRecordService;
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
        if (marketType == MarketType.A_SHARE) {
            throw new IllegalArgumentException("A股只做分析，不能生成PendingOrder");
        }
        if (marketType != MarketType.OKX_SWAP) {
            throw new IllegalArgumentException("仅OKX合约支持待确认订单");
        }
        Instant now = clock.instant();
        String side = plan.action() == TradePlanType.OPEN_SHORT ? "sell" : "buy";
        String posSide = plan.action() == TradePlanType.OPEN_SHORT ? "short" : "long";
        PendingOrder order = buildPendingOrder(UUID.randomUUID(), marketType, plan, now);
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
        if (marketType == MarketType.A_SHARE) {
            throw new IllegalArgumentException("A股只做分析，不能生成PendingOrder");
        }
        if (marketType != MarketType.OKX_SWAP) {
            throw new IllegalArgumentException("仅OKX合约支持待确认订单");
        }
        Instant now = clock.instant();
        PendingOrder order = buildPendingOrder(pendingOrderId, marketType, plan, now);
        order.applyBudgetReservation(orderMarginUsdt, budgetReservationId, budgetAllocationJson(allocation), clientOrderId);
        attach(order);
        orders.put(order.id(), order);
        persist(order);
        if (tradePlanRecordService != null) {
            tradePlanRecordService.markPendingOrderCreated(plan.id());
        }
        return order;
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
        return new PendingOrder(
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
    }

    public PendingOrder get(UUID id) {
        PendingOrder order = orders.get(id);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        return order;
    }

    public List<PendingOrder> pendingOrders() {
        return new ArrayList<>(orders.values()).stream()
                .filter(order -> order.status() == OrderStatus.PENDING_CONFIRM)
                .toList();
    }

    public List<PendingOrder> allOrders() {
        return new ArrayList<>(orders.values());
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
            if (isTerminal(order.status())) {
                continue;
            }
            attach(order);
            orders.put(order.id(), order);
        }
    }

    private PendingOrder attach(PendingOrder order) {
        order.onChange(() -> persist(order));
        return order;
    }

    private void persist(PendingOrder order) {
        if (pendingOrderRepository != null) {
            pendingOrderRepository.save(PendingOrderEntity.from(order));
        }
    }

    private static boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.CANCELLED
                || status == OrderStatus.EXPIRED
                || status == OrderStatus.REJECTED
                || status == OrderStatus.FAILED
                || status == OrderStatus.ENTRY_TIMEOUT_CANCELLED
                || status == OrderStatus.CLOSED;
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

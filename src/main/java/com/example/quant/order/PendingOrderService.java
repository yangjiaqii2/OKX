package com.example.quant.order;

import com.example.quant.market.MarketType;
import com.example.quant.config.TradingProperties;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
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

    @Autowired
    public PendingOrderService(TradingProperties tradingProperties) {
        this(tradingProperties.orderExpireSeconds(), Clock.systemUTC());
    }

    public PendingOrderService(int expireSeconds) {
        this(expireSeconds, Clock.systemUTC());
    }

    public PendingOrderService(int expireSeconds, Clock clock) {
        this.expireSeconds = expireSeconds;
        this.clock = clock;
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
        PendingOrder order = new PendingOrder(
                UUID.randomUUID(),
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
        orders.put(order.id(), order);
        return order;
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

    public void cancel(UUID id) {
        get(id).markCancelled("用户取消");
    }
}

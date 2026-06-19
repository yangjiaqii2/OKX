package com.example.quant.agent.lifecycle;

import com.example.quant.agent.event.TradeEventView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record AutoTradeLifecycleSnapshot(
        String instId,
        String posSide,
        Instant entryTime,
        Duration holdDuration,
        BigDecimal entryPrice,
        BigDecimal avgPx,
        BigDecimal unrealizedPnl,
        BigDecimal unrealizedPnlPct,
        BigDecimal budgetUsed,
        String entryOrderStatus,
        String protectionOrderStatus,
        String lifecycleStatus,
        String nextAction,
        EntryOrderFact entryOrder,
        List<ProtectionOrderFact> protectionOrders,
        PositionLifecycleFact positionLifecycle,
        ClosePositionFact closePosition,
        BudgetFact budget,
        List<TradeEventView> recentEvents,
        boolean manualAttentionRequired
) {
}

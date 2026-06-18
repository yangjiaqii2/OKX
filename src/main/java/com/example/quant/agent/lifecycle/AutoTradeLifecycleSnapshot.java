package com.example.quant.agent.lifecycle;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

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
        String nextAction
) {
}

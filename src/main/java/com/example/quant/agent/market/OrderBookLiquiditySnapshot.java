package com.example.quant.agent.market;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderBookLiquiditySnapshot(
        String instId,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal midPrice,
        BigDecimal spreadBps,
        BigDecimal bidDepthUsdt,
        BigDecimal askDepthUsdt,
        BigDecimal estimatedSlippageBps,
        boolean tradable,
        String denyReason,
        Instant createdAt
) {
    public static OrderBookLiquiditySnapshot unavailable(String instId, String reason) {
        return new OrderBookLiquiditySnapshot(
                instId,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.valueOf(9999),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.valueOf(9999),
                false,
                reason,
                Instant.now()
        );
    }

    public BigDecimal availableDepthUsdt() {
        return bidDepthUsdt.min(askDepthUsdt);
    }
}

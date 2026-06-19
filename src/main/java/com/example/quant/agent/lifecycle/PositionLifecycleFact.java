package com.example.quant.agent.lifecycle;

import java.math.BigDecimal;

public record PositionLifecycleFact(
        boolean positionSyncAvailable,
        boolean open,
        BigDecimal size,
        BigDecimal avgPx,
        BigDecimal unrealizedPnl,
        BigDecimal unrealizedPnlPct,
        String source
) {
}

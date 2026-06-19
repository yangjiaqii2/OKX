package com.example.quant.agent.lifecycle;

import java.math.BigDecimal;
import java.time.Instant;

public record ProtectionOrderFact(
        Long tradeOrderId,
        String orderRole,
        String status,
        String okxState,
        String okxOrdId,
        String clOrdId,
        BigDecimal size,
        BigDecimal price,
        Instant createdAt,
        String errorMessage
) {
}

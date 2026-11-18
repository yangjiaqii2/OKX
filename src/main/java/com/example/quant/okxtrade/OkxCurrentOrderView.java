package com.example.quant.okxtrade;

import java.math.BigDecimal;
import java.time.Instant;

public record OkxCurrentOrderView(
        String instId,
        String ordId,
        String algoId,
        String clOrdId,
        String algoClOrdId,
        String role,
        String side,
        String posSide,
        String ordType,
        boolean reduceOnly,
        BigDecimal size,
        BigDecimal filledSize,
        BigDecimal price,
        BigDecimal averageFillPrice,
        BigDecimal triggerPrice,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}

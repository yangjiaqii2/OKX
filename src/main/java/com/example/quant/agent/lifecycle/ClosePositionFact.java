package com.example.quant.agent.lifecycle;

import java.math.BigDecimal;
import java.time.Instant;

public record ClosePositionFact(
        Long closeRecordId,
        String status,
        String source,
        String closeOrderId,
        String closeClOrdId,
        BigDecimal realizedPnl,
        BigDecimal fee,
        BigDecimal fundingFee,
        Instant updatedAt,
        String errorMessage
) {
}

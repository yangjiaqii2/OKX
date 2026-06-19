package com.example.quant.agent.lifecycle;

import java.math.BigDecimal;
import java.time.Instant;

public record EntryOrderFact(
        String status,
        String okxOrdId,
        String clOrdId,
        BigDecimal orderSize,
        BigDecimal filledSize,
        BigDecimal avgFillPx,
        Instant submittedAt
) {
}

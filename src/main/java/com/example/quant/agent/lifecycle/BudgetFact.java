package com.example.quant.agent.lifecycle;

import java.math.BigDecimal;
import java.time.Instant;

public record BudgetFact(
        String reservationId,
        String status,
        BigDecimal amount,
        String reason,
        Instant updatedAt
) {
}

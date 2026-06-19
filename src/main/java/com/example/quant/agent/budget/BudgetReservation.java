package com.example.quant.agent.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BudgetReservation(
        UUID reservationId,
        UUID planId,
        UUID pendingOrderId,
        String userName,
        String symbol,
        BigDecimal amount,
        BudgetReservationStatus status,
        Instant createdAt,
        Instant updatedAt,
        String reason
) {
    public boolean reserved() {
        return status == BudgetReservationStatus.RESERVED;
    }
}

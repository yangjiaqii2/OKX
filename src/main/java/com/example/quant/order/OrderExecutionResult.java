package com.example.quant.order;

public record OrderExecutionResult(
        boolean executed,
        boolean liveOrder,
        String externalOrderId,
        String message
) {
}

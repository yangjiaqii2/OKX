package com.example.quant.order;

public record OrderExecutionResult(
        boolean executed,
        boolean liveOrder,
        String externalOrderId,
        String message,
        boolean submitted,
        boolean filled,
        boolean partiallyFilled,
        boolean unknown
) {
    public OrderExecutionResult(boolean executed, boolean liveOrder, String externalOrderId, String message) {
        this(executed, liveOrder, externalOrderId, message, executed, executed, false, false);
    }
}

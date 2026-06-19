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
    public static OrderExecutionResult submitted(String externalOrderId, String message) {
        return new OrderExecutionResult(true, true, externalOrderId, message,
                true, false, false, false);
    }

    public static OrderExecutionResult filled(String externalOrderId, String message) {
        return new OrderExecutionResult(true, true, externalOrderId, message,
                true, true, false, false);
    }

    public static OrderExecutionResult partiallyFilled(String externalOrderId, String message) {
        return new OrderExecutionResult(true, true, externalOrderId, message,
                true, false, true, false);
    }

    public static OrderExecutionResult rejected(String message) {
        return new OrderExecutionResult(false, false, null, message,
                false, false, false, false);
    }

    public static OrderExecutionResult unknown(String message) {
        return new OrderExecutionResult(false, true, null, message,
                false, false, false, true);
    }

    @Deprecated(since = "2026-06-19")
    public OrderExecutionResult(boolean executed, boolean liveOrder, String externalOrderId, String message) {
        this(executed, liveOrder, externalOrderId, message, executed, executed, false, false);
    }
}

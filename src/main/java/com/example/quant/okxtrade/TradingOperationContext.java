package com.example.quant.okxtrade;

public record TradingOperationContext(
        TradingOperationType operationType,
        String owner,
        String pendingOrderId,
        String tradePlanId,
        String positionDirection
) {
    public TradingOperationContext {
        operationType = operationType == null ? TradingOperationType.OPEN_POSITION : operationType;
    }

    public TradingOperationContext withDirection(String direction) {
        return new TradingOperationContext(operationType, owner, pendingOrderId, tradePlanId, direction);
    }

    public static TradingOperationContext of(TradingOperationType operationType, String owner,
                                             String pendingOrderId, String tradePlanId) {
        return new TradingOperationContext(operationType, owner, pendingOrderId, tradePlanId, null);
    }
}

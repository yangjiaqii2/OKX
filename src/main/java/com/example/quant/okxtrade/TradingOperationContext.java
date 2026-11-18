package com.example.quant.okxtrade;

public record TradingOperationContext(
        TradingOperationType operationType,
        String owner,
        String pendingOrderId,
        String tradePlanId
) {
    public TradingOperationContext {
        operationType = operationType == null ? TradingOperationType.OPEN_POSITION : operationType;
    }

    public static TradingOperationContext of(TradingOperationType operationType, String owner,
                                             String pendingOrderId, String tradePlanId) {
        return new TradingOperationContext(operationType, owner, pendingOrderId, tradePlanId);
    }
}

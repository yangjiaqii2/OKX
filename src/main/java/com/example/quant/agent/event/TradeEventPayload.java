package com.example.quant.agent.event;

public record TradeEventPayload(
        String userName,
        String instId,
        String pendingOrderId,
        Long autoTradeRecordId,
        Long tradeOrderId,
        TradeEventType eventType,
        String oldStatus,
        String newStatus,
        String reasonCode,
        String reasonMessage,
        String okxOrdId,
        String clOrdId,
        String algoId
) {
}

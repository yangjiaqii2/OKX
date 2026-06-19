package com.example.quant.agent.event;

import java.time.Instant;

public record TradeEventView(
        Long id,
        String userName,
        String instId,
        String pendingOrderId,
        Long autoTradeRecordId,
        Long tradeOrderId,
        String eventType,
        String oldStatus,
        String newStatus,
        String reasonCode,
        String reasonMessage,
        String okxOrdId,
        String clOrdId,
        String algoId,
        Instant createdAt
) {
    public static TradeEventView from(TradeEventEntity entity) {
        return new TradeEventView(
                entity.getId(),
                entity.getUserName(),
                entity.getInstId(),
                entity.getPendingOrderId(),
                entity.getAutoTradeRecordId(),
                entity.getTradeOrderId(),
                entity.getEventType(),
                entity.getOldStatus(),
                entity.getNewStatus(),
                entity.getReasonCode(),
                entity.getReasonMessage(),
                entity.getOkxOrdId(),
                entity.getClOrdId(),
                entity.getAlgoId(),
                entity.getCreatedAt()
        );
    }
}

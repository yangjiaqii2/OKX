package com.example.quant.agent.review;

import java.time.Instant;

public record TradeReviewView(
        Long id,
        String userName,
        String instId,
        String pendingOrderId,
        Long autoTradeRecordId,
        Long closePositionRecordId,
        String reviewReason,
        String strategyTag,
        String improvementHint,
        Instant createdAt,
        Instant updatedAt
) {
    public static TradeReviewView from(TradeReviewEntity entity) {
        return new TradeReviewView(
                entity.getId(),
                entity.getUserName(),
                entity.getInstId(),
                entity.getPendingOrderId(),
                entity.getAutoTradeRecordId(),
                entity.getClosePositionRecordId(),
                entity.getReviewReason(),
                entity.getStrategyTag(),
                entity.getImprovementHint(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

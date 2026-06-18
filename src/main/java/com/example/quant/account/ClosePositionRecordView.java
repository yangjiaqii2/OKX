package com.example.quant.account;

import java.math.BigDecimal;
import java.time.Instant;

public record ClosePositionRecordView(
        Long id,
        String userName,
        String instId,
        String posSide,
        String marginMode,
        String closeOrderId,
        String closeClOrdId,
        BigDecimal size,
        BigDecimal avgPx,
        BigDecimal realizedPnl,
        BigDecimal fee,
        BigDecimal fundingFee,
        String status,
        String source,
        Long autoTradeRecordId,
        String pendingOrderId,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static ClosePositionRecordView from(ClosePositionRecordEntity entity) {
        return new ClosePositionRecordView(
                entity.getId(),
                entity.getUserName(),
                entity.getInstId(),
                entity.getPosSide(),
                entity.getMarginMode(),
                entity.getCloseOrderId(),
                entity.getCloseClOrdId(),
                entity.getSize(),
                entity.getAvgPx(),
                entity.getRealizedPnl(),
                entity.getFee(),
                entity.getFundingFee(),
                entity.getStatus(),
                entity.getSource(),
                entity.getAutoTradeRecordId(),
                entity.getPendingOrderId(),
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

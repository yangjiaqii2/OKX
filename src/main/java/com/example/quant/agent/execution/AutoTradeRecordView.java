package com.example.quant.agent.execution;

import java.math.BigDecimal;
import java.time.Instant;

public record AutoTradeRecordView(
        Long id,
        String status,
        String userName,
        String triggerType,
        String instId,
        String tradePlanId,
        String pendingOrderId,
        String okxOrderId,
        String action,
        String posSide,
        Integer leverage,
        BigDecimal marginAmount,
        BigDecimal entryPrice,
        int candidateCount,
        Integer candidateScore,
        String trendDirection,
        BigDecimal lastPrice,
        BigDecimal riskRewardRatio,
        BigDecimal spreadBps,
        BigDecimal bidDepthUsdt,
        BigDecimal askDepthUsdt,
        String marketRiskLevel,
        String message,
        Instant createdAt
) {
    public static AutoTradeRecordView from(AutoTradeRecordEntity entity) {
        return new AutoTradeRecordView(
                entity.getId(),
                entity.getStatus(),
                entity.getUserName(),
                entity.getTriggerType(),
                entity.getInstId(),
                entity.getTradePlanId(),
                entity.getPendingOrderId(),
                entity.getOkxOrderId(),
                entity.getAction(),
                entity.getPosSide(),
                entity.getLeverage(),
                entity.getMarginAmount(),
                entity.getEntryPrice(),
                entity.getCandidateCount(),
                entity.getCandidateScore(),
                entity.getTrendDirection(),
                entity.getLastPrice(),
                entity.getRiskRewardRatio(),
                entity.getSpreadBps(),
                entity.getBidDepthUsdt(),
                entity.getAskDepthUsdt(),
                entity.getMarketRiskLevel(),
                entity.getMessage(),
                entity.getCreatedAt()
        );
    }
}

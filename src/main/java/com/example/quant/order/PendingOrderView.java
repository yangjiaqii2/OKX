package com.example.quant.order;

import com.example.quant.market.MarketType;
import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PendingOrderView(
        UUID id,
        MarketType marketType,
        String instId,
        TradePlanType action,
        String side,
        String posSide,
        String orderType,
        BigDecimal price,
        BigDecimal size,
        BigDecimal marginAmount,
        int leverage,
        String tdMode,
        BigDecimal stopLossPrice,
        BigDecimal takeProfitPrice,
        BigDecimal maxLossAmount,
        BigDecimal riskRewardRatio,
        int signalScore,
        BigDecimal fundingRate,
        BigDecimal volatility,
        BigDecimal volume24h,
        UUID tradePlanId,
        OrderStatus status,
        boolean userConfirmed,
        Instant confirmedAt,
        Instant executedAt,
        String rejectReason,
        Instant createdAt,
        Instant expireAt
) {
    public static PendingOrderView from(PendingOrder order) {
        return new PendingOrderView(
                order.id(),
                order.marketType(),
                order.instId(),
                order.action(),
                order.side(),
                order.posSide(),
                order.orderType(),
                order.price(),
                order.size(),
                order.marginAmount(),
                order.leverage(),
                order.tdMode(),
                order.stopLossPrice(),
                order.takeProfitPrice(),
                order.maxLossAmount(),
                order.riskRewardRatio(),
                order.signalScore(),
                order.fundingRate(),
                order.volatility(),
                order.volume24h(),
                order.tradePlanId(),
                order.status(),
                order.userConfirmed(),
                order.confirmedAt(),
                order.executedAt(),
                order.rejectReason(),
                order.createdAt(),
                order.expireAt()
        );
    }
}

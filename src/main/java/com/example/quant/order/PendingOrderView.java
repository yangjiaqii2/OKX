package com.example.quant.order;

import com.example.quant.market.MarketType;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
        UUID budgetReservationId,
        String budgetAllocationJson,
        String clientOrderId,
        int leverage,
        String tdMode,
        BigDecimal stopLossPrice,
        BigDecimal plannedTakeProfitPrice,
        BigDecimal takeProfitPrice,
        List<CanonicalExecutablePlan.ExitLeg> executableTakeProfitLegs,
        BigDecimal executableContractQuantity,
        BigDecimal executableStopLossAmount,
        BigDecimal maxLossAmount,
        BigDecimal riskRewardRatio,
        BigDecimal estimatedEntryPrice,
        BigDecimal estimatedNetStopLossAmount,
        BigDecimal estimatedNetRiskRewardRatio,
        int signalScore,
        BigDecimal fundingRate,
        BigDecimal volatility,
        BigDecimal volume24h,
        UUID tradePlanId,
        OrderStatus status,
        boolean userConfirmed,
        Instant confirmedAt,
        Instant submittedAt,
        Instant executedAt,
        String externalOrderId,
        String rejectReason,
        Instant createdAt,
        Instant expireAt
) {
    public static PendingOrderView from(PendingOrder order) {
        var execution = order.canonicalExecutablePlan();
        var risk = execution == null ? null : execution.risk();
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
                order.budgetReservationId(),
                order.budgetAllocationJson(),
                order.clientOrderId(),
                order.leverage(),
                order.tdMode(),
                order.stopLossPrice(),
                order.takeProfitPrice(),
                order.takeProfitPrice(),
                execution == null ? List.of() : execution.takeProfitLegs(),
                order.executableContractQuantity(),
                order.executableStopLossAmount(),
                order.maxLossAmount(),
                order.riskRewardRatio(),
                risk == null ? null : risk.estimatedEntryPrice(),
                risk == null ? null : risk.estimatedNetStopLossAmount(),
                risk == null ? null : risk.estimatedNetRiskRewardRatio(),
                order.signalScore(),
                order.fundingRate(),
                order.volatility(),
                order.volume24h(),
                order.tradePlanId(),
                order.status(),
                order.userConfirmed(),
                order.confirmedAt(),
                order.submittedAt(),
                order.executedAt(),
                order.externalOrderId(),
                order.rejectReason(),
                order.createdAt(),
                order.expireAt()
        );
    }
}

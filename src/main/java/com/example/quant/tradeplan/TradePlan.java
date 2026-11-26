package com.example.quant.tradeplan;

import com.example.quant.market.DirectionBias;
import com.example.quant.okxtrade.OkxInstrumentRules;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TradePlan(
        UUID id,
        String instId,
        TradePlanType action,
        String orderType,
        DirectionBias directionBias,
        BigDecimal confidence,
        BigDecimal entryPrice,
        BigDecimal stopLossPrice,
        BigDecimal takeProfitPrice,
        int suggestedLeverage,
        BigDecimal suggestedSize,
        BigDecimal suggestedMargin,
        BigDecimal maxLossAmount,
        BigDecimal riskRewardRatio,
        int signalScore,
        BigDecimal fundingRate,
        BigDecimal volatility,
        BigDecimal volume24h,
        String tdMode,
        List<String> reasonList,
        List<String> riskList,
        String invalidCondition,
        boolean needUserConfirm,
        Instant expireTime,
        CanonicalExecutablePlan executionPreview
) {
    public TradePlan(UUID id, String instId, TradePlanType action, String orderType, DirectionBias directionBias,
                     BigDecimal confidence, BigDecimal entryPrice, BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                     int suggestedLeverage, BigDecimal suggestedSize, BigDecimal suggestedMargin, BigDecimal maxLossAmount,
                     BigDecimal riskRewardRatio, int signalScore, BigDecimal fundingRate, BigDecimal volatility,
                     BigDecimal volume24h, String tdMode, List<String> reasonList, List<String> riskList,
                     String invalidCondition, boolean needUserConfirm, Instant expireTime) {
        this(id, instId, action, orderType, directionBias, confidence, entryPrice, stopLossPrice, takeProfitPrice,
                suggestedLeverage, suggestedSize, suggestedMargin, maxLossAmount, riskRewardRatio, signalScore,
                fundingRate, volatility, volume24h, tdMode, reasonList, riskList, invalidCondition, needUserConfirm,
                expireTime, null);
    }

    TradePlan withExecutionPreview(PlanExecutionRisk.Estimate estimate) {
        return new TradePlan(id, instId, action, orderType, directionBias, confidence, entryPrice, stopLossPrice,
                takeProfitPrice, suggestedLeverage, estimate.baseQuantity(), estimate.margin(),
                estimate.executable().risk().riskBudget(), riskRewardRatio, signalScore, fundingRate, volatility,
                volume24h, tdMode, reasonList, riskList, invalidCondition, needUserConfirm, expireTime,
                estimate.executable());
    }

    public String rejectionReason() {
        if (action != TradePlanType.WATCH && !"NO_ENTRY".equalsIgnoreCase(orderType)) {
            return null;
        }
        return riskList == null || riskList.isEmpty()
                ? "观察计划不能进入订单生命周期" : riskList.get(riskList.size() - 1);
    }

    public BigDecimal riskBudgetAmount() { return maxLossAmount; }
    public BigDecimal estimatedStopLossAmount() {
        if (suggestedSize == null || entryPrice == null || stopLossPrice == null) return BigDecimal.ZERO;
        return entryPrice.subtract(stopLossPrice).abs().multiply(suggestedSize);
    }
    public BigDecimal estimatedStopLossAmount(OkxInstrumentRules rules) {
        if (rules == null || entryPrice == null || stopLossPrice == null || suggestedSize == null) return BigDecimal.ZERO;
        BigDecimal contracts = rules.normalizeEntrySize(suggestedSize, entryPrice);
        BigDecimal finalBase = rules.contractValueInQuoteCurrencyForAudit()
                ? contracts.multiply(rules.contractValue()).divide(entryPrice, 16, java.math.RoundingMode.DOWN)
                : contracts.multiply(rules.contractValue());
        return entryPrice.subtract(stopLossPrice).abs().multiply(finalBase).setScale(8, java.math.RoundingMode.HALF_UP);
    }
}

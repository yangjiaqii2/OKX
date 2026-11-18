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
        Instant expireTime
) {
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

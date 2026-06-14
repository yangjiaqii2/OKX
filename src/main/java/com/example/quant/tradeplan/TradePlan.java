package com.example.quant.tradeplan;

import com.example.quant.market.DirectionBias;
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
        String tdMode,
        List<String> reasonList,
        List<String> riskList,
        String invalidCondition,
        boolean needUserConfirm,
        Instant expireTime
) {
}

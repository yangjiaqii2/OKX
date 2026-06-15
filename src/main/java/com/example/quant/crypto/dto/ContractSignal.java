package com.example.quant.crypto.dto;

import com.example.quant.market.DirectionBias;
import java.math.BigDecimal;
import java.util.List;

public record ContractSignal(
        int score,
        DirectionBias directionBias,
        BigDecimal entryPrice,
        BigDecimal stopLossPrice,
        BigDecimal takeProfitPrice,
        int suggestedLeverage,
        BigDecimal riskRewardRatio,
        BigDecimal changePercent5m,
        BigDecimal volumeSpikeRatio,
        BigDecimal fundingRate,
        BigDecimal openInterest,
        BigDecimal openInterestChange,
        BigDecimal volatility,
        List<String> reasonList,
        List<String> riskTagList
) {
}

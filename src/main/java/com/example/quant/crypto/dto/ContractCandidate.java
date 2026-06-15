package com.example.quant.crypto.dto;

import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ContractCandidate(
        MarketType marketType,
        String instId,
        String baseCurrency,
        String quoteCurrency,
        BigDecimal lastPrice,
        BigDecimal changePercent24h,
        BigDecimal changePercent5m,
        BigDecimal volume24h,
        BigDecimal volumeSpikeRatio,
        BigDecimal fundingRate,
        BigDecimal openInterest,
        BigDecimal openInterestChange,
        DirectionBias trendDirection,
        BigDecimal volatility,
        int score,
        BigDecimal entryPrice,
        BigDecimal stopLossPrice,
        BigDecimal takeProfitPrice,
        int suggestedLeverage,
        BigDecimal riskRewardRatio,
        List<String> candidateReasonList,
        List<String> riskTagList,
        Instant createdAt
) {
}

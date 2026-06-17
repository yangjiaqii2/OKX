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
        ContractFactorScore factorScore,
        List<String> reasonList,
        List<String> riskTagList,
        ContractSignalType signalType,
        String action,
        String entryType,
        BigDecimal todayChangePct,
        BigDecimal change24hPct,
        BigDecimal atrPct20m,
        BigDecimal stopLossPct,
        BigDecimal finalRankScore,
        ContractScoreBreakdown scoreBreakdown,
        ContractKlineAnalysis klineAnalysis,
        ContractNewsRiskAnalysis newsAnalysis
) {
    public ContractSignal(
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
            ContractFactorScore factorScore,
            List<String> reasonList,
            List<String> riskTagList
    ) {
        this(
                score,
                directionBias,
                entryPrice,
                stopLossPrice,
                takeProfitPrice,
                suggestedLeverage,
                riskRewardRatio,
                changePercent5m,
                volumeSpikeRatio,
                fundingRate,
                openInterest,
                openInterestChange,
                volatility,
                factorScore,
                reasonList,
                riskTagList,
                directionBias == DirectionBias.BEARISH ? ContractSignalType.TREND_SHORT : ContractSignalType.STRONG_LONG,
                score >= 80 ? "AUTO_TRADE_ALLOWED" : "WAIT_CONFIRM",
                "LIMIT",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                volatility == null ? BigDecimal.ZERO : volatility.multiply(BigDecimal.valueOf(100)),
                BigDecimal.ZERO,
                BigDecimal.valueOf(score),
                new ContractScoreBreakdown(score, score, score, score, score, score, 90),
                ContractKlineAnalysis.unavailable("LEGACY_ANALYSIS"),
                ContractNewsRiskAnalysis.low()
        );
    }
}

package com.example.quant.risk;

import java.math.BigDecimal;

public record ContractRiskRequest(
        boolean emergencyStop,
        boolean websocketConnected,
        long marketDelayMs,
        long maxMarketDelayMs,
        BigDecimal accountEquity,
        BigDecimal suggestedMargin,
        BigDecimal maxSingleMarginRate,
        int leverage,
        int maxLeverage,
        BigDecimal entryPrice,
        BigDecimal stopLossPrice,
        BigDecimal riskRewardRatio,
        BigDecimal minRiskRewardRatio,
        BigDecimal fundingRate,
        BigDecimal maxAbsFundingRate,
        BigDecimal volume24h,
        BigDecimal minVolume24h,
        RiskLevel newsRiskLevel,
        BigDecimal volatility,
        BigDecimal maxStopLossDistanceRate,
        int signalScore,
        int minSignalScore,
        BigDecimal maxSingleLossRate
) {
    public static ContractRiskRequest safeDefaults() {
        return new ContractRiskRequest(
                false,
                true,
                1000,
                5000,
                BigDecimal.valueOf(10000),
                BigDecimal.valueOf(300),
                BigDecimal.valueOf(0.05),
                2,
                3,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(1.5),
                BigDecimal.valueOf(0.0001),
                BigDecimal.valueOf(0.001),
                BigDecimal.valueOf(20000000),
                BigDecimal.valueOf(10000000),
                RiskLevel.LOW,
                BigDecimal.valueOf(0.02),
                BigDecimal.valueOf(0.08),
                75,
                58,
                BigDecimal.valueOf(0.01)
        );
    }

    public ContractRiskRequest withEmergencyStop(boolean value) {
        return copy(value, websocketConnected, marketDelayMs, maxMarketDelayMs, accountEquity, suggestedMargin, maxSingleMarginRate,
                leverage, maxLeverage, entryPrice, stopLossPrice, riskRewardRatio, minRiskRewardRatio, fundingRate,
                maxAbsFundingRate, volume24h, minVolume24h, newsRiskLevel, volatility, maxStopLossDistanceRate,
                signalScore, minSignalScore, maxSingleLossRate);
    }

    public ContractRiskRequest withStopLossPrice(BigDecimal value) {
        return copy(emergencyStop, websocketConnected, marketDelayMs, maxMarketDelayMs, accountEquity, suggestedMargin, maxSingleMarginRate,
                leverage, maxLeverage, entryPrice, value, riskRewardRatio, minRiskRewardRatio, fundingRate,
                maxAbsFundingRate, volume24h, minVolume24h, newsRiskLevel, volatility, maxStopLossDistanceRate,
                signalScore, minSignalScore, maxSingleLossRate);
    }

    public ContractRiskRequest withLeverage(int value) {
        return copy(emergencyStop, websocketConnected, marketDelayMs, maxMarketDelayMs, accountEquity, suggestedMargin, maxSingleMarginRate,
                value, maxLeverage, entryPrice, stopLossPrice, riskRewardRatio, minRiskRewardRatio, fundingRate,
                maxAbsFundingRate, volume24h, minVolume24h, newsRiskLevel, volatility, maxStopLossDistanceRate,
                signalScore, minSignalScore, maxSingleLossRate);
    }

    public ContractRiskRequest withMaxLeverage(int value) {
        return copy(emergencyStop, websocketConnected, marketDelayMs, maxMarketDelayMs, accountEquity, suggestedMargin, maxSingleMarginRate,
                leverage, value, entryPrice, stopLossPrice, riskRewardRatio, minRiskRewardRatio, fundingRate,
                maxAbsFundingRate, volume24h, minVolume24h, newsRiskLevel, volatility, maxStopLossDistanceRate,
                signalScore, minSignalScore, maxSingleLossRate);
    }

    public ContractRiskRequest withMarketDelayMs(long value) {
        return copy(emergencyStop, websocketConnected, value, maxMarketDelayMs, accountEquity, suggestedMargin, maxSingleMarginRate,
                leverage, maxLeverage, entryPrice, stopLossPrice, riskRewardRatio, minRiskRewardRatio, fundingRate,
                maxAbsFundingRate, volume24h, minVolume24h, newsRiskLevel, volatility, maxStopLossDistanceRate,
                signalScore, minSignalScore, maxSingleLossRate);
    }

    public ContractRiskRequest withMaxMarketDelayMs(long value) {
        return copy(emergencyStop, websocketConnected, marketDelayMs, value, accountEquity, suggestedMargin, maxSingleMarginRate,
                leverage, maxLeverage, entryPrice, stopLossPrice, riskRewardRatio, minRiskRewardRatio, fundingRate,
                maxAbsFundingRate, volume24h, minVolume24h, newsRiskLevel, volatility, maxStopLossDistanceRate,
                signalScore, minSignalScore, maxSingleLossRate);
    }

    public ContractRiskRequest withFundingRate(BigDecimal value) {
        return copy(emergencyStop, websocketConnected, marketDelayMs, maxMarketDelayMs, accountEquity, suggestedMargin, maxSingleMarginRate,
                leverage, maxLeverage, entryPrice, stopLossPrice, riskRewardRatio, minRiskRewardRatio, value,
                maxAbsFundingRate, volume24h, minVolume24h, newsRiskLevel, volatility, maxStopLossDistanceRate,
                signalScore, minSignalScore, maxSingleLossRate);
    }

    public ContractRiskRequest withVolatility(BigDecimal value) {
        return copy(emergencyStop, websocketConnected, marketDelayMs, maxMarketDelayMs, accountEquity, suggestedMargin, maxSingleMarginRate,
                leverage, maxLeverage, entryPrice, stopLossPrice, riskRewardRatio, minRiskRewardRatio, fundingRate,
                maxAbsFundingRate, volume24h, minVolume24h, newsRiskLevel, value, maxStopLossDistanceRate,
                signalScore, minSignalScore, maxSingleLossRate);
    }

    public ContractRiskRequest withSignalScore(int value) {
        return copy(emergencyStop, websocketConnected, marketDelayMs, maxMarketDelayMs, accountEquity, suggestedMargin, maxSingleMarginRate,
                leverage, maxLeverage, entryPrice, stopLossPrice, riskRewardRatio, minRiskRewardRatio, fundingRate,
                maxAbsFundingRate, volume24h, minVolume24h, newsRiskLevel, volatility, maxStopLossDistanceRate,
                value, minSignalScore, maxSingleLossRate);
    }

    public ContractRiskRequest withSuggestedMargin(BigDecimal value) {
        return copy(emergencyStop, websocketConnected, marketDelayMs, maxMarketDelayMs, accountEquity, value, maxSingleMarginRate,
                leverage, maxLeverage, entryPrice, stopLossPrice, riskRewardRatio, minRiskRewardRatio, fundingRate,
                maxAbsFundingRate, volume24h, minVolume24h, newsRiskLevel, volatility, maxStopLossDistanceRate,
                signalScore, minSignalScore, maxSingleLossRate);
    }

    private ContractRiskRequest copy(
            boolean emergencyStop,
            boolean websocketConnected,
            long marketDelayMs,
            long maxMarketDelayMs,
            BigDecimal accountEquity,
            BigDecimal suggestedMargin,
            BigDecimal maxSingleMarginRate,
            int leverage,
            int maxLeverage,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            BigDecimal riskRewardRatio,
            BigDecimal minRiskRewardRatio,
            BigDecimal fundingRate,
            BigDecimal maxAbsFundingRate,
            BigDecimal volume24h,
            BigDecimal minVolume24h,
            RiskLevel newsRiskLevel,
            BigDecimal volatility,
            BigDecimal maxStopLossDistanceRate,
            int signalScore,
            int minSignalScore,
            BigDecimal maxSingleLossRate
    ) {
        return new ContractRiskRequest(emergencyStop, websocketConnected, marketDelayMs, maxMarketDelayMs, accountEquity,
                suggestedMargin, maxSingleMarginRate, leverage, maxLeverage, entryPrice, stopLossPrice, riskRewardRatio,
                minRiskRewardRatio, fundingRate, maxAbsFundingRate, volume24h, minVolume24h, newsRiskLevel,
                volatility, maxStopLossDistanceRate, signalScore, minSignalScore, maxSingleLossRate);
    }
}

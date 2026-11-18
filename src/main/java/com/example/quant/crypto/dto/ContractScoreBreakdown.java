package com.example.quant.crypto.dto;

public record ContractScoreBreakdown(
        int trend,
        int volume,
        int liquidity,
        int volatility,
        int oiFunding,
        int market,
        int newsRisk
) {
    private static final int TREND_WEIGHT = 25;
    private static final int VOLUME_WEIGHT = 20;
    private static final int LIQUIDITY_WEIGHT = 25;
    private static final int VOLATILITY_WEIGHT = 10;
    private static final int OI_FUNDING_WEIGHT = 10;
    private static final int MARKET_WEIGHT = 5;
    private static final int NEWS_RISK_WEIGHT = 5;

    public static String weightSummary() {
        return "trend=%d, volume=%d, liquidity=%d, volatility=%d, oiFunding=%d, marketEnv=%d, newsRisk=%d"
                .formatted(TREND_WEIGHT, VOLUME_WEIGHT, LIQUIDITY_WEIGHT, VOLATILITY_WEIGHT,
                        OI_FUNDING_WEIGHT, MARKET_WEIGHT, NEWS_RISK_WEIGHT);
    }

    public ContractScoreBreakdown {
        trend = clamp(trend);
        volume = clamp(volume);
        liquidity = clamp(liquidity);
        volatility = clamp(volatility);
        oiFunding = clamp(oiFunding);
        market = clamp(market);
        newsRisk = clamp(newsRisk);
    }

    public int weightedTotal() {
        return clamp((int) Math.round(
                trend * (TREND_WEIGHT / 100.0)
                        + volume * (VOLUME_WEIGHT / 100.0)
                        + liquidity * (LIQUIDITY_WEIGHT / 100.0)
                        + volatility * (VOLATILITY_WEIGHT / 100.0)
                        + oiFunding * (OI_FUNDING_WEIGHT / 100.0)
                        + market * (MARKET_WEIGHT / 100.0)
                        + newsRisk * (NEWS_RISK_WEIGHT / 100.0)
        ));
    }

    public ContractFactorScore weightedFactorScore() {
        return new ContractFactorScore(
                weighted(trend, TREND_WEIGHT),
                weighted(volume, VOLUME_WEIGHT),
                weighted(volatility, VOLATILITY_WEIGHT),
                weighted(liquidity, LIQUIDITY_WEIGHT),
                weighted(oiFunding, OI_FUNDING_WEIGHT),
                weighted(market, MARKET_WEIGHT),
                weighted(newsRisk, NEWS_RISK_WEIGHT)
        );
    }

    private static int weighted(int value, int max) {
        return Math.max(0, Math.min(max, (int) Math.round(value * max / 100.0)));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}

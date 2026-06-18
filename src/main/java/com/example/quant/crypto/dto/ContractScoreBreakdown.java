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
                trend * 0.23
                        + volume * 0.22
                        + liquidity * 0.25
                        + volatility * 0.10
                        + oiFunding * 0.10
                        + market * 0.05
                        + newsRisk * 0.05
        ));
    }

    public ContractFactorScore weightedFactorScore() {
        return new ContractFactorScore(
                weighted(trend, 23),
                weighted(volume, 22),
                weighted(volatility, 10),
                weighted(liquidity, 25),
                weighted(oiFunding, 10),
                weighted(market, 5),
                weighted(newsRisk, 5)
        );
    }

    private static int weighted(int value, int max) {
        return Math.max(0, Math.min(max, (int) Math.round(value * max / 100.0)));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}

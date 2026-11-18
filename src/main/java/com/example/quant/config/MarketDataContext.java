package com.example.quant.config;

import java.util.Map;

public record MarketDataContext(
        MarketDataVenue signalVenue,
        MarketDataVenue executionVenue,
        MarketDataVenue candleVenue,
        MarketDataVenue tickerVenue,
        MarketDataVenue orderBookVenue,
        MarketDataVenue fundingVenue,
        MarketDataVenue openInterestVenue,
        MarketDataVenue instrumentVenue
) {
    public static MarketDataContext resolve(TradingMode executionMode, String configuredMarketMode) {
        MarketDataVenue execution = executionMode == TradingMode.DEMO ? MarketDataVenue.DEMO
                : executionMode == TradingMode.LIVE ? MarketDataVenue.LIVE : MarketDataVenue.UNKNOWN;
        String mode = configuredMarketMode == null ? "PUBLIC" : configuredMarketMode.trim().toUpperCase();
        if ("DEMO".equals(mode)) {
            throw new IllegalArgumentException(
                    "OKX public market data is PUBLIC_LIVE; DEMO provenance cannot be claimed");
        }
        MarketDataVenue signal = switch (mode) {
            case "PUBLIC", "PUBLIC_LIVE", "LIVE" -> MarketDataVenue.PUBLIC_LIVE;
            default -> MarketDataVenue.UNKNOWN;
        };
        return new MarketDataContext(signal, execution, signal, signal, signal, signal, signal, MarketDataVenue.PUBLIC_LIVE);
    }

    public Map<String, String> asMap() {
        return Map.of("signalVenue", signalVenue.name(), "executionVenue", executionVenue.name(),
                "candleVenue", candleVenue.name(), "tickerVenue", tickerVenue.name(),
                "orderBookVenue", orderBookVenue.name(), "fundingVenue", fundingVenue.name(),
                "openInterestVenue", openInterestVenue.name(), "instrumentVenue", instrumentVenue.name());
    }
}

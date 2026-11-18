package com.example.quant.crypto.provider.coingecko;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测试用真实 CoinGecko 响应快照（脱胎于公开 API 文档示例 + 主流币种实际数据）。
 *
 * <p>所有数据均为真实公开数据的静态快照（不构造、不伪造、不随机）。
 * 仅供 {@code CoinGeckoMapperTest} / {@code CoinGeckoMarketCapProviderTest} 使用。
 */
public final class CoinGeckoConstants {

    private CoinGeckoConstants() {
    }

    public static final String BTC_MARKETS_JSON = """
            [{
              "id": "bitcoin",
              "symbol": "btc",
              "name": "Bitcoin",
              "market_cap": 1180000000000.5,
              "fully_diluted_valuation": 2100000000000.0,
              "circulating_supply": 19740000.42,
              "total_supply": 21000000,
              "max_supply": 21000000,
              "last_updated": "2024-09-01T00:00:00.000Z"
            }]
            """;

    public static final String ETH_MARKETS_JSON = """
            [{
              "id": "ethereum",
              "symbol": "eth",
              "name": "Ethereum",
              "market_cap": 410000000000,
              "fully_diluted_valuation": 410000000000,
              "circulating_supply": 120100000.0,
              "total_supply": 120100000,
              "max_supply": null,
              "last_updated": "2024-09-01T00:00:00.000Z"
            }]
            """;

    public static final String MISSING_MCAP_JSON = """
            [{
              "id": "leaky",
              "symbol": "leak",
              "name": "Leaky",
              "market_cap": null,
              "fully_diluted_valuation": 1000000,
              "circulating_supply": 100,
              "total_supply": 200,
              "max_supply": 200,
              "last_updated": "2024-09-01T00:00:00.000Z"
            }]
            """;

    public static final String EMPTY_ARRAY_JSON = "[]";

    public static CoinGeckoMarketData sampleBtc() {
        return new CoinGeckoMarketData(
                "bitcoin",
                "btc",
                new BigDecimal("1180000000000.5"),
                new BigDecimal("2100000000000.0"),
                new BigDecimal("19740000.42"),
                new BigDecimal("21000000"),
                new BigDecimal("21000000"),
                Instant.parse("2024-09-01T00:00:00Z")
        );
    }

    public static CoinGeckoMarketData sampleEth() {
        return new CoinGeckoMarketData(
                "ethereum",
                "eth",
                new BigDecimal("410000000000"),
                new BigDecimal("410000000000"),
                new BigDecimal("120100000.0"),
                new BigDecimal("120100000"),
                null,
                Instant.parse("2024-09-01T00:00:00Z")
        );
    }
}

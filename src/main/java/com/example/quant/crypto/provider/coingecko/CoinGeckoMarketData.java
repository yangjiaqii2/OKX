package com.example.quant.crypto.provider.coingecko;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CoinGecko /coins/markets 单条记录的内部表示。
 *
 * <p>只承载 CoinGecko 原始字段，不引入任何"伪造默认"或"派生 ratio"。
 * 缺字段即 null，由 {@link CoinGeckoMapper} 决定是否回退 UNAVAILABLE。
 *
 * @param id                 CoinGecko coin id（必填，URL path 用）
 * @param symbol             原始 symbol（CoinGecko 大写）
 * @param marketCapUsd       流通市值，缺则 null
 * @param fdvUsd             完全稀释市值，缺则 null
 * @param circulatingSupply  流通量，缺则 null
 * @param totalSupply        总供应量，缺则 null
 * @param maxSupply          最大供应量，缺则 null
 * @param lastUpdated        CoinGecko last_updated（缺则 null）
 */
public record CoinGeckoMarketData(
        String id,
        String symbol,
        BigDecimal marketCapUsd,
        BigDecimal fdvUsd,
        BigDecimal circulatingSupply,
        BigDecimal totalSupply,
        BigDecimal maxSupply,
        Instant lastUpdated
) {
}

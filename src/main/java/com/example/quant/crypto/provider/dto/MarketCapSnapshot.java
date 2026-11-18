package com.example.quant.crypto.provider.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 市值快照。来源：外部 MarketCap Provider（CoinGecko / CoinMarketCap 等）。
 *
 * <p>关键规则：必须由真实 Provider 返回，禁止任何"根据价格 × 假定的 supply"伪造市值。
 * 所有字段在 Provider 没有数据时为 null，{@code null} 不是 0，Universe 硬资格视为缺失。
 *
 * @param baseSymbol         标准化后的 base symbol，例如 BTC、1000PEPE
 * @param marketCapUsd       流通市值（USD），可能为 null
 * @param fdvUsd             完全稀释市值（USD），可能为 null
 * @param circulatingSupply  当前流通量，可能为 null
 * @param maxSupply          最大供应量（优先用此计算 circulationRatio），可能为 null
 * @param totalSupply        总供应量（maxSupply 缺失时使用），可能为 null
 * @param snapshotAt         数据快照时间
 * @param source             数据源标识
 */
public record MarketCapSnapshot(
        String baseSymbol,
        BigDecimal marketCapUsd,
        BigDecimal fdvUsd,
        BigDecimal circulatingSupply,
        BigDecimal maxSupply,
        BigDecimal totalSupply,
        Instant snapshotAt,
        String source
) {
    public MarketCapSnapshot {
        if (baseSymbol == null || baseSymbol.isBlank()) {
            throw new IllegalArgumentException("baseSymbol required");
        }
    }
}

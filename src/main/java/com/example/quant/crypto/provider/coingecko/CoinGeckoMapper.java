package com.example.quant.crypto.provider.coingecko;

import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * CoinGecko 数据 ↔ {@link MarketCapSnapshot} 映射。
 *
 * <p>纯函数，零副作用，便于单测。
 *
 * <p>关键规则：<ul>
 *   <li>{@link CoinGeckoMarketData} 中所有 4 个核心字段（marketCapUsd / fdvUsd / circulatingSupply / totalSupply）都缺失 →
 *       {@link Result#unavailable(String)}，对应 ProviderResult.UNAVAILABLE</li>
 *   <li>marketCapUsd <= 0 / null + 其他有 → 视同"无市值"，UNAVAILABLE
 *       （Phase B Universe 硬资格要求 mcap 是真值）</li>
 *   <li>其他字段保留 null，由 Filter / Score 层判断能否用</li>
 * </ul>
 *
 * <p>绝不引入任何"默认市值"、"伪造 supply"、"根据价格 × 假定 supply 估算 FDV"。
 */
public final class CoinGeckoMapper {

    public enum Quality {
        FRESH,     // 4 个核心字段全部有效
        PARTIAL,   // marketCapUsd + 部分 supply 字段
        UNAVAILABLE // 数据缺失
    }

    public record Result(Quality quality, MarketCapSnapshot snapshot, String reason) {
        public boolean isUsable() {
            return quality != Quality.UNAVAILABLE && snapshot != null;
        }
    }

    private CoinGeckoMapper() {
    }

    /**
     * 把 CoinGecko 单条记录映射为 {@link MarketCapSnapshot}。
     * CoinGecko 字段缺失即 null，不替换为 0。
     */
    public static Result map(String baseSymbol, CoinGeckoMarketData data) {
        if (data == null) {
            return new Result(Quality.UNAVAILABLE, null, "coinGecko: no data");
        }
        if (baseSymbol == null || baseSymbol.isBlank()) {
            return new Result(Quality.UNAVAILABLE, null, "coinGecko: baseSymbol blank");
        }
        if (data.id() == null || data.id().isBlank()) {
            return new Result(Quality.UNAVAILABLE, null, "coinGecko: id missing");
        }
        if (data.marketCapUsd() == null || data.marketCapUsd().signum() <= 0) {
            return new Result(Quality.UNAVAILABLE, null, "coinGecko: marketCap missing or non-positive");
        }
        Quality q = allCoreFieldsPresent(data) ? Quality.FRESH : Quality.PARTIAL;
        BigDecimal totalSupply = pickTotal(data);
        MarketCapSnapshot snap = new MarketCapSnapshot(
                baseSymbol,
                scale(data.marketCapUsd(), 4),
                scale(data.fdvUsd(), 4),
                scale(data.circulatingSupply(), 4),
                scale(data.maxSupply(), 4),
                scale(totalSupply, 4),
                data.lastUpdated() == null ? Instant.now() : data.lastUpdated(),
                "coingecko"
        );
        return new Result(q, snap, null);
    }

    private static boolean allCoreFieldsPresent(CoinGeckoMarketData d) {
        return d.marketCapUsd() != null && d.marketCapUsd().signum() > 0
                && d.circulatingSupply() != null && d.circulatingSupply().signum() >= 0
                && (d.totalSupply() != null || d.maxSupply() != null);
    }

    /**
     * CoinGecko 中 totalSupply 与 maxSupply 通常同时存在；缺 maxSupply 时用 totalSupply。
     */
    private static BigDecimal pickTotal(CoinGeckoMarketData d) {
        if (d.totalSupply() != null) {
            return d.totalSupply();
        }
        return d.maxSupply();
    }

    private static BigDecimal scale(BigDecimal v, int scale) {
        if (v == null) {
            return null;
        }
        if (v.scale() <= scale) {
            return v;
        }
        return v.setScale(scale, RoundingMode.HALF_UP);
    }
}

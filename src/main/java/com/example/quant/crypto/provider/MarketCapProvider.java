package com.example.quant.crypto.provider;

import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MarketCap Provider 抽象。
 *
 * <p>Universe Hard Filter 必须基于真实 MarketCap；本接口不绑定具体供应商。
 * 生产默认实现：{@link NullMarketCapProvider}（始终 UNAVAILABLE，对应 MCap 数据缺失）。
 * 后续可注入 CoinGecko / CoinMarketCap 实现。
 */
public interface MarketCapProvider {
    /**
     * 拉取指定 base symbol 的市值快照。
     *
     * <p>实现必须捕获所有异常并返回 {@link DataQuality#UNAVAILABLE} 或
     * {@link DataQuality#TIMEOUT} 的 {@link ProviderResult}，禁止抛出。
     *
     * @param baseSymbol 已通过 {@link TokenSymbolMapper#mapSwapToBase(String)} 标准化
     * @return ProviderResult，失败时 data=null
     */
    ProviderResult<MarketCapSnapshot> fetch(String baseSymbol);

    /**
     * 批量拉取市值。默认逐条 {@link #fetch}；CoinGecko 实现会合并为单次 HTTP。
     */
    default Map<String, ProviderResult<MarketCapSnapshot>> fetchBatch(List<String> baseSymbols) {
        return fetchBatch(baseSymbols, null);
    }

    default Map<String, ProviderResult<MarketCapSnapshot>> fetchBatch(
            List<String> baseSymbols,
            Duration timeout
    ) {
        Map<String, ProviderResult<MarketCapSnapshot>> out = new LinkedHashMap<>();
        if (baseSymbols == null) {
            return out;
        }
        for (String base : baseSymbols) {
            if (base == null || base.isBlank()) {
                continue;
            }
            if (Thread.currentThread().isInterrupted()) {
                out.put(base.toUpperCase(), ProviderResult.timeout("interrupted"));
                continue;
            }
            if (timeout != null && timeout.isZero()) {
                out.put(base.toUpperCase(), ProviderResult.timeout("deadline exceeded"));
                continue;
            }
            String key = base.toUpperCase();
            out.put(key, fetch(key));
        }
        return out;
    }
}

package com.example.quant.crypto.provider;

import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MarketCap Provider 默认实现：当前没有真实外部数据源时使用。
 *
 * <p>始终返回 {@link DataQuality#UNAVAILABLE}，由 UniverseHardFilter 拒绝该候选。
 * 严禁伪造任何市值 / FDV / supply 数据。
 *
 * <p>非 Spring Bean：由 {@link com.example.quant.config.CoinGeckoProviderConfig} 在
 * CoinGecko 关闭时显式创建。切勿 {@code @Component}，否则会抢占 {@link MarketCapProvider}
 * 注入位，导致 CoinGecko 配置被 {@code @ConditionalOnMissingBean} 跳过。
 */
public class NullMarketCapProvider implements MarketCapProvider {
    private static final Logger log = LoggerFactory.getLogger(NullMarketCapProvider.class);

    @Override
    public ProviderResult<MarketCapSnapshot> fetch(String baseSymbol) {
        log.debug("NullMarketCapProvider returns UNAVAILABLE for baseSymbol={}", baseSymbol);
        return ProviderResult.unavailable("No MarketCap provider configured (NullMarketCapProvider)");
    }
}

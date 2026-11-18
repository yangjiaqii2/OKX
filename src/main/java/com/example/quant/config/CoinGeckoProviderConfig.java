package com.example.quant.config;

import com.example.quant.crypto.MarketCapSnapshotRepository;
import com.example.quant.crypto.provider.MarketCapProvider;
import com.example.quant.crypto.provider.coingecko.CoinGeckoClientImpl;
import com.example.quant.crypto.provider.coingecko.CoinGeckoHttpClient;
import com.example.quant.crypto.provider.coingecko.CoinGeckoMarketCapProvider;
import com.example.quant.crypto.provider.coingecko.CoinGeckoTokenBucket;
import com.example.quant.crypto.provider.NullMarketCapProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * CoinGecko Provider 的 Spring 装配。
 *
 * <p>默认启用；如需关闭：{@code selection.providers.coingecko.enabled=false}，回退 {@link NullMarketCapProvider}。
 */
@Configuration
public class CoinGeckoProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoProviderConfig.class);

    private final AgentProperties props;

    public CoinGeckoProviderConfig(AgentProperties props) {
        this.props = props;
    }

    @Bean
    @ConditionalOnMissingBean(CoinGeckoHttpClient.class)
    public CoinGeckoHttpClient coinGeckoHttpClient(
        @Value("${COINGECKO_API_KEY:#{null}}") String envApiKey) {
        String apiKey = resolveApiKey(envApiKey);
        String apiMode = props.selection().providers().getCoingecko().getApiMode();
        if (apiKey != null) {
            log.info("CoinGecko: using {} API authentication", apiMode);
        } else {
            log.info("CoinGecko: {} API without api key", apiMode);
        }
        return new CoinGeckoClientImpl(apiKey, apiMode);
    }

    @Bean
    @ConditionalOnMissingBean(CoinGeckoTokenBucket.class)
    public CoinGeckoTokenBucket coinGeckoTokenBucket() {
        int rpm = Math.max(1, props.selection().providers().getCoingecko().getRateLimitPerMinute());
        return new CoinGeckoTokenBucket(rpm);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(MarketCapProvider.class)
    public MarketCapProvider marketCapProvider(CoinGeckoHttpClient httpClient,
                                               CoinGeckoTokenBucket bucket,
                                               MarketCapSnapshotRepository snapshotRepository) {
        if (props.selection().providers().getCoingecko().isEnabled()) {
            log.info("MarketCapProvider = CoinGeckoMarketCapProvider");
            return new CoinGeckoMarketCapProvider("coingecko", httpClient, bucket, snapshotRepository);
        }
        log.info("MarketCapProvider = NullMarketCapProvider (CoinGecko disabled)");
        return new NullMarketCapProvider();
    }

    private String resolveApiKey(String envApiKey) {
        if (envApiKey != null && !envApiKey.isBlank()) {
            return envApiKey.trim();
        }
        AgentProperties.Providers.CoinGecko cg =
                props.selection().providers().getCoingecko();
        String yamlKey = cg.getApiKey();
        return (yamlKey == null || yamlKey.isBlank()) ? null : yamlKey.trim();
    }
}

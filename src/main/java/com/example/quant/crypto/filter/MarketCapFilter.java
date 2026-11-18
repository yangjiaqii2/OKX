package com.example.quant.crypto.filter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.quant.config.AgentProperties;
import java.math.BigDecimal;

/**
 * MarketCap 硬资格：{@code marketCapUsd >= minMarketCapUsd}。
 *
 * <p>规则（与 spec 一致）：<ul>
 *   <li>{@code UNAVAILABLE} / {@code TIMEOUT} → REJECT（reason {@code MARKET_CAP_UNAVAILABLE}）</li>
 *   <li>{@code STALE} → REJECT（reason {@code MARKET_CAP_STALE}）</li>
 *   <li>实际值缺失 → REJECT（reason {@code MARKET_CAP_MISSING}）</li>
 *   <li>实际值 &lt; 阈值 → REJECT（reason {@code MARKET_CAP_TOO_LOW}）</li>
 *   <li>实际值 &ge; 阈值 → PASS</li>
 * </ul>
 */
@Component
public class MarketCapFilter implements UniverseFilter {
    public static final String NAME = "market_cap";
    public static final String REASON_TOO_LOW = "MARKET_CAP_TOO_LOW";
    public static final String REASON_UNAVAILABLE = "MARKET_CAP_UNAVAILABLE";
    public static final String REASON_STALE = "MARKET_CAP_STALE";
    public static final String REASON_MISSING = "MARKET_CAP_MISSING";
    public static final String REASON_TIMEOUT = "MARKET_CAP_TIMEOUT";

    private final BigDecimal minMarketCapUsd;

    @Autowired
    public MarketCapFilter(AgentProperties properties) {
        this(properties.selection().universe().minMarketCapUsd());
    }

    public MarketCapFilter(BigDecimal minMarketCapUsd) {
        if (minMarketCapUsd == null || minMarketCapUsd.signum() <= 0) {
            throw new IllegalArgumentException("minMarketCapUsd must be > 0");
        }
        this.minMarketCapUsd = minMarketCapUsd;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public FilterResult apply(FilterContext context) {
        var result = context.marketCapResult();
        if (result == null) {
            return FilterResult.reject(NAME, REASON_MISSING);
        }
        return switch (result.dataQuality()) {
            case UNAVAILABLE -> FilterResult.reject(NAME, REASON_UNAVAILABLE, null, minMarketCapUsd, result.dataQuality());
            case TIMEOUT -> FilterResult.reject(NAME, REASON_TIMEOUT, null, minMarketCapUsd, result.dataQuality());
            case STALE -> FilterResult.reject(NAME, REASON_STALE, null, minMarketCapUsd, result.dataQuality());
            case PARTIAL -> FilterResult.reject(NAME, REASON_STALE, null, minMarketCapUsd, result.dataQuality());
            case AVAILABLE, FRESH -> {
                if (!result.hasData() || result.data().marketCapUsd() == null) {
                    yield FilterResult.reject(NAME, REASON_MISSING, null, minMarketCapUsd, result.dataQuality());
                }
                BigDecimal actual = result.data().marketCapUsd();
                if (actual.compareTo(minMarketCapUsd) < 0) {
                    yield FilterResult.reject(NAME, REASON_TOO_LOW, actual, minMarketCapUsd, result.dataQuality());
                }
                yield FilterResult.pass(NAME);
            }
        };
    }
}

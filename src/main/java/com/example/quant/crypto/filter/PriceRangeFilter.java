package com.example.quant.crypto.filter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.quant.config.AgentProperties;
import java.math.BigDecimal;

/**
 * PriceRange 硬资格：保留现有 {@code range24hPct <= max24hRangePct} 逻辑。
 *
 * <p>range 缺失时保守 PASS（不卡人），让后续 Filter / Score 决定。
 */
@Component
public class PriceRangeFilter implements UniverseFilter {
    public static final String NAME = "price_range";
    public static final String REASON_TOO_HIGH = "PRICE_RANGE_TOO_HIGH";

    private final BigDecimal max24hRangePct;

    @Autowired
    public PriceRangeFilter(AgentProperties properties) {
        this(properties.market().max24hRangePct());
    }

    public PriceRangeFilter(BigDecimal max24hRangePct) {
        if (max24hRangePct == null || max24hRangePct.signum() <= 0) {
            throw new IllegalArgumentException("max24hRangePct must be > 0");
        }
        this.max24hRangePct = max24hRangePct;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public FilterResult apply(FilterContext context) {
        BigDecimal range = context.range24hPct();
        if (range == null) {
            return FilterResult.pass(NAME);
        }
        if (range.compareTo(max24hRangePct) > 0) {
            return FilterResult.reject(NAME, REASON_TOO_HIGH, range, max24hRangePct, null);
        }
        return FilterResult.pass(NAME);
    }
}

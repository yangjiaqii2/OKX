package com.example.quant.crypto.filter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.quant.config.AgentProperties;
import java.math.BigDecimal;

/**
 * Liquidity 硬资格：基于 spread + depth。
 *
 * <p>规则：<ul>
 *   <li>{@code spreadBps} 缺失 → PASS（保守）</li>
 *   <li>{@code spreadBps > maxSpreadBps} → REJECT {@code SPREAD_TOO_WIDE}</li>
 *   <li>{@code availableDepthUsdt < minDepthUsdt} → REJECT {@code DEPTH_TOO_LOW}</li>
 * </ul>
 */
@Component
public class LiquidityFilter implements UniverseFilter {
    public static final String NAME = "liquidity";
    public static final String REASON_SPREAD = "SPREAD_TOO_WIDE";
    public static final String REASON_DEPTH = "DEPTH_TOO_LOW";

    private final BigDecimal maxSpreadBps;
    private final BigDecimal minDepthUsdt;

    @Autowired
    public LiquidityFilter(AgentProperties properties) {
        this(properties.market().maxSpreadBps(), properties.market().minDepthUsdt());
    }

    public LiquidityFilter(BigDecimal maxSpreadBps, BigDecimal minDepthUsdt) {
        if (maxSpreadBps == null || maxSpreadBps.signum() < 0) {
            throw new IllegalArgumentException("maxSpreadBps must be >= 0");
        }
        if (minDepthUsdt == null || minDepthUsdt.signum() < 0) {
            throw new IllegalArgumentException("minDepthUsdt must be >= 0");
        }
        this.maxSpreadBps = maxSpreadBps;
        this.minDepthUsdt = minDepthUsdt;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public FilterResult apply(FilterContext context) {
        BigDecimal spread = context.spreadBps();
        if (spread != null && spread.compareTo(maxSpreadBps) > 0) {
            return FilterResult.reject(NAME, REASON_SPREAD, spread, maxSpreadBps, null);
        }
        BigDecimal depth = context.availableDepthUsdt();
        if (depth != null && depth.compareTo(minDepthUsdt) < 0) {
            return FilterResult.reject(NAME, REASON_DEPTH, depth, minDepthUsdt, null);
        }
        return FilterResult.pass(NAME);
    }
}

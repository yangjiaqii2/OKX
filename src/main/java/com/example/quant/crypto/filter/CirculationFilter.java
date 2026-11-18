package com.example.quant.crypto.filter;

import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.math.BigDecimal;

/**
 * Phase B 流通量硬资格 Filter。
 *
 * <p>校验优先级：<ol>
 *   <li>若 {@link FilterContext#circulationRatio} 已知（非 null），直接 ratio vs threshold</li>
 *   <li>否则走 snapshot absolute：circulatingSupply / maxSupply (or totalSupply)</li>
 *   <li>snapshot UNAVAILABLE / TIMEOUT → 拒（理由以 _UNAVAILABLE / _TIMEOUT 结尾）</li>
 *   <li>snapshot STALE → 拒（理由 _STALE）</li>
 *   <li>所有必要数据都缺失 → 拒（理由 _MISSING）</li>
 * </ol>
 */
public final class CirculationFilter implements UniverseFilter {

    public static final String NAME = "circulation";
    public static final String REASON_TOO_LOW = "CIRCULATION_TOO_LOW";
    public static final String REASON_MISSING = "CIRCULATION_MISSING";
    public static final String REASON_UNAVAILABLE = "CIRCULATION_UNAVAILABLE";
    public static final String REASON_TIMEOUT = "CIRCULATION_TIMEOUT";
    public static final String REASON_STALE = "CIRCULATION_STALE";

    private final BigDecimal minCirculationRatio;

    public CirculationFilter(BigDecimal minCirculationRatio) {
        if (minCirculationRatio == null) {
            throw new IllegalArgumentException("minCirculationRatio is null");
        }
        if (minCirculationRatio.signum() < 0 || minCirculationRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("minCirculationRatio must be in [0,1]: " + minCirculationRatio);
        }
        this.minCirculationRatio = minCirculationRatio;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public FilterResult apply(FilterContext ctx) {
        // 路径 1：candidate 级别 ratio 优先
        BigDecimal ratio = ctx.safeCirculationRatio();
        if (ratio != null) {
            if (ratio.compareTo(minCirculationRatio) < 0) {
                return FilterResult.reject(NAME, REASON_TOO_LOW, ratio, minCirculationRatio,
                        DataQuality.FRESH);
            }
            return FilterResult.pass(NAME);
        }
        // 路径 2：snapshot absolute
        ProviderResult<MarketCapSnapshot> result = ctx.marketCapResult();
        if (result == null) {
            return FilterResult.reject(NAME, REASON_MISSING, null, minCirculationRatio, DataQuality.UNAVAILABLE);
        }
        DataQuality quality = result.dataQuality();
        if (quality == DataQuality.UNAVAILABLE) {
            return FilterResult.reject(NAME, REASON_UNAVAILABLE, null, minCirculationRatio, quality);
        }
        if (quality == DataQuality.TIMEOUT) {
            return FilterResult.reject(NAME, REASON_TIMEOUT, null, minCirculationRatio, quality);
        }
        if (quality == DataQuality.STALE) {
            return FilterResult.reject(NAME, REASON_STALE, null, minCirculationRatio, quality);
        }
        MarketCapSnapshot data = result.data();
        if (data == null) {
            return FilterResult.reject(NAME, REASON_MISSING, null, minCirculationRatio, quality);
        }
        BigDecimal circulating = data.circulatingSupply();
        BigDecimal max = data.maxSupply();
        if (max == null || max.signum() == 0) {
            max = data.totalSupply();
        }
        if (circulating == null || max == null || max.signum() == 0) {
            return FilterResult.reject(NAME, REASON_MISSING, null, minCirculationRatio, quality);
        }
        BigDecimal actualRatio = circulating.divide(max, 6, java.math.RoundingMode.HALF_UP);
        if (actualRatio.compareTo(minCirculationRatio) < 0) {
            return FilterResult.reject(NAME, REASON_TOO_LOW, actualRatio, minCirculationRatio, quality);
        }
        return FilterResult.pass(NAME);
    }
}

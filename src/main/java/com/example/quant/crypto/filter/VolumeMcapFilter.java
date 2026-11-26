package com.example.quant.crypto.filter;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase B 流通量-市值比 Filter。
 *
 * <p>ratio = volume24h / marketCapUsd。
 *
 * <p>校验优先级：<ol>
 *   <li>若 {@link FilterContext#volumeMcapRatio} 已知，直接 ratio vs threshold</li>
 *   <li>否则从 snapshot.marketCapUsd() 与 ctx.volume24hUsd() 实时计算</li>
 *   <li>任一数据缺失或 &le; 0 → 拒（区分 _MCAP_MISSING / _VOLUME_MISSING）</li>
 *   <li>DataQuality 状态：UNAVAILABLE → _MCAP_UNAVAILABLE；STALE → _MCAP_STALE</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(prefix = "quant.agent.selection.universe", name = "volume-mcap-enabled", havingValue = "true")
public final class VolumeMcapFilter implements UniverseFilter {

    public static final String NAME = "volume_mcap";
    public static final String REASON_TOO_LOW = "VOLUME_MCAP_TOO_LOW";
    public static final String REASON_MCAP_MISSING = "VOLUME_MCAP_MCAP_MISSING";
    public static final String REASON_MCAP_UNAVAILABLE = "VOLUME_MCAP_MCAP_UNAVAILABLE";
    public static final String REASON_MCAP_TIMEOUT = "VOLUME_MCAP_MCAP_TIMEOUT";
    public static final String REASON_MCAP_STALE = "VOLUME_MCAP_MCAP_STALE";
    public static final String REASON_VOLUME_MISSING = "VOLUME_MCAP_VOLUME_MISSING";

    private final BigDecimal minVolumeMcapRatio;

    @Autowired
    public VolumeMcapFilter(AgentProperties properties) {
        this(properties.selection().universe().minVolumeMcapRatio());
    }

    public VolumeMcapFilter(BigDecimal minVolumeMcapRatio) {
        if (minVolumeMcapRatio == null) {
            throw new IllegalArgumentException("minVolumeMcapRatio is null");
        }
        if (minVolumeMcapRatio.signum() < 0) {
            throw new IllegalArgumentException("minVolumeMcapRatio must be >= 0: " + minVolumeMcapRatio);
        }
        this.minVolumeMcapRatio = minVolumeMcapRatio;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public FilterResult apply(FilterContext ctx) {
        // 路径 1：candidate 级别 ratio 优先
        BigDecimal ratio = ctx.safeVolumeMcapRatio();
        if (ratio != null) {
            if (ratio.compareTo(minVolumeMcapRatio) < 0) {
                return FilterResult.reject(NAME, REASON_TOO_LOW, ratio, minVolumeMcapRatio, DataQuality.FRESH);
            }
            return FilterResult.pass(NAME);
        }
        // 路径 2：Provider 路径。先检 DataQuality 状态再计算 ratio。
        // STALE / UNAVAILABLE / TIMEOUT 数据不应参与 ratio 计算。
        ProviderResult<MarketCapSnapshot> mcapResult = ctx.marketCapResult();
        if (mcapResult != null) {
            DataQuality mq = mcapResult.dataQuality();
            if (mq == DataQuality.STALE) {
                return FilterResult.reject(NAME, REASON_MCAP_STALE, null, minVolumeMcapRatio, mq);
            }
            if (mq == DataQuality.TIMEOUT) {
                return FilterResult.reject(NAME, REASON_MCAP_TIMEOUT, null, minVolumeMcapRatio, mq);
            }
            if (mq == DataQuality.UNAVAILABLE) {
                return FilterResult.reject(NAME, REASON_MCAP_UNAVAILABLE, null, minVolumeMcapRatio, mq);
            }
        }
        BigDecimal mcapUsd = ctx.safeMarketCapUsd();
        if (mcapUsd == null) {
            return FilterResult.reject(NAME, REASON_MCAP_MISSING, null, minVolumeMcapRatio,
                    mcapResult == null ? DataQuality.UNAVAILABLE : mcapResult.dataQuality());
        }
        BigDecimal volume = ctx.volume24hUsd();
        if (volume == null || volume.signum() == 0) {
            return FilterResult.reject(NAME, REASON_VOLUME_MISSING, null, minVolumeMcapRatio,
                    DataQuality.FRESH);
        }
        BigDecimal computed = volume.divide(mcapUsd, 6, java.math.RoundingMode.HALF_UP);
        if (computed.compareTo(minVolumeMcapRatio) < 0) {
            return FilterResult.reject(NAME, REASON_TOO_LOW, computed, minVolumeMcapRatio,
                    DataQuality.FRESH);
        }
        return FilterResult.pass(NAME);
    }
}

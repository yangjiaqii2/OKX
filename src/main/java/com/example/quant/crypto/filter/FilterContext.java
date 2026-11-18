package com.example.quant.crypto.filter;

import com.example.quant.crypto.provider.MarketCapProvider;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Phase B Filter 输入上下文。
 *
 * <p>职责：把 candidate 级别（来自 scanV1 路径/Provider 路径）的核心价格/流通/质量信息
 * 打包为 {@link UniverseFilter} 链的输入。
 *
 * <p>字段说明：<ul>
 *   <li>{@code instId}、{@code baseSymbol}：必填，用于诊断与日志</li>
 *   <li>{@code lastPrice}、{@code volume24hUsd}：来自 ticker snapshot，必填非负</li>
 *   <li>{@code range24hPct}、{@code spreadBps}、{@code availableDepthUsdt}、{@code openInterestUsd}：
 *       可选（缺失时对应 filter 走"保守 PASS"）</li>
 *   <li>{@code marketCapResult}：来自 {@link MarketCapProvider}，可能 UNAVAILABLE/STALE/FRESH</li>
 *   <li>{@code circulationRatio} / {@code volumeMcapRatio}：candidate 级别 V2 字段
 *       <b>优先</b>供 {@link CirculationFilter} / {@link VolumeMcapFilter} 使用；
 *       缺失时回退到 snapshot absolute 路径。
 *       <br>设计原因：Phase B V2 scanner 在 {@code NullMarketCapProvider} 时 MarketCapSnapshot
 *       没有 absolute circulating/maxSupply，但 candidate 已带 ratio。Ratio 优先确保
 *       V2 path 不会因为 provider 暂未实现而误杀一切 candidate。</li>
 * </ul>
 */
public record FilterContext(
        String instId,
        String baseSymbol,
        BigDecimal lastPrice,
        BigDecimal volume24hUsd,
        BigDecimal range24hPct,
        BigDecimal spreadBps,
        BigDecimal availableDepthUsdt,
        BigDecimal openInterestUsd,
        ProviderResult<MarketCapSnapshot> marketCapResult,
        BigDecimal circulationRatio,
        BigDecimal volumeMcapRatio
) {

    public FilterContext {
        Objects.requireNonNull(instId, "instId");
        if (instId.isBlank()) {
            throw new IllegalArgumentException("instId blank");
        }
        if (baseSymbol == null || baseSymbol.isBlank()) {
            throw new IllegalArgumentException("baseSymbol blank");
        }
    }

    /**
     * 9 字段 overload（向后兼容）：不带 V2 candidate-level ratio 字段，filter 回退到
     * snapshot absolute 路径。
     */
    public FilterContext(
            String instId,
            String baseSymbol,
            BigDecimal lastPrice,
            BigDecimal volume24hUsd,
            BigDecimal range24hPct,
            BigDecimal spreadBps,
            BigDecimal availableDepthUsdt,
            BigDecimal openInterestUsd,
            ProviderResult<MarketCapSnapshot> marketCapResult
    ) {
        this(instId, baseSymbol, lastPrice, volume24hUsd, range24hPct, spreadBps,
                availableDepthUsdt, openInterestUsd, marketCapResult, null, null);
    }

    /**
     * V2 candidate-level ratio 优先路径：扫描器/AI pool 已从 candidate 直接拿到 ratio，
     * 无需 snapshot absolute。
     */
    public static FilterContext withV2Ratios(
            String instId,
            String baseSymbol,
            BigDecimal lastPrice,
            BigDecimal volume24hUsd,
            BigDecimal range24hPct,
            BigDecimal spreadBps,
            BigDecimal availableDepthUsdt,
            BigDecimal openInterestUsd,
            ProviderResult<MarketCapSnapshot> marketCapResult,
            BigDecimal circulationRatio,
            BigDecimal volumeMcapRatio
    ) {
        return new FilterContext(instId, baseSymbol, lastPrice, volume24hUsd, range24hPct,
                spreadBps, availableDepthUsdt, openInterestUsd, marketCapResult,
                circulationRatio, volumeMcapRatio);
    }

    /**
     * 兼容访问器：filter 读取 ratio 时统一调此方法，返回非负 ratio（负数视为 null）。
     */
    public BigDecimal safeCirculationRatio() {
        if (circulationRatio == null) return null;
        return circulationRatio.signum() < 0 ? null : circulationRatio;
    }

    public BigDecimal safeVolumeMcapRatio() {
        if (volumeMcapRatio == null) return null;
        return volumeMcapRatio.signum() < 0 ? null : volumeMcapRatio;
    }

    public BigDecimal safeMarketCapUsd() {
        if (marketCapResult == null || marketCapResult.data() == null) {
            return null;
        }
        BigDecimal v = marketCapResult.data().marketCapUsd();
        return v == null || v.signum() <= 0 ? null : v;
    }
}

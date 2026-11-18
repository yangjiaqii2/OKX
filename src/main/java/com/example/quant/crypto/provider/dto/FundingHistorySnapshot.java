package com.example.quant.crypto.provider.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Funding Rate 历史快照。来源：OKX {@code /api/v5/public/funding-rate-history}。
 *
 * <p>Provider 拉取最近 3 个 funding events（≈ 24h，因为 OKX SWAP funding 间隔为 8h），
 * 一次性完成：<ol>
 *   <li>解析为时间正序序列 {@code [t1, t2, t3]}（按 fundingTime 排序）</li>
 *   <li>计算线性回归斜率 {@code slope}（{@code x=[1,2,3]} vs {@code y=fundingRate[i]}）</li>
 *   <li>根据斜率阈值（±0.001）分类为 {@link FundingTrend#HEATING} / {@link #COOLING} / {@link #STABLE}</li>
 *   <li>根据 {@code |rate24h| >= extremeFundingRate}（默认 0.0030）打 {@link #extreme()} 标签</li>
 * </ol>
 *
 * <p>所有 fundingRate 字段以小数形式存储（{@code 0.0001} = 0.01%），与 OKX 原生返回一致。
 *
 * <p>若不足 3 个 events，Provider 仍返回本 snapshot（{@code rawRates} 长度 < 3），
 * 但 {@link #dataQuality()} = {@code STALE}，调用方需自行决定是否信任。
 *
 * @param instId         标的 ID
 * @param currentRate    最近一次 funding rate（最新 fundingTime 对应）
 * @param rate24h        24h 前的 funding rate（用于 EXTREME 判断）；不足 3 events 时为 null
 * @param slope          最近 3 个 funding events 线性回归斜率（不足 3 events 或全相等时为 0.0）
 * @param trend          由 slope 分类得出的 FundingTrend
 * @param extreme        是否触发极端资金费率标签（{@code |rate24h| >= extremeFundingRate}）
 * @param rawRates       原始 funding rate 序列（按时间正序），调试 / 审计用；不足 3 events 时长度 < 3
 * @param dataQuality    数据质量（3 events → FRESH；1-2 events → STALE）
 * @param snapshotAt     OKX 最新 fundingTime
 * @param fetchedAt      本地拉取时间
 * @param source         数据源标识（{@code "okx"}）
 */
public record FundingHistorySnapshot(
        String instId,
        BigDecimal currentRate,
        BigDecimal rate24h,
        double slope,
        FundingTrend trend,
        boolean extreme,
        List<BigDecimal> rawRates,
        DataQuality dataQuality,
        Instant snapshotAt,
        Instant fetchedAt,
        String source
) {
    public FundingHistorySnapshot {
        if (instId == null || instId.isBlank()) {
            throw new IllegalArgumentException("instId required");
        }
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt required");
        }
        if (dataQuality == null) {
            throw new IllegalArgumentException("dataQuality required");
        }
        if (trend == null) {
            throw new IllegalArgumentException("trend required");
        }
        if (rawRates == null) {
            rawRates = List.of();
        } else {
            rawRates = List.copyOf(rawRates);
        }
    }

    /**
     * 方便调用方使用的 alias（与 {@link com.example.quant.crypto.provider.DataQuality} 一致）。
     */
    public enum DataQuality {
        /** 3 个 funding events 完整，Provider 信任。 */
        FRESH,
        /** 1-2 个 events，调用方需谨慎。 */
        STALE,
        /** Provider 无数据 / 异常。 */
        UNAVAILABLE
    }
}

package com.example.quant.crypto.provider.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Open Interest 历史快照。来源：OKX {@code /api/v5/rubik/stat/contracts/open-interest-history}。
 *
 * <p>Provider 拉取最长窗口（24h，OKX SWAP OI 每 1h 一个采样点，共 24 个），内部切窗为
 * {@code 1h / 4h / 24h} 三个变化率。绝不在 Provider 内部对 24h 窗口做多次 HTTP 调用。
 *
 * <p><b>Provider 输出的是纯 OI 数据，不含 Direction。</b>Direction 由聚合层
 * （{@code OkxContractScanner.scanV2}）通过 {@code OIDirection.classify(priceChange24h, oiChange24h)}
 * 计算，强度由本 snapshot 的 3 个变化率数值字段体现。
 *
 * <p>所有变化率字段以"百分比"为单位（{@code 0.05} = +5%），符号规则与 OKX 一致。
 *
 * @param instId        标的 ID（{@code BTC-USDT-SWAP}）
 * @param currentOi     最新 OI（base/coin 单位，与 OKX 返回的 {@code oiCcy} 一致）
 * @param oiChange1h    最近 1h 变化率（百分比，{@code +0.05} = +5%）；数据点缺失时为 null
 * @param oiChange4h    最近 4h 变化率
 * @param oiChange24h   最近 24h 变化率
 * @param snapshotAt    OKX 拉取时间（response 中最新 ts）
 * @param fetchedAt     本地拉取时间（用于 ProviderCache TTL）
 * @param source        数据源标识（{@code "okx"}）
 */
public record OpenInterestHistorySnapshot(
        String instId,
        BigDecimal currentOi,
        BigDecimal oiChange1h,
        BigDecimal oiChange4h,
        BigDecimal oiChange24h,
        Instant snapshotAt,
        Instant fetchedAt,
        String source
) {
    public OpenInterestHistorySnapshot {
        if (instId == null || instId.isBlank()) {
            throw new IllegalArgumentException("instId required");
        }
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt required");
        }
    }
}

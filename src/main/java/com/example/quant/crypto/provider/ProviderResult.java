package com.example.quant.crypto.provider;

import java.time.Duration;
import java.time.Instant;

/**
 * 通用 Provider 包装结果。
 *
 * <p>所有第三方数据访问必须通过 {@link ProviderResult} 传递，禁止直接返回裸的第三方 DTO。
 * 这样可以在 Filter / Score 层显式检查 {@link DataQuality}，避免把缺失数据当作 0 参与计算。
 *
 * @param data         Provider 返回的数据，UNAVAILABLE / TIMEOUT 时为 null
 * @param dataQuality  数据质量
 * @param fetchedAt    拉取时间，UNAVAILABLE / TIMEOUT 时为当前时间
 * @param source       数据源标识（CoinGecko / CoinMarketCap / OKX / null）
 * @param errorCode    错误码（成功时为 null）
 * @param errorMessage 错误描述（成功时为 null）
 * @param <T>          数据类型
 */
public record ProviderResult<T>(
        T data,
        DataQuality dataQuality,
        Instant fetchedAt,
        String source,
        String errorCode,
        String errorMessage
) {
    public static <T> ProviderResult<T> unavailable(String reason) {
        return new ProviderResult<>(null, DataQuality.UNAVAILABLE, Instant.now(), null,
                "UNAVAILABLE", reason);
    }

    public static <T> ProviderResult<T> timeout(String reason) {
        return new ProviderResult<>(null, DataQuality.TIMEOUT, Instant.now(), null,
                "TIMEOUT", reason);
    }

    /** 上游明确没有该标的；允许调用方做短期负缓存，避免每轮重复查询。 */
    public static <T> ProviderResult<T> notFound(String reason) {
        return new ProviderResult<>(null, DataQuality.UNAVAILABLE, Instant.now(), null,
                "NOT_FOUND", reason);
    }

    public static <T> ProviderResult<T> fresh(T data, String source) {
        return new ProviderResult<>(data, DataQuality.FRESH, Instant.now(), source, null, null);
    }

    public static <T> ProviderResult<T> available(T data, String source) {
        return new ProviderResult<>(data, DataQuality.AVAILABLE, Instant.now(), source, null, null);
    }

    public static <T> ProviderResult<T> stale(T data, String source, Instant fetchedAt) {
        return new ProviderResult<>(data, DataQuality.STALE, fetchedAt, source, null, null);
    }

    /** 数据是否可用于 Universe 硬资格过滤（FRESH 或 AVAILABLE）。 */
    public boolean isUsable() {
        return dataQuality == DataQuality.AVAILABLE || dataQuality == DataQuality.FRESH;
    }

    /** 数据是否存在（即使 STALE 也算）。 */
    public boolean hasData() {
        return data != null;
    }

    public Duration age(Instant now) {
        if (fetchedAt == null) {
            return Duration.ofSeconds(Long.MAX_VALUE);
        }
        return Duration.between(fetchedAt, now);
    }
}

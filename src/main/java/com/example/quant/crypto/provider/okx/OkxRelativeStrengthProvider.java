package com.example.quant.crypto.provider.okx;

import com.example.quant.crypto.OkxMarketService;
import com.example.quant.crypto.dto.ContractKlineSet;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderCache;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.RelativeStrengthProvider;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * OKX 相对强弱 Provider。
 *
 * <p>24h 收益需要至少 25 个连续收盘整点。请求 limit 预留未收盘过滤后的余量；
 * {@link ProviderResult} 的 dataQuality 必须与 snapshot 内部质量一致。
 */
public final class OkxRelativeStrengthProvider {
    private static final String BTC = "BTC-USDT-SWAP";
    private static final String ETH = "ETH-USDT-SWAP";
    /**
     * 1H K 线请求数量：24h 窗口需要 25 个收盘点，再预留若干根以应对未收盘过滤与偶发缺棒。
     * 与扫描器 {@code safeKlines} 的 1H=72 对齐上限，避免重复过小请求。
     */
    static final int HOURLY_CANDLE_LIMIT = 48;
    private static final int MIN_POINTS_FOR_24H = 25;

    private final OkxMarketService marketService;
    private final ProviderCache<String, List<com.example.quant.crypto.dto.ContractCandle>> benchmarkCache;

    public OkxRelativeStrengthProvider(OkxMarketService marketService, int freshTtlSeconds, int staleTtlSeconds) {
        this.marketService = marketService;
        this.benchmarkCache = new ProviderCache<>("RelativeStrengthBenchmark",
                Duration.ofSeconds(freshTtlSeconds), Duration.ofSeconds(staleTtlSeconds));
    }

    public ProviderResult<RelativeStrengthSnapshot> fetch(String instId, ContractKlineSet existing) {
        return fetch(instId, existing, null);
    }

    public ProviderResult<RelativeStrengthSnapshot> fetch(String instId, ContractKlineSet existing, Duration timeout) {
        if (instId == null || existing == null) {
            return ProviderResult.unavailable("RS_PROVIDER_UNAVAILABLE: missing input");
        }
        if (timeout != null && timeout.isZero()) {
            return ProviderResult.timeout("RS_TIMEOUT: deadline exceeded");
        }
        if (Thread.currentThread().isInterrupted()) {
            return ProviderResult.timeout("RS_TIMEOUT: interrupted");
        }
        ProviderResult<List<com.example.quant.crypto.dto.ContractCandle>> btc = benchmark(BTC, timeout);
        if (btc != null && btc.dataQuality() == DataQuality.TIMEOUT) {
            return ProviderResult.timeout(btc.errorMessage());
        }
        ProviderResult<List<com.example.quant.crypto.dto.ContractCandle>> eth = benchmark(ETH, timeout);
        if (eth != null && eth.dataQuality() == DataQuality.TIMEOUT) {
            return ProviderResult.timeout(eth.errorMessage());
        }
        DataQuality quality = quality(btc, eth);
        List<com.example.quant.crypto.dto.ContractCandle> coinCandles = existing.oneHour();
        if (coinCandles == null || coinCandles.size() < MIN_POINTS_FOR_24H) {
            try {
                coinCandles = marketService.candles(instId, "1H", HOURLY_CANDLE_LIMIT, timeout);
            } catch (RuntimeException ex) {
                if (isTimeout(ex)) {
                    return ProviderResult.timeout("RS_TIMEOUT: coin candles timed out");
                }
                return ProviderResult.unavailable("RS_PROVIDER_UNAVAILABLE: coin candles: " + ex.getMessage());
            }
        }
        RelativeStrengthSnapshot snapshot = RelativeStrengthProvider.calculate(instId,
                coinCandles, btc == null ? null : btc.data(), eth == null ? null : eth.data(),
                quality, Instant.now());
        DataQuality resultQuality = snapshot.dataQuality() == DataQuality.UNAVAILABLE
                ? DataQuality.UNAVAILABLE
                : quality;
        if (snapshot.rs1h() == null && snapshot.rs4h() == null && snapshot.rs24h() == null
                || resultQuality == DataQuality.UNAVAILABLE) {
            return new ProviderResult<>(snapshot, DataQuality.UNAVAILABLE, Instant.now(), "OKX",
                    "RS_INSUFFICIENT_DATA", "relative strength window unavailable");
        }
        return new ProviderResult<>(snapshot, resultQuality, Instant.now(), "OKX", null, null);
    }

    private ProviderResult<List<com.example.quant.crypto.dto.ContractCandle>> benchmark(String instId,
                                                                                        Duration timeout) {
        if (timeout != null && timeout.isZero()) {
            return ProviderResult.timeout("RS_TIMEOUT: deadline exceeded");
        }
        return benchmarkCache.get(instId, key -> {
            try {
                return ProviderResult.fresh(marketService.candles(key, "1H", HOURLY_CANDLE_LIMIT, timeout), "OKX");
            } catch (RuntimeException ex) {
                if (isTimeout(ex)) {
                    return ProviderResult.timeout("RS_TIMEOUT: benchmark candles timed out");
                }
                return ProviderResult.unavailable("RS_PROVIDER_UNAVAILABLE: candles: " + ex.getMessage());
            }
        });
    }

    private static DataQuality quality(ProviderResult<?> btc, ProviderResult<?> eth) {
        if (btc == null || eth == null || !btc.hasData() || !eth.hasData()) {
            return DataQuality.UNAVAILABLE;
        }
        if (btc.dataQuality() == DataQuality.STALE || eth.dataQuality() == DataQuality.STALE) {
            return DataQuality.STALE;
        }
        return btc.dataQuality() == DataQuality.FRESH && eth.dataQuality() == DataQuality.FRESH
                ? DataQuality.FRESH : DataQuality.AVAILABLE;
    }

    private static boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (current instanceof java.util.concurrent.TimeoutException
                    || (message != null && message.toLowerCase().contains("timed out"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

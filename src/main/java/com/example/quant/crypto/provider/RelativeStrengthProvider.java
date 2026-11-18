package com.example.quant.crypto.provider;

import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相对强弱纯计算。
 *
 * <p>24h / 4h / 1h 收益都要求：同一结束整点上，候选与 BTC/ETH 都有收盘价，
 * 且各自在 {@code end - N hours} 也有精确整点价格。不得用更早的价格冒充目标窗口，
 * 也不得把不同结束时点的涨幅相减。
 *
 * <p>共同终点还必须相对扫描时间足够新，且任一序列最新收盘相对共同终点的偏离不能过大，
 * 否则标为 UNAVAILABLE——数量够但行情过旧时不得继续评分。
 */
public final class RelativeStrengthProvider {
    private static final int SCALE = 6;
    private static final long HOUR_MILLIS = Duration.ofHours(1).toMillis();
    /**
     * 共同终点相对 asOf 的最大滞后：覆盖“当前小时未收盘 + 整点切换短暂缺棒”。
     */
    static final Duration MAX_SHARED_END_LAG = Duration.ofHours(3);
    /** 任一序列最新收盘相对共同终点的最大超前（整点切换允许差一根）。 */
    static final Duration MAX_SERIES_SKEW = Duration.ofHours(1);
    /** 允许时钟轻微快于行情时间戳。 */
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private RelativeStrengthProvider() {
    }

    public static RelativeStrengthSnapshot calculate(String instId, List<ContractCandle> coin,
                                                      List<ContractCandle> btc,
                                                      List<ContractCandle> eth,
                                                      DataQuality quality) {
        return calculate(instId, coin, btc, eth, quality, Instant.now());
    }

    public static RelativeStrengthSnapshot calculate(String instId, List<ContractCandle> coin,
                                                      List<ContractCandle> btc,
                                                      List<ContractCandle> eth,
                                                      DataQuality quality,
                                                      Instant asOf) {
        Map<Long, BigDecimal> coinPrices = prices(coin);
        Map<Long, BigDecimal> btcPrices = prices(btc);
        Map<Long, BigDecimal> ethPrices = prices(eth);
        Long endTs = sharedEndTimestamp(coinPrices, btcPrices, ethPrices);
        Instant windowEnd = endTs == null ? null : Instant.ofEpochMilli(endTs);
        if (endTs == null || !windowFreshEnough(coinPrices, btcPrices, ethPrices, endTs, asOf)) {
            return unavailable(instId, windowEnd);
        }
        BigDecimal coin1h = changeAt(coinPrices, endTs, 1);
        BigDecimal coin4h = changeAt(coinPrices, endTs, 4);
        BigDecimal coin24h = changeAt(coinPrices, endTs, 24);
        BigDecimal btc1h = changeAt(btcPrices, endTs, 1);
        BigDecimal btc4h = changeAt(btcPrices, endTs, 4);
        BigDecimal btc24h = changeAt(btcPrices, endTs, 24);
        BigDecimal eth1h = changeAt(ethPrices, endTs, 1);
        BigDecimal eth4h = changeAt(ethPrices, endTs, 4);
        BigDecimal eth24h = changeAt(ethPrices, endTs, 24);
        DataQuality effectiveQuality = coin1h == null || coin4h == null || coin24h == null
                || btc1h == null || btc4h == null || btc24h == null
                || eth1h == null || eth4h == null || eth24h == null
                ? DataQuality.UNAVAILABLE : quality;
        if (effectiveQuality == DataQuality.UNAVAILABLE) {
            return new RelativeStrengthSnapshot(instId, coin1h, coin4h, coin24h,
                    btc1h, btc4h, btc24h, eth1h, eth4h, eth24h,
                    relative(coin1h, btc1h, eth1h), relative(coin4h, btc4h, eth4h),
                    relative(coin24h, btc24h, eth24h), DataQuality.UNAVAILABLE, windowEnd);
        }
        return new RelativeStrengthSnapshot(instId, coin1h, coin4h, coin24h,
                btc1h, btc4h, btc24h, eth1h, eth4h, eth24h,
                relative(coin1h, btc1h, eth1h), relative(coin4h, btc4h, eth4h),
                relative(coin24h, btc24h, eth24h), effectiveQuality, windowEnd);
    }

    private static RelativeStrengthSnapshot unavailable(String instId, Instant windowEnd) {
        return new RelativeStrengthSnapshot(instId, null, null, null, null, null, null,
                null, null, null, null, null, null, DataQuality.UNAVAILABLE, windowEnd);
    }

    private static Map<Long, BigDecimal> prices(List<ContractCandle> candles) {
        Map<Long, BigDecimal> prices = new HashMap<>();
        if (candles == null) {
            return prices;
        }
        candles.stream().filter(c -> c != null && c.timestamp() != null)
                .sorted(Comparator.comparing(ContractCandle::timestamp))
                .forEach(c -> {
                    if (c.close() != null && c.close().signum() > 0) {
                        prices.put(c.timestamp().toEpochMilli(), c.close());
                    }
                });
        return prices;
    }

    /**
     * 三方价格图的共同结束整点：取交集中的最大时间戳。
     * 没有交集时返回 null，所有窗口不可用。
     */
    private static Long sharedEndTimestamp(Map<Long, BigDecimal> coin,
                                           Map<Long, BigDecimal> btc,
                                           Map<Long, BigDecimal> eth) {
        if (coin.isEmpty() || btc.isEmpty() || eth.isEmpty()) {
            return null;
        }
        Set<Long> shared = new HashSet<>(coin.keySet());
        shared.retainAll(btc.keySet());
        shared.retainAll(eth.keySet());
        return shared.stream().max(Long::compareTo).orElse(null);
    }

    private static boolean windowFreshEnough(Map<Long, BigDecimal> coin,
                                             Map<Long, BigDecimal> btc,
                                             Map<Long, BigDecimal> eth,
                                             long endTs,
                                             Instant asOf) {
        Instant now = asOf == null ? Instant.now() : asOf;
        long lagMillis = now.toEpochMilli() - endTs;
        if (lagMillis < -MAX_FUTURE_SKEW.toMillis()) {
            return false;
        }
        if (lagMillis > MAX_SHARED_END_LAG.toMillis()) {
            return false;
        }
        long maxSkew = MAX_SERIES_SKEW.toMillis();
        return seriesSkewOk(coin, endTs, maxSkew)
                && seriesSkewOk(btc, endTs, maxSkew)
                && seriesSkewOk(eth, endTs, maxSkew);
    }

    private static boolean seriesSkewOk(Map<Long, BigDecimal> prices, long endTs, long maxSkewMillis) {
        long seriesMax = prices.keySet().stream().mapToLong(Long::longValue).max().orElse(endTs);
        return seriesMax - endTs <= maxSkewMillis;
    }

    private static BigDecimal changeAt(Map<Long, BigDecimal> prices, Long endTs, int hours) {
        if (endTs == null || prices.isEmpty()) {
            return null;
        }
        BigDecimal latest = prices.get(endTs);
        if (latest == null || latest.signum() <= 0) {
            return null;
        }
        long baselineTs = endTs - hours * HOUR_MILLIS;
        BigDecimal baseline = prices.get(baselineTs);
        if (baseline == null || baseline.signum() <= 0) {
            return null;
        }
        return latest.divide(baseline, SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
    }

    private static BigDecimal relative(BigDecimal coin, BigDecimal btc, BigDecimal eth) {
        if (coin == null || btc == null || eth == null) {
            return null;
        }
        return coin.subtract(btc.max(eth));
    }
}

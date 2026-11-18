package com.example.quant.crypto.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 相对强弱时间窗：24h 收益需要至少 25 个连续整点价格，且三方必须对齐到同一结束时点。
 */
class RelativeStrengthWindowTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    /** 与 BASE 起算的 25 根小时线终点对齐，避免被“相对现在过旧”误杀。 */
    private static final Instant AS_OF_BASE = BASE.plusSeconds(24 * 3600L + 1800L);

    @Test
    @DisplayName("连续 24 个小时价格点不足以计算 24h 收益")
    void twentyFourHourlyPricesAreNotEnoughForTwentyFourHourReturn() {
        List<ContractCandle> series = hourly(24, hour -> BigDecimal.valueOf(100 + hour));

        RelativeStrengthSnapshot snapshot = RelativeStrengthProvider.calculate(
                "X-USDT-SWAP", series, series, series, DataQuality.FRESH, AS_OF_BASE);

        assertThat(snapshot.coin24h()).isNull();
        assertThat(snapshot.rs24h()).isNull();
        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
    }

    @Test
    @DisplayName("至少 25 个有效小时价格点可以计算 24h 收益")
    void twentyFiveHourlyPricesAreEnoughForTwentyFourHourReturn() {
        List<ContractCandle> series = hourly(25, hour -> BigDecimal.valueOf(100 + hour));

        RelativeStrengthSnapshot snapshot = RelativeStrengthProvider.calculate(
                "X-USDT-SWAP", series, series, series, DataQuality.FRESH, AS_OF_BASE);

        assertThat(snapshot.coin24h()).isEqualByComparingTo("24.000000");
        assertThat(snapshot.rs24h()).isEqualByComparingTo("0");
        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.FRESH);
        assertThat(snapshot.windowEnd()).isEqualTo(BASE.plusSeconds(24 * 3600L));
    }

    @Test
    @DisplayName("缺少精确基线整点时不得用更早价格冒充目标窗口")
    void missingExactBaselineHourIsUnavailableInsteadOfEarlierPrice() {
        List<ContractCandle> withGap = hourly(25, hour -> BigDecimal.valueOf(100 + hour));
        // 去掉 t-24 整点，只留更早的哨兵点：旧实现会退到哨兵价，算出假 24h。
        withGap.remove(0);
        withGap.add(0, candle(BASE.minusSeconds(3600), BigDecimal.valueOf(50)));

        RelativeStrengthSnapshot snapshot = RelativeStrengthProvider.calculate(
                "X-USDT-SWAP", withGap, withGap, withGap, DataQuality.FRESH, AS_OF_BASE);

        assertThat(snapshot.coin24h()).isNull();
        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
    }

    @Test
    @DisplayName("基准与候选结束时点错位时不得把不同窗口涨幅相减")
    void misalignedEndTimestampsDoNotMixWindows() {
        List<ContractCandle> coin = hourly(25, hour -> BigDecimal.valueOf(100 + hour));
        List<ContractCandle> btc = hourly(25, hour -> BigDecimal.valueOf(100 + hour));
        // ETH 整体晚一小时：旧实现各自取最新点会把不同窗口涨幅相减。
        List<ContractCandle> ethShifted = hourlyFrom(BASE.plusSeconds(3600), 25,
                hour -> BigDecimal.valueOf(200 + hour));

        RelativeStrengthSnapshot snapshot = RelativeStrengthProvider.calculate(
                "X-USDT-SWAP", coin, btc, ethShifted, DataQuality.FRESH, AS_OF_BASE);

        assertThat(ethShifted.get(ethShifted.size() - 1).timestamp())
                .isNotEqualTo(coin.get(coin.size() - 1).timestamp());
        // 共同终点下 ETH 缺 t-24 整点 → 24h 与整体质量必须 unavailable，不得产出错位 RS。
        assertThat(snapshot.eth24h()).isNull();
        assertThat(snapshot.rs24h()).isNull();
        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
    }

    @Test
    @DisplayName("候选比基准多一根未对齐 K 线时，统一使用共同结束时点")
    void usesSharedEndTimestampAcrossCoinAndBenchmarks() {
        List<ContractCandle> coin = hourly(26, hour -> BigDecimal.valueOf(100 + hour));
        List<ContractCandle> bench = hourly(25, hour -> BigDecimal.valueOf(100));

        RelativeStrengthSnapshot snapshot = RelativeStrengthProvider.calculate(
                "X-USDT-SWAP", coin, bench, bench, DataQuality.FRESH, AS_OF_BASE);

        // 共同终点是 hour 24（bench 最后一根）；coin 在该点 close=124，24h 前=100 → +24%
        assertThat(snapshot.coin24h()).isEqualByComparingTo("24.000000");
        assertThat(snapshot.btc24h()).isEqualByComparingTo("0");
        assertThat(snapshot.rs24h()).isEqualByComparingTo("24.000000");
        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.FRESH);
        assertThat(snapshot.windowEnd()).isEqualTo(BASE.plusSeconds(24 * 3600L));
    }

    @Test
    @DisplayName("数量足够但候选相对基准落后 12 小时：不得标 FRESH，不得继续用旧窗口评分")
    void enoughCandlesButTwelveHoursStaleIsUnavailable() {
        Instant asOf = Instant.parse("2026-09-22T12:00:00Z");
        // BTC/ETH 覆盖到 asOf 前一小时；候选同样 48 根，但整体落后 12 小时。
        List<ContractCandle> fresh = hourlyFrom(asOf.minusSeconds(48 * 3600L), 48,
                hour -> BigDecimal.valueOf(100 + hour));
        List<ContractCandle> staleCoin = hourlyFrom(asOf.minusSeconds(60 * 3600L), 48,
                hour -> BigDecimal.valueOf(100 + hour));

        RelativeStrengthSnapshot snapshot = RelativeStrengthProvider.calculate(
                "X-USDT-SWAP", staleCoin, fresh, fresh, DataQuality.FRESH, asOf);

        assertThat(snapshot.windowEnd()).isNotNull();
        assertThat(snapshot.windowEnd())
                .as("共同终点会被候选拖到约 12 小时前")
                .isBefore(asOf.minusSeconds(11 * 3600L));
        assertThat(snapshot.rs24h()).isNull();
        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
    }

    @Test
    @DisplayName("整点切换时基准比候选多一根已收盘 K 线：允许短暂错位并记录评分终点")
    void oneHourBoundarySkewRemainsFreshAndRecordsWindowEnd() {
        Instant asOf = Instant.parse("2026-09-22T12:15:00Z");
        // 候选最新收盘 10:00；BTC/ETH 已有 11:00（整点切换的合理延迟）。
        Instant coinStart = Instant.parse("2026-09-20T11:00:00Z");
        Instant benchStart = Instant.parse("2026-09-20T12:00:00Z");
        List<ContractCandle> coin = hourlyFrom(coinStart, 48, hour -> BigDecimal.valueOf(100));
        List<ContractCandle> bench = hourlyFrom(benchStart, 48, hour -> BigDecimal.valueOf(100));

        RelativeStrengthSnapshot snapshot = RelativeStrengthProvider.calculate(
                "X-USDT-SWAP", coin, bench, bench, DataQuality.FRESH, asOf);

        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.FRESH);
        assertThat(snapshot.rs24h()).isEqualByComparingTo("0");
        assertThat(snapshot.windowEnd()).isEqualTo(Instant.parse("2026-09-22T10:00:00Z"));
    }

    @Test
    @DisplayName("共同终点相对扫描时间超过允许滞后时不可用")
    void sharedEndOlderThanAllowedLagIsUnavailable() {
        Instant asOf = Instant.parse("2026-09-22T12:00:00Z");
        // 三方对齐，但最新收盘停在 8 小时前。
        List<ContractCandle> series = hourlyFrom(asOf.minusSeconds(32 * 3600L), 25,
                hour -> BigDecimal.valueOf(100 + hour));

        RelativeStrengthSnapshot snapshot = RelativeStrengthProvider.calculate(
                "X-USDT-SWAP", series, series, series, DataQuality.FRESH, asOf);

        assertThat(snapshot.windowEnd()).isEqualTo(asOf.minusSeconds(8 * 3600L));
        assertThat(snapshot.rs24h()).isNull();
        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
    }

    private static List<ContractCandle> hourly(int count, IntFunction<BigDecimal> closeAtHour) {
        return hourlyFrom(BASE, count, closeAtHour);
    }

    private static List<ContractCandle> hourlyFrom(Instant start, int count,
                                                   IntFunction<BigDecimal> closeAtHour) {
        List<ContractCandle> candles = new ArrayList<>(count);
        for (int hour = 0; hour < count; hour++) {
            candles.add(candle(start.plusSeconds(hour * 3600L), closeAtHour.apply(hour)));
        }
        return candles;
    }

    private static ContractCandle candle(Instant ts, BigDecimal close) {
        return new ContractCandle(ts, close, close, close, close, BigDecimal.ONE);
    }
}

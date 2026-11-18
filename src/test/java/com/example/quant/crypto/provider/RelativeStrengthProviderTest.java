package com.example.quant.crypto.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class RelativeStrengthProviderTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    /** 与系列终点对齐，避免相对 asOf 过旧被新鲜度门控误杀。 */
    private static final Instant AS_OF = BASE.plusSeconds(24 * 3600L + 1800L);

    @Test
    void usesBtcWhenBtcIsTheStrongerBenchmark() {
        RelativeStrengthSnapshot snapshot = calculate(
                hourly(25, hour -> hour == 0 ? bd(100) : (hour < 24 ? bd(100) : bd(130))),
                hourly(25, hour -> hour == 0 ? bd(100) : (hour < 24 ? bd(100) : bd(150))),
                hourly(25, hour -> hour == 0 ? bd(100) : (hour < 24 ? bd(100) : bd(115))));

        assertThat(snapshot.coin24h()).isEqualByComparingTo("30.000000");
        assertThat(snapshot.btc24h()).isEqualByComparingTo("50.000000");
        assertThat(snapshot.eth24h()).isEqualByComparingTo("15.000000");
        assertThat(snapshot.rs24h()).isEqualByComparingTo("-20.000000");
        assertThat(snapshot.rs1h()).isEqualByComparingTo("-20.000000");
        assertThat(snapshot.rs4h()).isEqualByComparingTo("-20.000000");
    }

    @Test
    void usesEthWhenEthIsTheStrongerBenchmark() {
        RelativeStrengthSnapshot snapshot = calculate(
                hourly(25, hour -> hour == 0 ? bd(100) : (hour < 24 ? bd(100) : bd(130))),
                hourly(25, hour -> hour == 0 ? bd(100) : (hour < 24 ? bd(100) : bd(115))),
                hourly(25, hour -> hour == 0 ? bd(100) : (hour < 24 ? bd(100) : bd(150))));

        assertThat(snapshot.rs24h()).isEqualByComparingTo("-20.000000");
        assertThat(snapshot.rs1h()).isEqualByComparingTo("-20.000000");
        assertThat(snapshot.rs4h()).isEqualByComparingTo("-20.000000");
    }

    @Test
    void equalBenchmarksAndCoinAboveBelowOrEqualAreCalculated() {
        RelativeStrengthSnapshot above = calculate(
                hourly(25, hour -> hour < 24 ? bd(100) : bd(140)),
                hourly(25, hour -> hour < 24 ? bd(100) : bd(120)),
                hourly(25, hour -> hour < 24 ? bd(100) : bd(120)));
        RelativeStrengthSnapshot below = calculate(
                hourly(25, hour -> hour < 24 ? bd(100) : bd(120)),
                hourly(25, hour -> hour < 24 ? bd(100) : bd(140)),
                hourly(25, hour -> hour < 24 ? bd(100) : bd(140)));
        RelativeStrengthSnapshot equal = calculate(
                hourly(25, hour -> hour < 24 ? bd(100) : bd(140)),
                hourly(25, hour -> hour < 24 ? bd(100) : bd(140)),
                hourly(25, hour -> hour < 24 ? bd(100) : bd(140)));

        assertThat(above.rs24h()).isPositive();
        assertThat(below.rs24h()).isNegative();
        assertThat(equal.rs24h()).isEqualByComparingTo("0");
    }

    @Test
    void timestampsAreSortedAndDuplicateTimestampsDoNotChangeTheLatestWindow() {
        List<ContractCandle> coin = hourly(25, hour -> hour < 24 ? bd(100) : bd(130));
        List<ContractCandle> reversed = new ArrayList<>(coin);
        Collections.reverse(reversed);
        reversed.add(reversed.get(2));

        RelativeStrengthSnapshot snapshot = calculate(reversed,
                hourly(25, hour -> hour < 24 ? bd(100) : bd(120)),
                hourly(25, hour -> hour < 24 ? bd(100) : bd(120)));

        assertThat(snapshot.coin24h()).isEqualByComparingTo("30.000000");
    }

    @Test
    void insufficientDataAndIllegalPricesRemainMissing() {
        RelativeStrengthSnapshot shortCoin = calculate(hourly(2, hour -> bd(100 + hour)),
                hourly(25, hour -> bd(100)), hourly(25, hour -> bd(100)));
        RelativeStrengthSnapshot illegal = calculate(
                hourly(25, hour -> hour == 0 ? bd(0) : bd(100 + hour)),
                hourly(25, hour -> bd(100)), hourly(25, hour -> bd(100)));

        assertThat(shortCoin.coin4h()).isNull();
        assertThat(shortCoin.rs24h()).isNull();
        assertThat(illegal.coin24h()).isNull();
        assertThat(illegal.rs24h()).isNull();
        assertThat(shortCoin.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
    }

    @Test
    void benchmarkCacheIsSharedAcrossCoinsAndHonorsFreshAndStaleWindows() {
        AtomicInteger loads = new AtomicInteger();
        ProviderCache<String, List<ContractCandle>> cache = new ProviderCache<>(
                "relative-strength-test", Duration.ofSeconds(60), Duration.ofSeconds(600));
        cache.get("BTC", key -> {
            loads.incrementAndGet();
            return ProviderResult.fresh(hourly(25, hour -> bd(100)), "test");
        });
        cache.get("BTC", key -> {
            loads.incrementAndGet();
            return ProviderResult.fresh(hourly(25, hour -> bd(100)), "test");
        });

        assertThat(loads).hasValue(1);
        assertThat(cache.get("BTC", key -> ProviderResult.unavailable("not called")).dataQuality())
                .isEqualTo(DataQuality.FRESH);
    }

    @Test
    void missingBenchmarkNeverBecomesZero() {
        RelativeStrengthSnapshot snapshot = calculate(
                hourly(25, hour -> hour < 24 ? bd(100) : bd(140)),
                List.of(),
                hourly(25, hour -> hour < 24 ? bd(100) : bd(120)));

        assertThat(snapshot.btc24h()).isNull();
        assertThat(snapshot.rs24h()).isNull();
        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
    }

    @Test
    void staleBenchmarkQualityIsPreservedByCalculationInput() {
        RelativeStrengthSnapshot snapshot = RelativeStrengthProvider.calculate("X-USDT-SWAP",
                hourly(25, hour -> hour < 24 ? bd(100) : bd(140)),
                hourly(25, hour -> hour < 24 ? bd(100) : bd(120)),
                hourly(25, hour -> hour < 24 ? bd(100) : bd(120)),
                DataQuality.STALE,
                AS_OF);

        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.STALE);
        assertThat(snapshot.rs24h()).isNotNull();
    }

    @Test
    void staleCacheEntryIsServedWithoutReload() {
        AtomicInteger loads = new AtomicInteger();
        ProviderCache<String, List<ContractCandle>> cache = new ProviderCache<>(
                "relative-strength-stale", Duration.ZERO, Duration.ofMinutes(10));
        cache.get("ETH", key -> {
            loads.incrementAndGet();
            return ProviderResult.fresh(hourly(25, hour -> bd(100)), "test");
        });

        assertThat(cache.get("ETH", key -> {
            loads.incrementAndGet();
            return ProviderResult.unavailable("must not reload");
        }).dataQuality()).isEqualTo(DataQuality.STALE);
        assertThat(loads).hasValue(1);
    }

    private static RelativeStrengthSnapshot calculate(List<ContractCandle> coin,
                                                       List<ContractCandle> btc,
                                                       List<ContractCandle> eth) {
        return RelativeStrengthProvider.calculate("X-USDT-SWAP", coin, btc, eth,
                DataQuality.FRESH, AS_OF);
    }

    private static List<ContractCandle> hourly(int count, IntFunction<BigDecimal> closeAtHour) {
        List<ContractCandle> result = new ArrayList<>(count);
        for (int hour = 0; hour < count; hour++) {
            BigDecimal close = closeAtHour.apply(hour);
            result.add(new ContractCandle(BASE.plusSeconds(hour * 3_600L),
                    close, close, close, close, BigDecimal.ONE));
        }
        return result;
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }
}

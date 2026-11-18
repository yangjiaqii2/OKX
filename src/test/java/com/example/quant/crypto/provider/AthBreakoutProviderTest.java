package com.example.quant.crypto.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.provider.dto.AthBreakoutSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AthBreakoutProviderTest {
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final BigDecimal AT = new BigDecimal("-2");
    private static final BigDecimal NEAR = new BigDecimal("-10");
    private static final BigDecimal MID = new BigDecimal("-40");

    @Test
    void athUsesHistoricalHighAndCurrentTickerPrice() {
        AthBreakoutSnapshot snapshot = calculate(new BigDecimal("95"), candles(
                candle(10, 12, 9, 11, 0), candle(90, 100, 80, 95, 1)));

        assertThat(snapshot.athPrice()).isEqualByComparingTo("100");
        assertThat(snapshot.currentPrice()).isEqualByComparingTo("95");
        assertThat(snapshot.distanceToAthPct()).isEqualByComparingTo("-5");
    }

    @Test
    void unorderedAndDuplicateCandlesAreHandled() {
        List<ContractCandle> candles = new ArrayList<>(candles(
                candle(90, 100, 80, 95, 2), candle(10, 50, 9, 20, 1), candle(1, 50, 1, 2, 1)));
        Collections.reverse(candles);

        AthBreakoutSnapshot snapshot = calculate(new BigDecimal("100"), candles);

        assertThat(snapshot.athPrice()).isEqualByComparingTo("100");
        assertThat(snapshot.distanceToAthPct()).isZero();
    }

    @Test
    void invalidHighsAreIgnoredAndNoValidHistoryIsUnavailable() {
        AthBreakoutSnapshot valid = calculate(new BigDecimal("10"), List.of(
                candle(1, 0, 1, 1, 0), candle(1, -4, 1, 1, 1), candle(1, 20, 1, 10, 2)));
        AthBreakoutSnapshot unavailable = calculate(new BigDecimal("10"), List.of(
                candle(1, 0, 1, 1, 0), candle(1, -4, 1, 1, 1)));

        assertThat(valid.athPrice()).isEqualByComparingTo("20");
        assertThat(unavailable.athPrice()).isNull();
        assertThat(unavailable.distanceToAthPct()).isNull();
        assertThat(unavailable.breakoutLevel()).isNull();
        assertThat(unavailable.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
    }

    @Test
    void breakoutBoundariesAreInclusiveOnLowerRanges() {
        assertThat(calculate(98, 100).breakoutLevel()).isEqualTo("AT_ATH");
        assertThat(calculate(90, 100).breakoutLevel()).isEqualTo("NEAR_ATH");
        assertThat(calculate(60, 100).breakoutLevel()).isEqualTo("MID_RANGE");
        assertThat(calculate(101, 100).breakoutLevel()).isEqualTo("AT_ATH");
        assertThat(calculate(95, 100).breakoutLevel()).isEqualTo("NEAR_ATH");
        assertThat(calculate(75, 100).breakoutLevel()).isEqualTo("MID_RANGE");
        assertThat(calculate(59, 100).breakoutLevel()).isEqualTo("DEEP_BELOW");
    }

    @Test
    void partialHistoryIsExplicitlyMarked() {
        AthBreakoutSnapshot snapshot = AthBreakoutProvider.calculate("X-USDT-SWAP",
                new BigDecimal("95"), candles(candle(90, 100, 80, 95, 0)),
                DataQuality.PARTIAL, 50, true, AT, NEAR, MID);

        assertThat(snapshot.athPrice()).isEqualByComparingTo("100");
        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.PARTIAL);
        assertThat(snapshot.pagesFetched()).isEqualTo(50);
        assertThat(snapshot.lookbackTruncated()).isTrue();
    }

    @Test
    void completeHistoryKeepsFreshQualityAndPageMetadata() {
        AthBreakoutSnapshot snapshot = AthBreakoutProvider.calculate("X-USDT-SWAP",
                new BigDecimal("100"), candles(candle(90, 100, 80, 95, 0)),
                DataQuality.FRESH, 2, false, AT, NEAR, MID);

        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.FRESH);
        assertThat(snapshot.pagesFetched()).isEqualTo(2);
        assertThat(snapshot.lookbackTruncated()).isFalse();
    }

    @Test
    void cacheUsesFreshStaleAndReloadWindowsPerInstrument() {
        var cache = new ProviderCache<String, AthBreakoutSnapshot>("ath", java.time.Duration.ofSeconds(60),
                java.time.Duration.ofSeconds(600));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        cache.get("BTC-USDT-SWAP", key -> { calls.incrementAndGet(); return ProviderResult.fresh(
                calculate(95, 100), "test"); });
        cache.get("BTC-USDT-SWAP", key -> { calls.incrementAndGet(); return ProviderResult.fresh(
                calculate(95, 100), "test"); });
        cache.get("ETH-USDT-SWAP", key -> { calls.incrementAndGet(); return ProviderResult.fresh(
                calculate(95, 100), "test"); });

        assertThat(calls).hasValue(2);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void currentPriceAboveAthIsStillAtAth() {
        assertThat(calculate(110, 100).breakoutLevel()).isEqualTo("AT_ATH");
        assertThat(calculate(110, 100).distanceToAthPct()).isEqualByComparingTo("10");
    }

    @Test
    void exactlyMinusTwoPercentIsAtAth() {
        AthBreakoutSnapshot snapshot = calculate(new BigDecimal("98"),
                List.of(candle(1, 100, 1, 98, 0)));
        assertThat(snapshot.breakoutLevel()).isEqualTo("AT_ATH");
    }

    @Test
    void justBelowMinusTwoPercentIsNearAth() {
        AthBreakoutSnapshot snapshot = calculate(new BigDecimal("97.99"),
                List.of(candle(1, 100, 1, 97, 0)));
        assertThat(snapshot.breakoutLevel()).isEqualTo("NEAR_ATH");
    }

    @Test
    void exactlyMinusTenPercentIsNearAth() {
        assertThat(calculate(90, 100).breakoutLevel()).isEqualTo("NEAR_ATH");
    }

    @Test
    void justBelowMinusTenPercentIsMidRange() {
        assertThat(calculate(89, 100).breakoutLevel()).isEqualTo("MID_RANGE");
    }

    @Test
    void exactlyMinusFortyPercentIsMidRange() {
        assertThat(calculate(60, 100).breakoutLevel()).isEqualTo("MID_RANGE");
    }

    @Test
    void justBelowMinusFortyPercentIsDeepBelow() {
        assertThat(calculate(59, 100).breakoutLevel()).isEqualTo("DEEP_BELOW");
    }

    @Test
    void nullCurrentPriceDoesNotCreateSyntheticDistance() {
        AthBreakoutSnapshot snapshot = AthBreakoutProvider.calculate("X-USDT-SWAP", null,
                candles(candle(1, 100, 1, 90, 0)), DataQuality.FRESH, 1, false, AT, NEAR, MID);
        assertThat(snapshot.currentPrice()).isNull();
        assertThat(snapshot.distanceToAthPct()).isNull();
        assertThat(snapshot.breakoutLevel()).isNull();
        assertThat(snapshot.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
    }

    private static AthBreakoutSnapshot calculate(BigDecimal current, List<ContractCandle> candles) {
        return AthBreakoutProvider.calculate("X-USDT-SWAP", current, candles, DataQuality.FRESH,
                1, false, AT, NEAR, MID);
    }

    private static AthBreakoutSnapshot calculate(int current, int ath) {
        return calculate(BigDecimal.valueOf(current), List.of(candle(1, ath, 1, current, 0)));
    }

    private static List<ContractCandle> candles(ContractCandle... candles) {
        return List.of(candles);
    }

    private static ContractCandle candle(int open, int high, int low, int close, int hour) {
        return new ContractCandle(BASE.plusSeconds(hour * 86_400L), BigDecimal.valueOf(open),
                BigDecimal.valueOf(high), BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.ONE);
    }
}

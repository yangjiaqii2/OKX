package com.example.quant.crypto.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderCacheTest {

    private static MarketCapSnapshot mcap(String base) {
        return new MarketCapSnapshot(base, new BigDecimal("100000000000"),
                null, null, null, null, Instant.now(), "test");
    }

    @Test
    void freshEntryReturnsFreshQuality() {
        ProviderCache<String, MarketCapSnapshot> cache = new ProviderCache<>(
                "test", Duration.ofSeconds(60), Duration.ofSeconds(600));
        var r = cache.get("BTC", k -> ProviderResult.fresh(mcap(k), "test"));
        assertThat(r.dataQuality()).isEqualTo(DataQuality.FRESH);
        assertThat(r.data().baseSymbol()).isEqualTo("BTC");
    }

    @Test
    void secondHitWithinFreshTtlDoesNotCallLoaderAgain() {
        AtomicInteger calls = new AtomicInteger();
        ProviderCache<String, MarketCapSnapshot> cache = new ProviderCache<>(
                "test", Duration.ofSeconds(60), Duration.ofSeconds(600));
        cache.get("BTC", k -> { calls.incrementAndGet(); return ProviderResult.fresh(mcap(k), "test"); });
        cache.get("BTC", k -> { calls.incrementAndGet(); return ProviderResult.fresh(mcap(k), "test"); });
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void peekReturnsFreshEntryWithoutCallingLoader() {
        ProviderCache<String, MarketCapSnapshot> cache = new ProviderCache<>(
                "test", Duration.ofSeconds(60), Duration.ofSeconds(600));
        cache.get("BTC", k -> ProviderResult.fresh(mcap(k), "test"));

        var fresh = cache.peek("BTC");

        assertThat(fresh).isPresent();
        assertThat(fresh.orElseThrow().dataQuality()).isEqualTo(DataQuality.FRESH);
        assertThat(cache.peek("UNKNOWN")).isEmpty();
    }

    @Test
    void peekOmitsStaleEntrySoBatchCallerCanRefreshIt() {
        ProviderCache<String, MarketCapSnapshot> cache = new ProviderCache<>(
                "test", Duration.ofSeconds(60), Duration.ofSeconds(600));
        cache.seed("BTC", ProviderResult.stale(
                mcap("BTC"), "test", Instant.now().minusSeconds(61)));

        assertThat(cache.peek("BTC")).isEmpty();
    }

    @Test
    void recentStaleDataRemainsStaleAndDoesNotSatisfyFreshPeek() {
        ProviderCache<String, MarketCapSnapshot> cache = new ProviderCache<>(
                "test", Duration.ofMinutes(10), Duration.ofMinutes(30));
        var stale = ProviderResult.stale(mcap("BTC"), "disk", Instant.now().minusSeconds(90));
        cache.seed("BTC", stale);

        assertThat(cache.peek("BTC")).isEmpty();
        assertThat(cache.get("BTC", key -> { throw new AssertionError("existing fallback expected"); }))
                .isEqualTo(stale);
    }

    @Test
    void staleEntryStillServedWithinStaleTtl() {
        ProviderCache<String, MarketCapSnapshot> cache = new ProviderCache<>(
                "test", Duration.ZERO, Duration.ofMinutes(10));
        cache.get("ETH", k -> ProviderResult.fresh(mcap(k), "test"));
        // 第二次调用 freshTtl=0 已经过期但 staleTtl 仍允许
        var r2 = cache.get("ETH", k -> { throw new AssertionError("loader should not be called"); });
        assertThat(r2.dataQuality()).isEqualTo(DataQuality.STALE);
    }

    @Test
    void loaderExceptionWrappedAsUnavailable() {
        ProviderCache<String, MarketCapSnapshot> cache = new ProviderCache<>(
                "test", Duration.ofSeconds(60), Duration.ofSeconds(600));
        var r = cache.get("X", k -> { throw new IllegalStateException("api down"); });
        assertThat(r.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(r.errorMessage()).contains("api down");
    }

    @Test
    void invalidateForcesReload() {
        AtomicInteger calls = new AtomicInteger();
        ProviderCache<String, MarketCapSnapshot> cache = new ProviderCache<>(
                "test", Duration.ofMinutes(5), Duration.ofMinutes(30));
        cache.get("SOL", k -> { calls.incrementAndGet(); return ProviderResult.fresh(mcap(k), "test"); });
        cache.invalidate("SOL");
        cache.get("SOL", k -> { calls.incrementAndGet(); return ProviderResult.fresh(mcap(k), "test"); });
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void unavailableResultNotCached() {
        AtomicInteger calls = new AtomicInteger();
        ProviderCache<String, MarketCapSnapshot> cache = new ProviderCache<>(
                "test", Duration.ofMinutes(5), Duration.ofMinutes(30));
        cache.get("Z", k -> { calls.incrementAndGet(); return ProviderResult.unavailable(""); });
        cache.get("Z", k -> { calls.incrementAndGet(); return ProviderResult.unavailable(""); });
        // unavailable 不会被缓存（避免风暴期被钉住），loader 跑两次
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void explicitNotFoundSeedIsNegativeCachedWithinFreshTtl() {
        ProviderCache<String, MarketCapSnapshot> cache = new ProviderCache<>(
                "test", Duration.ofMinutes(10), Duration.ofMinutes(10));
        ProviderResult<MarketCapSnapshot> notFound = new ProviderResult<>(
                null, DataQuality.UNAVAILABLE, Instant.now(), "test",
                "NOT_FOUND", "no market-cap data for symbol");

        cache.seed("UNKNOWN", notFound);

        assertThat(cache.peek("UNKNOWN"))
                .isPresent()
                .get()
                .extracting(ProviderResult::errorCode)
                .isEqualTo("NOT_FOUND");
    }
}

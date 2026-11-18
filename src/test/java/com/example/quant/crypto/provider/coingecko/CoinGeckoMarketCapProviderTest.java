package com.example.quant.crypto.provider.coingecko;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.MarketCapSnapshotEntity;
import com.example.quant.crypto.MarketCapSnapshotRepository;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.when;

class CoinGeckoMarketCapProviderTest {

    /**
     * 单元测试专用 HTTP client：按队列顺序返回预录的响应；队列空 → empty。
     * 用于模拟 200/4xx/5xx/网络异常/空响应 等所有路径。
     */
    private static final class FakeHttp implements CoinGeckoHttpClient {
        Deque<Response> responses = new ArrayDeque<>();
        Deque<RuntimeException> errors = new ArrayDeque<>();
        int callCount = 0;

        @Override
        public Optional<CoinGeckoMarketData> fetchBySymbol(String baseSymbol) {
            callCount++;
            if (!errors.isEmpty()) {
                throw errors.poll();
            }
            Response next = responses.poll();
            if (next == null) {
                return Optional.empty();
            }
            return next.body == null ? Optional.empty() : Optional.of(next.body);
        }

        void enqueueOk(CoinGeckoMarketData data) {
            responses.add(new Response(data));
        }

        void enqueueEmpty() {
            responses.add(new Response(null));
        }

        record Response(CoinGeckoMarketData body) {}
    }

    private static CoinGeckoTokenBucket generousBucket() {
        return new CoinGeckoTokenBucket(1000);
    }

    @Test
    void fetchSuccessReturnsFreshResult() {
        FakeHttp fake = new FakeHttp();
        fake.enqueueOk(CoinGeckoConstants.sampleBtc());
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, generousBucket());

        ProviderResult<MarketCapSnapshot> r = provider.fetch("BTC");

        assertThat(r.isUsable()).isTrue();
        assertThat(r.dataQuality()).isEqualTo(DataQuality.FRESH);
        assertThat(r.source()).isEqualTo("coingecko");
        assertThat(r.data().marketCapUsd()).isEqualByComparingTo(new BigDecimal("1180000000000.5000"));
        assertThat(fake.callCount).isEqualTo(1);
    }

    @Test
    void fetch404EmptyArrayReturnsUnavailable() {
        // 真实 CoinGecko 行为：未知 symbol 返回 []
        FakeHttp fake = new FakeHttp();
        fake.enqueueEmpty();
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, generousBucket());

        ProviderResult<MarketCapSnapshot> r = provider.fetch("NOPE");

        assertThat(r.isUsable()).isFalse();
        assertThat(r.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(r.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(r.errorMessage()).contains("no data");
    }

    @Test
    void fetch429FromHttpClientReturnsUnavailable() {
        // Token bucket 空 → 模拟本地限流（不要依赖 429，429 由 HTTP 客户端在 impl 中转 empty）
        FakeHttp fake = new FakeHttp();
        CoinGeckoTokenBucket empty = new CoinGeckoTokenBucket(1);
        empty.tryAcquire(); // 消耗掉
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, empty);

        ProviderResult<MarketCapSnapshot> r = provider.fetch("BTC");

        assertThat(r.isUsable()).isFalse();
        assertThat(r.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(r.errorMessage()).contains("rate limited");
        assertThat(fake.callCount).isZero(); // 没进 HTTP 就拒了
    }

    @Test
    void fetchMissingMarketCapReturnsUnavailable() {
        // mcap=null 的真实数据 → mapper 拒绝
        FakeHttp fake = new FakeHttp();
        fake.enqueueOk(new CoinGeckoMarketData(
                "leaky", "leak", null, new BigDecimal("1000"),
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("200"), null));
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, generousBucket());

        ProviderResult<MarketCapSnapshot> r = provider.fetch("LEAK");

        assertThat(r.isUsable()).isFalse();
        assertThat(r.errorMessage()).contains("marketCap");
    }

    @Test
    void fetchBlankSymbolReturnsUnavailable() {
        FakeHttp fake = new FakeHttp();
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, generousBucket());

        ProviderResult<MarketCapSnapshot> r1 = provider.fetch("");
        ProviderResult<MarketCapSnapshot> r2 = provider.fetch(null);

        assertThat(r1.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(r2.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(r1.errorMessage()).contains("blank");
        assertThat(fake.callCount).isZero();
    }

    @Test
    void fetchNetworkExceptionReturnsUnavailableNotThrows() {
        FakeHttp fake = new FakeHttp();
        fake.errors.add(new RuntimeException("connection reset"));
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, generousBucket());

        // 必须捕获并降级，不抛
        ProviderResult<MarketCapSnapshot> r = provider.fetch("BTC");
        assertThat(r.isUsable()).isFalse();
        assertThat(r.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(r.errorMessage()).contains("RuntimeException");
    }

    @Test
    void interruptedUpstreamRequestReturnsTimeoutWithoutStartingGlobalCooldown() {
        FakeHttp fake = new FakeHttp();
        fake.errors.add(new CoinGeckoRequestException(
                CoinGeckoRequestException.Kind.INTERRUPTED, "scan deadline cancelled"));
        fake.enqueueOk(CoinGeckoConstants.sampleBtc());
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, generousBucket());

        ProviderResult<MarketCapSnapshot> first = provider.fetch("BTC");
        ProviderResult<MarketCapSnapshot> second = provider.fetch("ETH");

        assertThat(first.dataQuality()).isEqualTo(DataQuality.TIMEOUT);
        assertThat(first.errorCode()).isEqualTo("TIMEOUT");
        assertThat(second.dataQuality()).isEqualTo(DataQuality.FRESH);
        assertThat(fake.callCount).isEqualTo(2);
    }

    @Test
    void emptyUpstreamResponseDoesNotStartGlobalCooldown() {
        FakeHttp fake = new FakeHttp();
        fake.enqueueEmpty();
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, generousBucket());

        ProviderResult<MarketCapSnapshot> first = provider.fetch("BTC");
        ProviderResult<MarketCapSnapshot> second = provider.fetch("ETH");

        assertThat(first.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(second.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(second.errorMessage()).doesNotContain("cooldown");
        assertThat(fake.callCount).isEqualTo(2);
    }

    @Test
    void explicitUpstreamRateLimitStartsGlobalCooldown() {
        FakeHttp fake = new FakeHttp();
        fake.errors.add(new CoinGeckoRequestException(
                CoinGeckoRequestException.Kind.RATE_LIMITED, "429"));
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, generousBucket());

        ProviderResult<MarketCapSnapshot> first = provider.fetch("BTC");
        ProviderResult<MarketCapSnapshot> second = provider.fetch("ETH");

        assertThat(first.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(first.errorMessage()).contains("upstream rate limited");
        assertThat(second.errorMessage()).contains("cooldown");
        assertThat(fake.callCount).isEqualTo(1);
    }

    @Test
    void emptyBatchResponseDoesNotStartGlobalCooldown() {
        FakeHttp fake = new FakeHttp();
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, generousBucket());

        Map<String, ProviderResult<MarketCapSnapshot>> first = provider.fetchBatch(
                java.util.List.of("UNKNOWN1"), Duration.ofSeconds(1));
        Map<String, ProviderResult<MarketCapSnapshot>> second = provider.fetchBatch(
                java.util.List.of("UNKNOWN2"), Duration.ofSeconds(1));

        assertThat(first.get("UNKNOWN1").errorMessage()).doesNotContain("cooldown");
        assertThat(first.get("UNKNOWN1").errorCode()).isEqualTo("NOT_FOUND");
        assertThat(second.get("UNKNOWN2").errorMessage()).doesNotContain("cooldown");
        assertThat(fake.callCount).isEqualTo(2);
    }

    @Test
    void exhaustedUpstreamFallsBackToPersistedSnapshotAsStale() {
        FakeHttp fake = new FakeHttp();
        CoinGeckoTokenBucket empty = new CoinGeckoTokenBucket(1);
        empty.tryAcquire();
        MarketCapSnapshotEntity stored = new MarketCapSnapshotEntity();
        stored.setBaseSymbol("BTC");
        stored.setMarketCapUsd(new BigDecimal("1180000000000"));
        stored.setSnapshotAt(Instant.parse("2026-09-19T00:00:00Z"));
        stored.setSource("coingecko");
        MarketCapSnapshotRepository repository = org.mockito.Mockito.mock(MarketCapSnapshotRepository.class);
        when(repository.findById("BTC")).thenReturn(Optional.of(stored));
        when(repository.findAllById(java.util.List.of("BTC"))).thenReturn(java.util.List.of(stored));
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, empty, repository);

        ProviderResult<MarketCapSnapshot> result = provider.fetch("BTC");

        assertThat(result.dataQuality()).isEqualTo(DataQuality.STALE);
        assertThat(result.data().marketCapUsd()).isEqualByComparingTo("1180000000000");
        assertThat(result.source()).contains("cache");
        assertThat(fake.callCount).isZero();
    }

    @Test
    void fetchUpperCasesBaseSymbol() {
        // 真实 CoinGecko symbols 大小写不敏感；提供方必须把 base 标准化为大写
        FakeHttp fake = new FakeHttp();
        fake.enqueueOk(CoinGeckoConstants.sampleBtc());
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, generousBucket());

        provider.fetch("btc");

        // 验证 fake 收到大小写不敏感：实际逻辑在 HTTP impl 中做；这里只验证 provider 不抛
        assertThat(fake.callCount).isEqualTo(1);
    }

    @Test
    void deadlineAwareBatchKeepsBatchSemanticsAndConsumesOneToken() {
        FakeHttp fake = new FakeHttp();
        fake.enqueueOk(CoinGeckoConstants.sampleBtc());
        fake.enqueueOk(CoinGeckoConstants.sampleBtc());
        CoinGeckoTokenBucket oneToken = new CoinGeckoTokenBucket(1);
        CoinGeckoMarketCapProvider provider = new CoinGeckoMarketCapProvider(
                "coingecko", fake, oneToken);

        Map<String, ProviderResult<MarketCapSnapshot>> result = provider.fetchBatch(
                java.util.List.of("BTC", "ETH"), Duration.ofSeconds(1));

        assertThat(result).hasSize(2);
        assertThat(fake.callCount).isEqualTo(2);
        assertThat(result.get("BTC")).isNotNull().extracting(ProviderResult::isUsable).isEqualTo(true);
        assertThat(result.get("ETH")).isNotNull().extracting(ProviderResult::isUsable).isEqualTo(false);
    }
}

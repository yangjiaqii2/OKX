package com.example.quant.crypto.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Phase B MarketCapFilter 独立单测。
 *
 * <p>覆盖：阈值边界、5 种 DataQuality 状态、字段缺失、构造器校验、name/reason 元数据。
 * 注意：此测试是 Filter 独立行为测试，{@link UniverseHardFilterTest} 是组合行为测试，两者互补。
 */
class MarketCapFilterTest {

    private static final BigDecimal MIN = new BigDecimal("20000000");
    private final MarketCapFilter filter = new MarketCapFilter(MIN);

    private static FilterContext ctx(ProviderResult<MarketCapSnapshot> r) {
        return new FilterContext("BTC-USDT-SWAP", "BTC", new BigDecimal("50000"),
                new BigDecimal("1000000000"), new BigDecimal("5"),
                new BigDecimal("2"), new BigDecimal("100000"), new BigDecimal("10000000"), r);
    }

    private static ProviderResult<MarketCapSnapshot> fresh(BigDecimal marketCapUsd) {
        return ProviderResult.fresh(
                new MarketCapSnapshot("BTC", marketCapUsd, null, null, null, null, Instant.now(), "test"),
                "test"
        );
    }

    @Test
    @DisplayName("name 必须稳定为 market_cap")
    void nameStable() {
        assertThat(filter.name()).isEqualTo("market_cap");
    }

    @Nested
    @DisplayName("构造器校验")
    class CtorValidation {
        @Test
        void rejectsNullThreshold() {
            assertThatThrownBy(() -> new MarketCapFilter((BigDecimal) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("minMarketCapUsd");
        }

        @Test
        void rejectsZeroThreshold() {
            assertThatThrownBy(() -> new MarketCapFilter(BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegativeThreshold() {
            assertThatThrownBy(() -> new MarketCapFilter(new BigDecimal("-1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("阈值边界")
    class ThresholdBoundary {
        @Test
        void exactlyAtThresholdPasses() {
            var r = filter.apply(ctx(fresh(MIN)));
            assertThat(r.passed()).isTrue();
            assertThat(r.reason()).isNull();
        }

        @Test
        void oneBelowThresholdRejects() {
            var r = filter.apply(ctx(fresh(new BigDecimal("19999999.99"))));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("MARKET_CAP_TOO_LOW");
            assertThat((BigDecimal) r.actualValue()).isEqualByComparingTo("19999999.99");
            assertThat((BigDecimal) r.threshold()).isEqualByComparingTo(MIN);
            assertThat(r.dataQuality()).isEqualTo(DataQuality.FRESH);
        }

        @Test
        void wellAboveThresholdPasses() {
            var r = filter.apply(ctx(fresh(new BigDecimal("999999999"))));
            assertThat(r.passed()).isTrue();
        }
    }

    @Nested
    @DisplayName("DataQuality 状态")
    class DataQualityCases {
        @Test
        void unavailableRejects() {
            var r = filter.apply(ctx(ProviderResult.unavailable("test")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("MARKET_CAP_UNAVAILABLE");
            assertThat(r.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        }

        @Test
        void timeoutRejects() {
            var r = filter.apply(ctx(ProviderResult.timeout("test")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("MARKET_CAP_TIMEOUT");
            assertThat(r.dataQuality()).isEqualTo(DataQuality.TIMEOUT);
        }

        @Test
        void staleRejects() {
            var r = filter.apply(ctx(ProviderResult.stale(
                    new MarketCapSnapshot("BTC", new BigDecimal("30000000"), null, null, null, null,
                            Instant.now().minusSeconds(700), "test"),
                    "test", Instant.now().minusSeconds(700))));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("MARKET_CAP_STALE");
            assertThat(r.dataQuality()).isEqualTo(DataQuality.STALE);
        }

        @Test
        void availableAlsoCountsAsUsable() {
            var r = filter.apply(ctx(ProviderResult.available(
                    new MarketCapSnapshot("BTC", MIN, null, null, null, null, Instant.now(), "test"),
                    "test")));
            assertThat(r.passed()).isTrue();
        }
    }

    @Nested
    @DisplayName("字段缺失")
    class MissingCases {
        @Test
        void nullResultRejects() {
            var r = filter.apply(ctx(null));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("MARKET_CAP_MISSING");
        }

        @Test
        void resultWithNullDataRejects() {
            var r = filter.apply(ctx(new ProviderResult<>(null, DataQuality.FRESH, Instant.now(), "test", null, null)));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("MARKET_CAP_MISSING");
        }

        @Test
        void resultWithDataButNullMarketCapRejects() {
            var r = filter.apply(ctx(ProviderResult.fresh(
                    new MarketCapSnapshot("BTC", null, null, null, null, null, Instant.now(), "test"),
                    "test")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("MARKET_CAP_MISSING");
        }
    }
}

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
 * Phase B CirculationFilter 独立单测。
 *
 * <p>覆盖：阈值边界、denominator 选择（maxSupply 优先 / 退化到 totalSupply）、
 * 5 种 DataQuality 状态、字段缺失、构造器校验。
 */
class CirculationFilterTest {

    private static final BigDecimal MIN_RATIO = new BigDecimal("0.50");
    private final CirculationFilter filter = new CirculationFilter(MIN_RATIO);

    private static FilterContext ctx(ProviderResult<MarketCapSnapshot> r) {
        return new FilterContext("BTC-USDT-SWAP", "BTC", new BigDecimal("50000"),
                new BigDecimal("1000000000"), new BigDecimal("5"),
                new BigDecimal("2"), new BigDecimal("100000"), new BigDecimal("10000000"), r);
    }

    private static ProviderResult<MarketCapSnapshot> snap(BigDecimal circulating, BigDecimal max, BigDecimal total) {
        return ProviderResult.fresh(
                new MarketCapSnapshot("BTC", new BigDecimal("100000000"), null, circulating, max, total,
                        Instant.now(), "test"),
                "test");
    }

    @Test
    void nameStable() {
        assertThat(filter.name()).isEqualTo("circulation");
    }

    @Nested
    class CtorValidation {
        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> new CirculationFilter((BigDecimal) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegative() {
            assertThatThrownBy(() -> new CirculationFilter(new BigDecimal("-0.1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsAboveOne() {
            assertThatThrownBy(() -> new CirculationFilter(new BigDecimal("1.01")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void allowsZero() {
            // min=0 是允许的：表示不卡 circulation
            new CirculationFilter(BigDecimal.ZERO);
        }

        @Test
        void allowsOne() {
            new CirculationFilter(BigDecimal.ONE);
        }
    }

    @Nested
    class DenominatorChoice {
        @Test
        void maxSupplyTakesPrecedence() {
            // circulating=6, max=10 → 0.6（>= 0.5）PASS
            // total=20（不该被用到）
            var r = filter.apply(ctx(snap(new BigDecimal("6"), new BigDecimal("10"), new BigDecimal("20"))));
            assertThat(r.passed()).isTrue();
        }

        @Test
        void totalSupplyUsedWhenMaxMissing() {
            // circulating=6, max=null, total=10 → 0.6 PASS
            var r = filter.apply(ctx(snap(new BigDecimal("6"), null, new BigDecimal("10"))));
            assertThat(r.passed()).isTrue();
        }

        @Test
        void zeroMaxFallsBackToTotal() {
            // circulating=6, max=0（无效），total=10 → 0.6 PASS
            var r = filter.apply(ctx(snap(new BigDecimal("6"), BigDecimal.ZERO, new BigDecimal("10"))));
            assertThat(r.passed()).isTrue();
        }
    }

    @Nested
    class ThresholdBoundary {
        @Test
        void exactly50PercentPasses() {
            var r = filter.apply(ctx(snap(new BigDecimal("5000000"), new BigDecimal("10000000"), null)));
            assertThat(r.passed()).isTrue();
        }

        @Test
        void justBelow50Rejects() {
            // circulating=4_900_000 / max=10_000_000 = 0.49 < 0.5 → reject
            // 避免 4999999/10000000 = 0.4999999 在 BigDecimal HALF_UP 下 rounding 为 0.5 的精度陷阱
            var r = filter.apply(ctx(snap(new BigDecimal("4900000"), new BigDecimal("10000000"), null)));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("CIRCULATION_TOO_LOW");
            assertThat(r.dataQuality()).isEqualTo(DataQuality.FRESH);
        }
    }

    @Nested
    class DataQualityCases {
        @Test
        void unavailable() {
            var r = filter.apply(ctx(ProviderResult.unavailable("test")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("CIRCULATION_UNAVAILABLE");
            assertThat(r.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        }

        @Test
        void timeout() {
            var r = filter.apply(ctx(ProviderResult.timeout("test")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("CIRCULATION_TIMEOUT");
        }

        @Test
        void stale() {
            var r = filter.apply(ctx(ProviderResult.stale(
                    new MarketCapSnapshot("BTC", null, null, new BigDecimal("6"),
                            new BigDecimal("10"), null, Instant.now().minusSeconds(700), "test"),
                    "test", Instant.now().minusSeconds(700))));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("CIRCULATION_STALE");
        }
    }

    @Nested
    class MissingCases {
        @Test
        void nullResult() {
            var r = filter.apply(ctx(null));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("CIRCULATION_MISSING");
        }

        @Test
        void nullCirculating() {
            var r = filter.apply(ctx(snap(null, new BigDecimal("10"), null)));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("CIRCULATION_MISSING");
        }

        @Test
        void nullBothMaxAndTotal() {
            var r = filter.apply(ctx(snap(new BigDecimal("6"), null, null)));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("CIRCULATION_MISSING");
        }

        @Test
        void zeroBothMaxAndTotal() {
            var r = filter.apply(ctx(snap(new BigDecimal("6"), BigDecimal.ZERO, BigDecimal.ZERO)));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("CIRCULATION_MISSING");
        }
    }
}

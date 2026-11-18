package com.example.quant.crypto.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.crypto.provider.ProviderResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Phase B PriceRangeFilter 独立单测。
 *
 * <p>关键约束：range 缺失时保守 PASS（不卡人），让下游 Filter 决定。
 */
class PriceRangeFilterTest {

    private static final BigDecimal MAX = new BigDecimal("40");
    private final PriceRangeFilter filter = new PriceRangeFilter(MAX);

    private static FilterContext ctx(BigDecimal range) {
        return new FilterContext("BTC-USDT-SWAP", "BTC", new BigDecimal("50000"),
                new BigDecimal("1000000000"), range,
                new BigDecimal("2"), new BigDecimal("100000"), new BigDecimal("10000000"),
                (ProviderResult) null);
    }

    @Test
    void nameStable() {
        assertThat(filter.name()).isEqualTo("price_range");
    }

    @Nested
    class CtorValidation {
        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> new PriceRangeFilter((BigDecimal) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsZero() {
            assertThatThrownBy(() -> new PriceRangeFilter(BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegative() {
            assertThatThrownBy(() -> new PriceRangeFilter(new BigDecimal("-1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Boundary {
        @Test
        void exactlyAtMaxPasses() {
            var r = filter.apply(ctx(MAX));
            assertThat(r.passed()).isTrue();
        }

        @Test
        void oneAboveMaxRejects() {
            var r = filter.apply(ctx(new BigDecimal("40.01")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("PRICE_RANGE_TOO_HIGH");
            assertThat((BigDecimal) r.actualValue()).isEqualByComparingTo("40.01");
            assertThat((BigDecimal) r.threshold()).isEqualByComparingTo(MAX);
        }

        @Test
        void wellBelowMaxPasses() {
            assertThat(filter.apply(ctx(new BigDecimal("5"))).passed()).isTrue();
        }
    }

    @Nested
    class NullBehavior {
        @Test
        void nullRangePassesConservative() {
            // 关键设计：range 缺失不卡人，让下游决定
            var r = filter.apply(ctx(null));
            assertThat(r.passed()).isTrue();
            assertThat(r.reason()).isNull();
        }
    }
}

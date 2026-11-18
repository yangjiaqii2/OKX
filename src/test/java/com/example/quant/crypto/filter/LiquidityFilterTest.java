package com.example.quant.crypto.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.crypto.provider.ProviderResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Phase B LiquidityFilter 独立单测。
 *
 * <p>spread 缺失时保守 PASS；depth 缺失时保守 PASS。两个独立 PASS 路径要分别验证。
 */
class LiquidityFilterTest {

    private static final BigDecimal MAX_SPREAD = new BigDecimal("30");
    private static final BigDecimal MIN_DEPTH = new BigDecimal("50000");
    private final LiquidityFilter filter = new LiquidityFilter(MAX_SPREAD, MIN_DEPTH);

    private static FilterContext ctx(BigDecimal spread, BigDecimal depth) {
        return new FilterContext("BTC-USDT-SWAP", "BTC", new BigDecimal("50000"),
                new BigDecimal("1000000000"), new BigDecimal("5"),
                spread, depth, new BigDecimal("10000000"),
                (ProviderResult) null);
    }

    @Test
    void nameStable() {
        assertThat(filter.name()).isEqualTo("liquidity");
    }

    @Nested
    class CtorValidation {
        @Test
        void rejectsNullMaxSpread() {
            assertThatThrownBy(() -> new LiquidityFilter((BigDecimal) null, MIN_DEPTH))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNullMinDepth() {
            assertThatThrownBy(() -> new LiquidityFilter(MAX_SPREAD, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegativeMaxSpread() {
            assertThatThrownBy(() -> new LiquidityFilter(new BigDecimal("-1"), MIN_DEPTH))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void allowsZeroMaxSpread() {
            // 0 表示拒绝任何 spread（最严）
            new LiquidityFilter(BigDecimal.ZERO, MIN_DEPTH);
        }
    }

    @Nested
    class SpreadCases {
        @Test
        void spreadAtMaxPasses() {
            var r = filter.apply(ctx(MAX_SPREAD, new BigDecimal("100000")));
            assertThat(r.passed()).isTrue();
        }

        @Test
        void spreadAboveMaxRejects() {
            var r = filter.apply(ctx(new BigDecimal("31"), new BigDecimal("100000")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("SPREAD_TOO_WIDE");
        }

        @Test
        void nullSpreadPassesConservative() {
            var r = filter.apply(ctx(null, new BigDecimal("100000")));
            assertThat(r.passed()).isTrue();
        }
    }

    @Nested
    class DepthCases {
        @Test
        void depthAtMinPasses() {
            var r = filter.apply(ctx(new BigDecimal("5"), MIN_DEPTH));
            assertThat(r.passed()).isTrue();
        }

        @Test
        void depthBelowMinRejects() {
            var r = filter.apply(ctx(new BigDecimal("5"), new BigDecimal("49999.99")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("DEPTH_TOO_LOW");
        }

        @Test
        void nullDepthPassesConservative() {
            var r = filter.apply(ctx(new BigDecimal("5"), null));
            assertThat(r.passed()).isTrue();
        }
    }

    @Nested
    class BothChecks {
        @Test
        void spreadRejectionTakesPriorityOverDepth() {
            // spread 31（reject）+ depth 0（reject）→ SPREAD 优先
            var r = filter.apply(ctx(new BigDecimal("31"), new BigDecimal("0")));
            assertThat(r.reason()).isEqualTo("SPREAD_TOO_WIDE");
        }
    }
}

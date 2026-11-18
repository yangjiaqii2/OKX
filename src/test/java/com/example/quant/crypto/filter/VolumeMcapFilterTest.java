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
 * Phase B VolumeMcapFilter 独立单测。
 *
 * <p>覆盖：阈值边界、volume/mcap 缺失、5 种 DataQuality 状态、构造器校验。
 */
class VolumeMcapFilterTest {

    private static final BigDecimal MIN_RATIO = new BigDecimal("0.20");
    private final VolumeMcapFilter filter = new VolumeMcapFilter(MIN_RATIO);

    private static FilterContext ctx(ProviderResult<MarketCapSnapshot> mcap, BigDecimal volume) {
        return new FilterContext("ETH-USDT-SWAP", "ETH", new BigDecimal("3000"),
                volume, new BigDecimal("5"),
                new BigDecimal("2"), new BigDecimal("100000"), new BigDecimal("5000000"), mcap);
    }

    private static ProviderResult<MarketCapSnapshot> mcap(BigDecimal mcapUsd) {
        return ProviderResult.fresh(
                new MarketCapSnapshot("ETH", mcapUsd, null, null, null, null, Instant.now(), "test"),
                "test");
    }

    @Test
    void nameStable() {
        assertThat(filter.name()).isEqualTo("volume_mcap");
    }

    @Nested
    class CtorValidation {
        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> new VolumeMcapFilter((BigDecimal) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegative() {
            assertThatThrownBy(() -> new VolumeMcapFilter(new BigDecimal("-0.1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void allowsZero() {
            // 0 表示不卡 ratio
            new VolumeMcapFilter(BigDecimal.ZERO);
        }
    }

    @Nested
    class ThresholdBoundary {
        @Test
        void exactly20PercentPasses() {
            // 5_000_000 / 25_000_000 = 0.20
            var r = filter.apply(ctx(mcap(new BigDecimal("25000000")), new BigDecimal("5000000")));
            assertThat(r.passed()).isTrue();
        }

        @Test
        void justBelow20Rejects() {
            // volume = 4_000_000 / mcap = 25_000_000 = 0.16 < 0.2 → reject
            var r = filter.apply(ctx(mcap(new BigDecimal("25000000")), new BigDecimal("4000000")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("VOLUME_MCAP_TOO_LOW");
        }

        @Test
        void wellAbovePasses() {
            var r = filter.apply(ctx(mcap(new BigDecimal("10000000")), new BigDecimal("5000000")));
            assertThat(r.passed()).isTrue();
        }

        @Test
        void minRatioZeroAcceptsAnyPositive() {
            VolumeMcapFilter zero = new VolumeMcapFilter(BigDecimal.ZERO);
            var r = zero.apply(ctx(mcap(new BigDecimal("10000000")), new BigDecimal("1")));
            assertThat(r.passed()).isTrue();
        }
    }

    @Nested
    class MissingCases {
        @Test
        void nullMcapResult() {
            var r = filter.apply(ctx(null, new BigDecimal("1000000")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("VOLUME_MCAP_MCAP_MISSING");
        }

        @Test
        void nullMarketCapUsdInSnapshot() {
            var r = filter.apply(ctx(mcap(null), new BigDecimal("1000000")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("VOLUME_MCAP_MCAP_MISSING");
        }

        @Test
        void zeroMarketCapUsd() {
            var r = filter.apply(ctx(mcap(BigDecimal.ZERO), new BigDecimal("1000000")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("VOLUME_MCAP_MCAP_MISSING");
        }

        @Test
        void nullVolume() {
            var r = filter.apply(ctx(mcap(new BigDecimal("10000000")), null));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("VOLUME_MCAP_VOLUME_MISSING");
        }

        @Test
        void zeroVolume() {
            var r = filter.apply(ctx(mcap(new BigDecimal("10000000")), BigDecimal.ZERO));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("VOLUME_MCAP_VOLUME_MISSING");
        }
    }

    @Nested
    class DataQualityCases {
        @Test
        void unavailableMcap() {
            var r = filter.apply(ctx(ProviderResult.unavailable("test"), new BigDecimal("1000000")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("VOLUME_MCAP_MCAP_UNAVAILABLE");
            assertThat(r.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        }

        @Test
        void timeoutMcap() {
            var r = filter.apply(ctx(ProviderResult.timeout("test"), new BigDecimal("1000000")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("VOLUME_MCAP_MCAP_TIMEOUT");
            assertThat(r.dataQuality()).isEqualTo(DataQuality.TIMEOUT);
        }

        @Test
        void staleMcap() {
            var r = filter.apply(ctx(ProviderResult.stale(
                    new MarketCapSnapshot("ETH", new BigDecimal("10000000"), null, null, null, null,
                            Instant.now().minusSeconds(700), "test"),
                    "test", Instant.now().minusSeconds(700)), new BigDecimal("1000000")));
            assertThat(r.passed()).isFalse();
            assertThat(r.reason()).isEqualTo("VOLUME_MCAP_MCAP_STALE");
        }
    }
}

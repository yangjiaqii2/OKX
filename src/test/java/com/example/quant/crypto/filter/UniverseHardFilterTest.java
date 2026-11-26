package com.example.quant.crypto.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;

/**
 * Phase B Universe 硬资格边界 + Funnel + 短路 + 异常隔离测试。
 * <p>覆盖 spec §3 关键硬阈值：
 * <ul>
 *   <li>marketCapUsd &ge; 20,000,000</li>
 *   <li>circulationRatio &ge; 0.50</li>
 *   <li>volume24h / marketCap &ge; 0.20</li>
 * </ul>
 */
class UniverseHardFilterTest {

    private static final BigDecimal MIN_MCAP = new BigDecimal("20000000");
    private static final BigDecimal MIN_CIRC = new BigDecimal("0.50");
    private static final BigDecimal MIN_VOL_MCAP = new BigDecimal("0.20");
    private static final BigDecimal MAX_RANGE = new BigDecimal("40");
    private static final BigDecimal MAX_SPREAD = new BigDecimal("30");
    private static final BigDecimal MIN_DEPTH = new BigDecimal("50000");

    private final UniverseHardFilter filter = new UniverseHardFilter(List.of(
            new MarketCapFilter(MIN_MCAP),
            new CirculationFilter(MIN_CIRC),
            new VolumeMcapFilter(MIN_VOL_MCAP),
            new PriceRangeFilter(MAX_RANGE),
            new LiquidityFilter(MAX_SPREAD, MIN_DEPTH)
    ));

    @Test
    void supplyAndTurnoverRulesAreOptInAndRejectUnderThresholdWhenEnabled() {
        var context = new ApplicationContextRunner()
                .withUserConfiguration(BoundAgentProperties.class)
                .withInitializer(ctx -> new ClassPathBeanDefinitionScanner((BeanDefinitionRegistry) ctx)
                        .scan(UniverseHardFilter.class.getPackageName()));
        var marketCap = ProviderResult.fresh(new MarketCapSnapshot("BTC", new BigDecimal("40000000"),
                null, BigDecimal.ONE, BigDecimal.valueOf(4), BigDecimal.valueOf(4), Instant.now(), "test"), "test");
        var lowCirculation = FilterContext.withV2Ratios("BTC-USDT-SWAP", "BTC", BigDecimal.ONE,
                BigDecimal.valueOf(100_000), null, null, null, null, marketCap,
                new BigDecimal("0.25"), new BigDecimal("0.0025"));
        context.run(ctx -> assertThat(ctx.getBean(UniverseHardFilter.class).apply(lowCirculation).passed()).isTrue());
        context.withPropertyValues("quant.agent.selection.universe.circulation-enabled=true",
                        "quant.agent.selection.universe.min-circulation-ratio=0.30")
                .run(ctx -> {
                    var result = ctx.getBean(UniverseHardFilter.class).apply(lowCirculation).result();
                    assertThat(result.reason()).isEqualTo(CirculationFilter.REASON_TOO_LOW);
                    assertThat(result.threshold()).isEqualTo(new BigDecimal("0.30"));
                });
        context.withPropertyValues("quant.agent.selection.universe.volume-mcap-enabled=true",
                        "quant.agent.selection.universe.min-volume-mcap-ratio=0.10")
                .run(ctx -> {
                    var result = ctx.getBean(UniverseHardFilter.class).apply(lowCirculation).result();
                    assertThat(result.reason()).isEqualTo(VolumeMcapFilter.REASON_TOO_LOW);
                    assertThat(result.threshold()).isEqualTo(new BigDecimal("0.10"));
                });
    }

    @EnableConfigurationProperties(AgentProperties.class)
    static class BoundAgentProperties {
    }

    private static FilterContext ctxWith(ProviderResult<MarketCapSnapshot> mcap) {
        return new FilterContext(
                "BTC-USDT-SWAP",
                "BTC",
                new BigDecimal("50000"),
                new BigDecimal("500000000"),  // 5 亿
                new BigDecimal("5"),
                new BigDecimal("2"),
                new BigDecimal("100000"),
                new BigDecimal("10000000"),
                mcap
        );
    }

    /**
     * Phase B V2 ratio 优先路径：candidate 已带 circulationRatio / volumeMcapRatio，
     * filter 直接用 ratio 比较，不走 snapshot absolute 路径。
     */
    private static FilterContext ctxWithV2(ProviderResult<MarketCapSnapshot> mcap,
                                           BigDecimal circRatio, BigDecimal volMcapRatio) {
        return FilterContext.withV2Ratios(
                "BTC-USDT-SWAP",
                "BTC",
                new BigDecimal("50000"),
                new BigDecimal("500000000"),
                new BigDecimal("5"),
                new BigDecimal("2"),
                new BigDecimal("100000"),
                new BigDecimal("10000000"),
                mcap,
                circRatio,
                volMcapRatio
        );
    }

    private static ProviderResult<MarketCapSnapshot> mcap(BigDecimal usd, BigDecimal circulating, BigDecimal max) {
        return ProviderResult.fresh(
                new MarketCapSnapshot("BTC", usd, usd != null ? usd.multiply(BigDecimal.valueOf(2)) : null,
                        circulating, max, max, Instant.now(), "test"),
                "test"
        );
    }

    @Nested
    @DisplayName("MarketCap 边界")
    class MarketCapBoundary {
        @Test
        void exactlyAtThresholdPasses() {
            // Phase B V2 path：candidate 自身带 V2 ratio 字段
            var r = filter.apply(ctxWithV2(
                    mcap(MIN_MCAP, null, null), new BigDecimal("0.50"), new BigDecimal("0.20")));
            assertThat(r.passed()).isTrue();
        }

        @Test
        void oneBelowThresholdRejects() {
            var r = filter.apply(ctxWithV2(
                    mcap(new BigDecimal("19999999.9999"), null, null),
                    new BigDecimal("0.50"), new BigDecimal("0.20")));
            assertThat(r.passed()).isFalse();
            assertThat(r.result().reason()).isEqualTo(MarketCapFilter.REASON_TOO_LOW);
        }

        @Test
        void unavailableRejects() {
            var r = filter.apply(ctxWith(ProviderResult.<MarketCapSnapshot>unavailable("test")));
            assertThat(r.passed()).isFalse();
            assertThat(r.result().reason()).isEqualTo(MarketCapFilter.REASON_UNAVAILABLE);
            assertThat(r.result().dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        }

        @Test
        void timeoutRejects() {
            var r = filter.apply(ctxWith(ProviderResult.<MarketCapSnapshot>timeout("test")));
            assertThat(r.passed()).isFalse();
            assertThat(r.result().reason()).isEqualTo(MarketCapFilter.REASON_TIMEOUT);
        }

        @Test
        void staleRejects() {
            var r = filter.apply(ctxWith(new ProviderResult<>(
                    new MarketCapSnapshot("BTC", MIN_MCAP, null, null, null, null,
                            Instant.now().minusSeconds(700), "test"),
                    DataQuality.STALE, Instant.now().minusSeconds(700), "test", null, null
            )));
            assertThat(r.passed()).isFalse();
            assertThat(r.result().reason()).isEqualTo(MarketCapFilter.REASON_STALE);
        }

        @Test
        void missingRejects() {
            var r = filter.apply(ctxWith(null));
            assertThat(r.passed()).isFalse();
            assertThat(r.result().reason()).isEqualTo(MarketCapFilter.REASON_MISSING);
        }
    }

    @Nested
    @DisplayName("Circulation 边界")
    class CirculationBoundary {
        @Test
        void exactlyAt50Passes() {
            // V2 ratio 优先：candidate 带 circulationRatio=0.50
            var r = filter.apply(ctxWithV2(
                    mcap(MIN_MCAP, null, null), new BigDecimal("0.50"), new BigDecimal("0.20")));
            assertThat(r.passed()).isTrue();
        }

        @Test
        void justBelow50Rejects() {
            // candidate 带 circulationRatio=0.49
            var r = filter.apply(ctxWithV2(
                    mcap(MIN_MCAP, null, null), new BigDecimal("0.49"), new BigDecimal("0.20")));
            assertThat(r.passed()).isFalse();
            assertThat(r.result().reason()).isEqualTo(CirculationFilter.REASON_TOO_LOW);
        }

        @Test
        void unavailableCirculationRejects() {
            // mcap unavailable → MarketCapFilter 短路过 → reason 是 MARKET_CAP_UNAVAILABLE
            // （不是 CIRCULATION_UNAVAILABLE，因为 short-circuit 在 cheap-first 链里优先）
            var r = filter.apply(ctxWith(ProviderResult.<MarketCapSnapshot>unavailable("test")));
            assertThat(r.passed()).isFalse();
            assertThat(r.result().reason()).isEqualTo(MarketCapFilter.REASON_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("VolumeMcap 边界")
    class VolumeMcapBoundary {
        private FilterContext ctxWithV2Ratios(BigDecimal mcapUsd, BigDecimal circRatio, BigDecimal volMcapRatio) {
            return FilterContext.withV2Ratios(
                    "ETH-USDT-SWAP", "ETH", new BigDecimal("3000"),
                    new BigDecimal("5000000"), new BigDecimal("5"), new BigDecimal("2"),
                    new BigDecimal("100000"), new BigDecimal("5000000"),
                    mcap(mcapUsd, null, null),
                    circRatio, volMcapRatio
            );
        }

        @Test
        void ratioExactlyAt20Passes() {
            // V2 ratio 优先：candidate 带 volumeMcapRatio=0.20
            var r = filter.apply(ctxWithV2Ratios(
                    new BigDecimal("25000000"), new BigDecimal("0.50"), new BigDecimal("0.20")));
            assertThat(r.passed()).isTrue();
        }

        @Test
        void ratioBelow20Rejects() {
            // candidate 带 volumeMcapRatio=0.16
            var r = filter.apply(ctxWithV2Ratios(
                    new BigDecimal("25000000"), new BigDecimal("0.50"), new BigDecimal("0.16")));
            assertThat(r.passed()).isFalse();
            assertThat(r.result().reason()).isEqualTo(VolumeMcapFilter.REASON_TOO_LOW);
        }
    }

    @Nested
    @DisplayName("短路：MarketCap reject 不消耗后续 Filter")
    class ShortCircuit {
        @Test
        void firstRejectSkipsLaterFilters() {
            // MarketCap reject，VolumeMcap 不会被跑（输入是 UNAVAILABLE）
            var outcome = filter.applyBatch(List.of(ctxWith(ProviderResult.unavailable("test"))));
            assertThat(outcome.passed()).isEmpty();
            assertThat(outcome.rejected()).hasSize(1);
            assertThat(outcome.rejected().get(0).rejection().filterName()).isEqualTo("market_cap");
            assertThat(outcome.funnel().stages()).containsKey("after_market_cap");
            // VolumeMcap stage 不会被记入（短路）
            assertThat(outcome.funnel().stages()).doesNotContainKey("after_volume_mcap");
        }
    }

    @Nested
    @DisplayName("异常隔离：Filter 抛异常时整体不崩溃")
    class ExceptionIsolation {
        @Test
        void explodingFilterIsolatedAsReject() {
            UniverseHardFilter custom = new UniverseHardFilter(List.of(
                    new UniverseFilter() {
                        @Override
                        public String name() {
                            return "boom";
                        }
                        @Override
                        public FilterResult apply(FilterContext context) {
                            throw new RuntimeException("kaboom");
                        }
                    }
            ));
            var outcome = custom.applyBatch(List.of(ctxWith(mcap(MIN_MCAP, null, null))));
            assertThat(outcome.passed()).isEmpty();
            assertThat(outcome.rejected()).hasSize(1);
            assertThat(outcome.rejected().get(0).rejection().filterName()).isEqualTo("boom");
            assertThat(outcome.rejected().get(0).rejection().reason()).startsWith("FILTER_EXCEPTION:");
        }
    }

    @Nested
    @DisplayName("Funnel 统计")
    class Funnel {
        @Test
        void funnelShowsRemainingPerStage() {
            var outcome = filter.applyBatch(List.of(
                    ctxWith(mcap(MIN_MCAP, new BigDecimal("6000000"), new BigDecimal("10000000"))),
                    ctxWith(ProviderResult.unavailable("test")),
                    ctxWith(mcap(new BigDecimal("1000000"), null, null))
            ));
            var stages = outcome.funnel().stages();
            assertThat(stages.get("universe_inputs")).isEqualTo(3);
            assertThat(stages.get("after_market_cap")).isEqualTo(1);
            // 通过 MarketCap 的 1 个 ticker 后续全部 PASS
            assertThat(stages.get("after_liquidity")).isEqualTo(1);
            assertThat(outcome.passed()).hasSize(1);
            assertThat(outcome.rejected()).hasSize(2);
        }
    }
}

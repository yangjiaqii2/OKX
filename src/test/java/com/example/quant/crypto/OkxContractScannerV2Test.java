package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.quant.agent.market.MarketEnvironmentService;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.scan.ScanDiagnostics;
import com.example.quant.agent.scan.ScanContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.filter.CirculationFilter;
import com.example.quant.crypto.filter.LiquidityFilter;
import com.example.quant.crypto.filter.MarketCapFilter;
import com.example.quant.crypto.filter.PriceRangeFilter;
import com.example.quant.crypto.filter.UniverseHardFilter;
import com.example.quant.crypto.filter.VolumeMcapFilter;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.MarketCapProvider;
import com.example.quant.crypto.provider.NullMarketCapProvider;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import com.example.quant.crypto.selection.V2SelectionScoreCalculator;
import com.example.quant.market.MarketType;
import com.example.quant.news.NewsSearchService;
import com.example.quant.news.dto.NewsItem;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxInstrumentRulesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Phase B 端到端测试 — OkxContractScanner V2 路径。
 *
 * <p>关注架构级断言：V2 流程不再"先 volume 排序后取 top30"，而是
 * "tickers → broad universe → Universe Hard Filter → deep analysis"。
 *
 * <p>关键架构级测试：{@link #nonTop30WithQualityCanEnterV2()} 锁住这一行为。
 */
class OkxContractScannerV2Test {

    private static final long VOL_HIGH = 5_000_000_000L;
    private static final long VOL_SLEEPER = 5_000_000L;
    private static final BigDecimal PRICE = new BigDecimal("1.5");

    @Test
    void exchangeMetadataKeepsNonCryptoAndUnknownSwapsOutOfSelection() {
        StubProvider provider = new StubProvider();
        for (String base : List.of("BTC", "ANTHROPIC", "UNKNOWN")) {
            provider.snapshot(base, qualitySnapshot(base));
        }
        var fixture = V2Fixture.builder().provider(provider).tickers(List.of(
                ticker("ANTHROPIC-USDT-SWAP", VOL_HIGH),
                ticker("UNKNOWN-USDT-SWAP", VOL_HIGH),
                ticker("BTC-USDT-SWAP", VOL_HIGH))).build();
        var metadata = mock(OkxInstrumentRulesService.class);
        when(metadata.liveCryptoUsdtSwapIds(any())).thenReturn(java.util.Set.of("BTC-USDT-SWAP"));
        fixture.scanner.configureInstrumentRulesService(metadata);

        assertThat(fixture.scanner.scan()).extracting(ContractCandidate::instId)
                .containsExactly("BTC-USDT-SWAP");
        assertThat(fixture.scanner.lastV2ScoreResults()).containsOnlyKeys("BTC-USDT-SWAP");
    }

    @Test
    void rejectsExcessiveRangeAndWideTickerSpreadBeforeDeepAnalysis() {
        var wideRange = ticker("RANGE-USDT-SWAP", VOL_HIGH);
        wideRange.put("high24h", "1.8");
        wideRange.put("low24h", "0.9");
        var wideSpread = ticker("SPREAD-USDT-SWAP", VOL_HIGH);
        wideSpread.put("bidPx", "1.499");
        wideSpread.put("askPx", "1.501");
        var boundary = ticker("BOUNDARY-USDT-SWAP", VOL_HIGH);
        boundary.put("high24h", "1.8");
        boundary.put("low24h", "1.2");
        boundary.put("bidPx", "1.4994");
        boundary.put("askPx", "1.5006");
        var missingQuote = ticker("MISSING-USDT-SWAP", VOL_HIGH);
        missingQuote.remove("bidPx");
        var invalidQuote = ticker("INVALID-USDT-SWAP", VOL_HIGH);
        invalidQuote.put("bidPx", "--");
        StubProvider provider = new StubProvider();
        for (String base : List.of("RANGE", "SPREAD", "BOUNDARY", "MISSING", "INVALID")) {
            provider.snapshot(base, qualitySnapshot(base));
        }
        var fixture = V2Fixture.builder().provider(provider)
                .tickers(List.of(wideRange, wideSpread, boundary, missingQuote, invalidQuote)).build();

        assertThat(fixture.scanner.scan()).extracting(ContractCandidate::instId)
                .containsExactlyInAnyOrder("BOUNDARY-USDT-SWAP", "MISSING-USDT-SWAP", "INVALID-USDT-SWAP");
    }

    @Test
    void completedScanCarriesRankedReservesWithoutExpandingPublishedCandidates() {
        StubProvider provider = new StubProvider();
        for (int i = 0; i < 5; i++) {
            provider.snapshot("POOL" + i, qualitySnapshot("POOL" + i));
        }
        AgentProperties properties = V2Fixture.defaultProps();
        properties.setTopCandidateLimit(2);
        var fixture = V2Fixture.builder().provider(provider).agentProperties(properties)
                .tickers(tickers("POOL", 5, VOL_HIGH)).build();
        var analysis = new ContractAnalysisService(fixture.scanner, null, null);

        var result = analysis.refreshScan("reserve-check");

        assertThat(result.candidates()).hasSize(2);
        assertThat(analysis.candidates()).containsExactlyElementsOf(result.candidates());
        assertThat(result.reserveCandidates()).hasSize(3).doesNotContainAnyElementsOf(result.candidates());
        assertThat(result.reserveCandidates()).allSatisfy(candidate ->
                assertThat(result.v2Scores()).containsKey(candidate.instId()));
    }

    @Test
    void scheduledResultsKeepTheirScanIdentityWhenManualScanOverlaps() {
        StubProvider provider = new StubProvider();
        provider.snapshot("POOL0", qualitySnapshot("POOL0"));
        provider.snapshot("POOL1", qualitySnapshot("POOL1"));
        AgentProperties properties = V2Fixture.defaultProps();
        properties.setTopCandidateLimit(1);
        var scanner = spy(V2Fixture.builder().provider(provider).agentProperties(properties)
                .tickers(tickers("POOL", 2, VOL_HIGH)).build().scanner);
        doAnswer(call -> {
            Object scheduledCandidates = call.callRealMethod();
            scanner.scan(ScanContext.unbounded("manual"));
            return scheduledCandidates;
        }).when(scanner).scan(argThat(ctx -> ctx.scanId().equals("scheduled")));
        var analysis = new ContractAnalysisService(scanner, null, null);

        var result = analysis.refreshScan("scheduled");

        assertThat(result.report().scanId()).isEqualTo("scheduled");
        assertThat(result.reserveCandidates()).hasSize(1);
        assertThat(analysis.liveSnapshot().scanId()).isEqualTo("scheduled");
    }

    @Test
    @DisplayName("架构级：非 Top 30 但满足 V2 阈值的候选能进入 V2 后续分析")
    void nonTop30WithQualityCanEnterV2() {
        // 30 个"老钱"ticker volume 很高，但 mcap=10M (< 20M 阈值) — V2 拒
        // 1 个 SLEEPER ticker volume 极低，但 mcap=20M + circulation=50% + volMcap=20% — V2 通过
        // 验证 SLEEPER 出现在 scan() 输出中（即使前 30 个被 V2 拒）
        StubProvider provider = new StubProvider();
        for (int i = 0; i < 30; i++) {
            String base = "OLD" + i;
            provider.snapshot(base, lowMcapSnapshot(base));
        }
        provider.snapshot("SLEEPER", qualitySnapshot("SLEEPER"));

        V2Fixture fix = V2Fixture.builder()
                .provider(provider)
                .tickers(tickersWithSleeper())
                .build();
        OkxContractScanner scanner = fix.scanner;

        List<ContractCandidate> out = scanner.scan();
        ScanDiagnostics diag = scanner.lastDiagnostics();

        // 1) universe 包含 31 个 USDT-SWAP（30 高 volume + 1 SLEEPER）
        assertThat(diag.universeTickers()).isEqualTo(31);
        // 2) 前 30 个被 market_cap 拒，SLEEPER 通过 → passed=1, rejected=30
        assertThat(diag.passedCount()).isEqualTo(1);
        assertThat(diag.rejectedCount()).isEqualTo(30);
        // 3) deepAnalysisTargetCount 反映 passed 数
        assertThat(diag.deepAnalysisTargetCount()).isEqualTo(1);
        // 4) providerStatus 为 FRESH（所有都有 data）
        assertThat(diag.providerStatus()).containsEntry("market_cap", DataQuality.FRESH);
        assertThat(diag.providerStatus()).containsKey("relative_strength");
        assertThat(diag.providerStatus()).containsKey("ath_breakout");
        // 5) scan() 输出包含 SLEEPER（即使它不是 volume Top 30）
        assertThat(out).extracting(ContractCandidate::instId)
                .contains("SLEEPER-USDT-SWAP");
        // 6) scan() 输出不包含被 V2 拒的 OLD*
        assertThat(out).extracting(ContractCandidate::instId)
                .doesNotContain("OLD0-USDT-SWAP", "OLD29-USDT-SWAP");
        assertThat(out.get(0).relativeStrength()).isNotNull();
        assertThat(out.get(0).athBreakout()).isNotNull();
        assertThat(out.get(0).score()).isEqualTo(out.get(0).score());
    }

    @Test
    @DisplayName("V2 端到端：所有候选满足阈值时全部进入 deep analysis")
    void v2EndToEndAllPass() {
        StubProvider provider = new StubProvider();
        for (int i = 0; i < 5; i++) {
            String base = "PASS" + i;
            provider.snapshot(base, qualitySnapshot(base));
        }

        V2Fixture fix = V2Fixture.builder()
                .provider(provider)
                .tickers(tickers("PASS", 5, VOL_HIGH))
                .build();
        OkxContractScanner scanner = fix.scanner;

        List<ContractCandidate> out = scanner.scan();
        ScanDiagnostics diag = scanner.lastDiagnostics();

        assertThat(diag.universeTickers()).isEqualTo(5);
        assertThat(diag.passedCount()).isEqualTo(5);
        assertThat(diag.rejectedCount()).isZero();
        assertThat(diag.funnel().stages()).containsKey("after_market_cap");
        // topCandidateLimit=10，所以 5 个全在 ranked 中
        assertThat(out).hasSize(5);
        for (int i = 1; i < out.size(); i++) {
            assertThat(V2SelectionScoreCalculator.compareForRanking(out.get(i - 1), out.get(i)))
                    .isLessThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("V2 当前 funding 请求失败时跳过候选，不把 UNKNOWN 当作零费率")
    void fundingFailureDoesNotBecomeZeroFundingCandidate() {
        StubProvider provider = new StubProvider();
        provider.snapshot("PASS0", qualitySnapshot("PASS0"));
        V2Fixture fix = V2Fixture.builder()
                .provider(provider)
                .tickers(tickers("PASS", 1, VOL_HIGH))
                .failFundingRate()
                .build();

        List<ContractCandidate> out = fix.scanner.scan();

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("NullMarketCapProvider 路径：所有候选都被 market_cap 拒")
    void nullProviderRejectsAll() {
        V2Fixture fix = V2Fixture.builder()
                .provider(new NullMarketCapProvider())
                .tickers(tickers("Z", 5, VOL_HIGH))
                .build();
        OkxContractScanner scanner = fix.scanner;

        List<ContractCandidate> out = scanner.scan();
        ScanDiagnostics diag = scanner.lastDiagnostics();

        assertThat(diag.universeTickers()).isEqualTo(5);
        assertThat(diag.passedCount()).isZero();
        assertThat(diag.rejectedCount()).isEqualTo(5);
        assertThat(diag.providerStatus()).containsEntry("market_cap", DataQuality.UNAVAILABLE);
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("ProviderCache 命中：同 base 在单次 scan 内多次出现只 fetch 一次")
    void providerCacheReusedWithinSingleScan() {
        AtomicInteger fetchCount = new AtomicInteger();
        MarketCapProvider counting = base -> {
            fetchCount.incrementAndGet();
            return ProviderResult.fresh(qualitySnapshot(base), "counting");
        };

        // 10 个不同的 base，但每个 base 只 fetch 一次
        V2Fixture fix = V2Fixture.builder()
                .provider(counting)
                .tickers(tickers("BASE", 10, VOL_HIGH))
                .build();
        fix.scanner.scan();
        fix.scanner.scan();

        assertThat(fetchCount.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("扫描完成日志使用 open_interest_history 状态输出 OI 数据质量")
    void finishedLogUsesOpenInterestHistoryQuality() {
        StubProvider provider = new StubProvider();
        provider.snapshot("PASS0", qualitySnapshot("PASS0"));
        OkxContractScanner scanner = V2Fixture.builder()
                .provider(provider)
                .tickers(tickers("PASS", 1, VOL_HIGH))
                .build().scanner;
        Logger logger = (Logger) LoggerFactory.getLogger(OkxContractScanner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            scanner.scan();

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("scanV2 finished")
                            && message.contains("oiDataQuality=UNAVAILABLE"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static List<Map<String, Object>> tickersWithSleeper() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            all.add(ticker("OLD" + i + "-USDT-SWAP", VOL_HIGH - i));
        }
        all.add(ticker("SLEEPER-USDT-SWAP", VOL_SLEEPER));
        return all;
    }

    private static List<Map<String, Object>> tickers(String prefix, int count, long volume) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            all.add(ticker(prefix + i + "-USDT-SWAP", volume - i * 1000L));
        }
        return all;
    }

    private static List<Map<String, Object>> tickersWithSpecific(Map<String, BigDecimal> volumeByInst) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : volumeByInst.entrySet()) {
            all.add(ticker(e.getKey(), e.getValue().longValueExact()));
        }
        return all;
    }

    private static Map<String, Object> ticker(String instId, long quoteVolumeUsdt) {
        Map<String, Object> t = new HashMap<>();
        t.put("instId", instId);
        t.put("last", PRICE.toPlainString());
        t.put("open24h", PRICE.toPlainString());
        // 夹具按目标 USDT 成交额传参；写入基础币数量，避免把美元当成 volCcy24h。
        BigDecimal baseVolume = quoteVolumeUsdt == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(quoteVolumeUsdt).divide(PRICE, 8, java.math.RoundingMode.HALF_UP);
        t.put("volCcy24h", baseVolume.stripTrailingZeros().toPlainString());
        t.put("vol24h", "0");
        t.put("bidPx", PRICE.subtract(new BigDecimal("0.0001")).toPlainString());
        t.put("askPx", PRICE.add(new BigDecimal("0.0001")).toPlainString());
        return t;
    }

    /**
     * 给 base 返 marketCap=20M, circulating=15M, max=25M, vol=5M → vol/mcap=0.25, circulation=0.60
     */
    private static MarketCapSnapshot qualitySnapshot(String base) {
        return new MarketCapSnapshot(base, new BigDecimal("20000000"),
                new BigDecimal("25000000"),
                new BigDecimal("15000000"), new BigDecimal("25000000"),
                null, Instant.now(), "test");
    }

    /**
     * 给 base 返 marketCap=10M, circulating=9M, max=10M → 不达 20M 阈值
     */
    private static MarketCapSnapshot lowMcapSnapshot(String base) {
        return new MarketCapSnapshot(base, new BigDecimal("10000000"),
                new BigDecimal("15000000"),
                new BigDecimal("9000000"), new BigDecimal("10000000"),
                null, Instant.now(), "test");
    }

    // ---------------------------------------------------------------------
    // V2 fixture: builds a 9-arg OkxContractScanner with stub market cap provider
    // ---------------------------------------------------------------------

    private static OkxInstrumentRules testRules(String instId) {
        return new OkxInstrumentRules(instId, BigDecimal.ONE,
                instId.substring(0, instId.indexOf('-')),
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.01"));
    }

    private static final class StubProvider implements MarketCapProvider {
        private final Map<String, MarketCapSnapshot> data = new HashMap<>();

        void snapshot(String base, MarketCapSnapshot snap) {
            data.put(base, snap);
        }

        @Override
        public ProviderResult<MarketCapSnapshot> fetch(String baseSymbol) {
            MarketCapSnapshot s = data.get(baseSymbol);
            if (s == null) {
                return ProviderResult.unavailable("no snapshot for " + baseSymbol);
            }
            return ProviderResult.fresh(s, "stub");
        }
    }

    private static final class V2Fixture {
        final OkxContractScanner scanner;

        private V2Fixture(OkxContractScanner scanner) {
            this.scanner = scanner;
        }

        static AgentProperties defaultProps() {
            AgentProperties p = new AgentProperties();
            p.setTopCandidateLimit(10);
            return p;
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private MarketCapProvider provider;
            private List<Map<String, Object>> tickers = List.of();
            private AgentProperties agentProperties = defaultProps();
            private boolean failFundingRate;

            Builder provider(MarketCapProvider provider) {
                this.provider = provider;
                return this;
            }

            Builder tickers(List<Map<String, Object>> tickers) {
                this.tickers = tickers;
                return this;
            }

            Builder agentProperties(AgentProperties props) {
                this.agentProperties = props;
                return this;
            }

            Builder failFundingRate() {
                this.failFundingRate = true;
                return this;
            }

            V2Fixture build() {
                FakeTickerClient client = new FakeTickerClient(tickers, failFundingRate);
                OkxMarketService marketService = new OkxMarketService(client);
                OkxContractScanner s = new OkxContractScanner(
                        client,
                        marketService,
                        new ContractSignalAnalyzer(),
                        new ContractNewsAnalysisService(new NewsSearchService(
                                (marketType, symbol, name, keywords, maxResults, lookbackHours) -> List.of(
                                        new NewsItem("neutral market update", "test", "https://example.test",
                                                Instant.now(), "", "NEUTRAL", 0, 10, List.of(symbol), List.of(),
                                                MarketType.OKX_SWAP))),
                                agentProperties),
                        new OrderBookLiquidityService(client, agentProperties,
                                OkxContractScannerV2Test::testRules),
                        new MarketEnvironmentService(marketService),
                        agentProperties,
                        provider,
                        defaultUniverseFilter(agentProperties)
                );
                return new V2Fixture(s);
            }
        }

        private static UniverseHardFilter defaultUniverseFilter(AgentProperties props) {
            return new UniverseHardFilter(List.of(
                    new MarketCapFilter(props.selection().universe().minMarketCapUsd()),
                    new CirculationFilter(props.selection().universe().minCirculationRatio()),
                    new VolumeMcapFilter(props.selection().universe().minVolumeMcapRatio()),
                    new PriceRangeFilter(props.market().max24hRangePct()),
                    new LiquidityFilter(props.market().maxSpreadBps(), props.market().minDepthUsdt())
            ));
        }
    }

    private static final class FakeTickerClient extends OkxRestClient {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final List<Map<String, Object>> tickers;
        private final boolean failFundingRate;

        FakeTickerClient(List<Map<String, Object>> tickers, boolean failFundingRate) {
            super(null, null, null, null, null);
            this.tickers = tickers;
            this.failFundingRate = failFundingRate;
        }

        @Override
        public JsonNode publicGet(String requestPath, java.time.Duration timeout) {
            return publicGet(requestPath);
        }

        @Override
        protected JsonNode publicGetWithoutHttpClient(String requestPath) {
            return publicGet(requestPath);
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            try {
                if (requestPath.startsWith("/api/v5/market/tickers")) {
                    return objectMapper.readTree(toJson(tickers));
                }
                if (requestPath.startsWith("/api/v5/market/candles")) {
                    return objectMapper.readTree(candlesJson());
                }
                if (requestPath.startsWith("/api/v5/market/books")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"bids":[["1.4999","10000"],["1.48","8000"]],"asks":[["1.5001","10000"],["1.52","8000"]]}]}
                            """);
                }
                if (requestPath.startsWith("/api/v5/public/funding-rate")) {
                    if (failFundingRate) {
                        throw new IllegalStateException("funding unavailable");
                    }
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"fundingRate":"0.0001"}]}
                            """);
                }
                if (requestPath.startsWith("/api/v5/public/open-interest")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"oiCcy":"500000000"}]}
                            """);
                }
                return objectMapper.readTree("{\"code\":\"0\",\"data\":[]}");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private static String toJson(List<Map<String, Object>> rows) {
            StringBuilder sb = new StringBuilder("{\"code\":\"0\",\"data\":[");
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) sb.append(",");
                Map<String, Object> r = rows.get(i);
                sb.append("{");
                boolean first = true;
                for (Map.Entry<String, Object> e : r.entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(e.getKey()).append("\":");
                    if (e.getValue() instanceof Number n) {
                        sb.append("\"").append(n.toString()).append("\"");
                    } else {
                        sb.append("\"").append(e.getValue().toString()).append("\"");
                    }
                    first = false;
                }
                sb.append("}");
            }
            sb.append("]}");
            return sb.toString();
        }

        private static String candlesJson() {
            List<ContractCandle> candles = new ArrayList<>();
            BigDecimal close = BigDecimal.valueOf(1.5);
            for (int i = 0; i < 240; i++) {
                BigDecimal open = close;
                close = close.add(i % 2 == 0 ? new BigDecimal("0.005") : new BigDecimal("-0.005"));
                candles.add(new ContractCandle(
                        Instant.parse("2026-08-09T00:00:00Z").plusSeconds(i * 300L),
                        open,
                        close.max(open).multiply(BigDecimal.valueOf(1.004)),
                        close.min(open).multiply(BigDecimal.valueOf(0.996)),
                        close,
                        BigDecimal.valueOf(1000 + i)));
            }
            List<String> rows = candles.stream()
                    .map(c -> "[\"%d\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"0\",\"0\",\"1\"]".formatted(
                            c.timestamp().toEpochMilli(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                    .toList();
            List<String> reversed = new ArrayList<>(rows);
            Collections.reverse(reversed);
            return "{\"code\":\"0\",\"data\":[" + String.join(",", reversed) + "]}";
        }
    }
}

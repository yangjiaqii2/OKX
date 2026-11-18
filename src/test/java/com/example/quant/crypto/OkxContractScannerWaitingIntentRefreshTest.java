package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.market.MarketEnvironmentService;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.scan.ScanDiagnostics;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.filter.CirculationFilter;
import com.example.quant.crypto.filter.LiquidityFilter;
import com.example.quant.crypto.filter.MarketCapFilter;
import com.example.quant.crypto.filter.PriceRangeFilter;
import com.example.quant.crypto.filter.UniverseHardFilter;
import com.example.quant.crypto.filter.VolumeMcapFilter;
import com.example.quant.crypto.provider.MarketCapProvider;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import com.example.quant.market.MarketType;
import com.example.quant.news.NewsSearchService;
import com.example.quant.news.dto.NewsItem;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 已存在的等待入场意图必须持续拿到数据刷新和评估机会。
 *
 * <p>{@code WaitingEntryIntentService.evaluate()} 只对"出现在交付列表里的合约"判断触发与失效，
 * 找不到候选就直接跳过。所以"等待记录没被删除"完全不等于"等待机制在工作"——
 * 只要该合约连续若干轮掉出交付列表，它就既不会触发也不会失效，一路静默到 30 分钟过期。
 *
 * <p>本测试锁住扫描链路这一侧的保证：<b>有活跃等待意图的合约，每轮都拿到深度分析名额和交付名额</b>，
 * 且名额从既有 {@code deepAnalysisLimit} / {@code topCandidateLimit} 内部扣除，不扩大请求预算。
 */
class OkxContractScannerWaitingIntentRefreshTest {

    private static final BigDecimal PRICE = new BigDecimal("1.5");
    private static final int PASSED_COUNT = 40;
    private static final int DEEP_LIMIT = 30;
    private static final int ROTATION_QUOTA = 6;
    private static final int TOP_LIMIT = 10;

    /** 成交额末位：既进不了成交额头部，也不可能每轮都被轮换选中。 */
    private static final String WAITING = "SYM" + (PASSED_COUNT - 1) + "-USDT-SWAP";

    @Test
    @DisplayName("等待中的合约每轮都被深度分析，不依赖它当轮的成交额排名")
    void waitingInstIdReceivesDeepAnalysisEveryRound() {
        Fixture fixture = fixture();
        fixture.waiting.set(Set.of(WAITING));

        for (int round = 0; round < 8; round++) {
            fixture.client.klineRequests.clear();
            fixture.scanner.scan();

            assertThat(fixture.client.klineRequests)
                    .as("第 %d 轮：等待中的合约必须拿到新的 K 线与失效判断数据", round)
                    .contains(WAITING);
        }
    }

    @Test
    @DisplayName("等待中的合约每轮都进入交付列表，evaluate() 才可能判断触发与失效")
    void waitingInstIdIsDeliveredEveryRound() {
        Fixture fixture = fixture();
        fixture.waiting.set(Set.of(WAITING));

        for (int round = 0; round < 8; round++) {
            List<ContractCandidate> delivered = fixture.scanner.scan();

            assertThat(delivered).extracting(ContractCandidate::instId)
                    .as("第 %d 轮：交付列表里没有它，evaluate() 就会直接 continue", round)
                    .contains(WAITING);
            assertThat(delivered)
                    .as("交付数量仍然受 topCandidateLimit 限制")
                    .hasSizeLessThanOrEqualTo(TOP_LIMIT);
        }
    }

    @Test
    @DisplayName("没有等待意图时，扫描链路的名额分配与改造前完全一致")
    void withoutWaitingIntentsAllocationIsUnchanged() {
        Fixture pinned = fixture();
        Fixture plain = fixture();
        pinned.waiting.set(Set.of());

        for (int round = 0; round < 5; round++) {
            pinned.client.klineRequests.clear();
            plain.client.klineRequests.clear();
            pinned.scanner.scan();
            plain.scanner.scan();

            assertThat(analysedUniverse(pinned))
                    .containsExactlyInAnyOrderElementsOf(analysedUniverse(plain));
        }
    }

    @Test
    @DisplayName("固定名额从 deepAnalysisLimit 内部扣除：等待意图不会增加外部请求预算")
    void waitingIntentDoesNotExpandTheDeepAnalysisBudget() {
        Fixture fixture = fixture();
        fixture.waiting.set(new LinkedHashSet<>(List.of(
                "SYM39-USDT-SWAP", "SYM38-USDT-SWAP", "SYM37-USDT-SWAP")));

        for (int round = 0; round < 5; round++) {
            fixture.client.klineRequests.clear();
            fixture.scanner.scan();
            ScanDiagnostics diagnostics = fixture.scanner.lastDiagnostics();

            assertThat(diagnostics.deepAnalysisTargetCount())
                    .as("深度分析名额总量不得因等待意图而增加")
                    .isEqualTo(DEEP_LIMIT);
            assertThat(analysedUniverse(fixture)).hasSizeLessThanOrEqualTo(DEEP_LIMIT);
        }
    }

    @Test
    @DisplayName("等待合约掉出硬过滤名单时不被强行拉回：本层不绕过资格判定")
    void waitingInstIdOutsideTheFilteredUniverseIsNotForcedIn() {
        Fixture fixture = fixture();
        fixture.waiting.set(Set.of("DELISTED-USDT-SWAP"));

        fixture.client.klineRequests.clear();
        List<ContractCandidate> delivered = fixture.scanner.scan();

        assertThat(fixture.client.klineRequests).doesNotContain("DELISTED-USDT-SWAP");
        assertThat(delivered).extracting(ContractCandidate::instId).doesNotContain("DELISTED-USDT-SWAP");
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private record Fixture(OkxContractScanner scanner, RecordingTickerClient client,
                           AtomicReference<Set<String>> waiting) {
    }

    private static Set<String> analysedUniverse(Fixture fixture) {
        Set<String> universe = new LinkedHashSet<>(fixture.client.instIds);
        universe.retainAll(fixture.client.klineRequests);
        return universe;
    }

    private static Fixture fixture() {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(TOP_LIMIT);
        properties.selection().setDeepAnalysisLimit(DEEP_LIMIT);
        properties.selection().setDeepAnalysisRotationQuota(ROTATION_QUOTA);

        List<String> tickers = new ArrayList<>();
        for (int i = 0; i < PASSED_COUNT; i++) {
            tickers.add("SYM" + i + "-USDT-SWAP");
        }
        RecordingTickerClient client = new RecordingTickerClient(tickers);
        OkxMarketService marketService = new OkxMarketService(client);
        OkxContractScanner scanner = new OkxContractScanner(
                client,
                marketService,
                new ContractSignalAnalyzer(),
                new ContractNewsAnalysisService(new NewsSearchService(
                        (marketType, symbol, name, keywords, maxResults, lookbackHours) -> List.of(
                                new NewsItem("neutral market update", "test", "https://example.test",
                                        Instant.now(), "", "NEUTRAL", 0, 10, List.of(symbol), List.of(),
                                        MarketType.OKX_SWAP))),
                        properties),
                new OrderBookLiquidityService(client, properties,
                        OkxContractScannerWaitingIntentRefreshTest::testRules),
                new MarketEnvironmentService(marketService),
                properties,
                qualityProvider(),
                universeFilter(properties)
        );
        AtomicReference<Set<String>> waiting = new AtomicReference<>(Set.of());
        scanner.setActiveEntryIntentInstIds(waiting::get);
        return new Fixture(scanner, client, waiting);
    }

    private static OkxInstrumentRules testRules(String instId) {
        return new OkxInstrumentRules(instId, BigDecimal.ONE,
                instId.substring(0, instId.indexOf('-')),
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.01"));
    }

    private static MarketCapProvider qualityProvider() {
        return base -> ProviderResult.fresh(new MarketCapSnapshot(base, new BigDecimal("20000000"),
                new BigDecimal("25000000"), new BigDecimal("15000000"), new BigDecimal("25000000"),
                null, Instant.now(), "test"), "test");
    }

    private static UniverseHardFilter universeFilter(AgentProperties props) {
        return new UniverseHardFilter(List.of(
                new MarketCapFilter(props.selection().universe().minMarketCapUsd()),
                new CirculationFilter(props.selection().universe().minCirculationRatio()),
                new VolumeMcapFilter(props.selection().universe().minVolumeMcapRatio()),
                new PriceRangeFilter(props.market().max24hRangePct()),
                new LiquidityFilter(props.market().maxSpreadBps(), props.market().minDepthUsdt())
        ));
    }

    private static final class RecordingTickerClient extends OkxRestClient {
        private static final Pattern INST_ID = Pattern.compile("instId=([A-Za-z0-9%\\-]+)");

        private final ObjectMapper objectMapper = new ObjectMapper();
        final List<String> instIds;
        final Set<String> klineRequests = ConcurrentHashMap.newKeySet();

        RecordingTickerClient(List<String> instIds) {
            super(null, null, null, null, null);
            this.instIds = instIds;
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
                    return objectMapper.readTree(tickersJson());
                }
                if (requestPath.startsWith("/api/v5/market/candles")) {
                    Matcher matcher = INST_ID.matcher(requestPath);
                    if (matcher.find()) {
                        klineRequests.add(java.net.URLDecoder.decode(matcher.group(1),
                                java.nio.charset.StandardCharsets.UTF_8));
                    }
                    return objectMapper.readTree(candlesJson());
                }
                if (requestPath.startsWith("/api/v5/market/books")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"bids":[["1.49","10000"],["1.48","8000"]],"asks":[["1.51","10000"],["1.52","8000"]]}]}
                            """);
                }
                if (requestPath.startsWith("/api/v5/public/funding-rate")) {
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

        private String tickersJson() {
            StringBuilder sb = new StringBuilder("{\"code\":\"0\",\"data\":[");
            for (int i = 0; i < instIds.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                long turnover = 5_000_000L + (instIds.size() - i) * 1_000L;
                String baseVolume = BigDecimal.valueOf(turnover)
                        .divide(PRICE, 8, java.math.RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toPlainString();
                sb.append("{\"instId\":\"").append(instIds.get(i)).append("\"")
                        .append(",\"last\":\"").append(PRICE.toPlainString()).append("\"")
                        .append(",\"open24h\":\"").append(PRICE.toPlainString()).append("\"")
                        .append(",\"high24h\":\"").append(PRICE.toPlainString()).append("\"")
                        .append(",\"low24h\":\"").append(PRICE.toPlainString()).append("\"")
                        .append(",\"volCcy24h\":\"").append(baseVolume).append("\"")
                        .append(",\"vol24h\":\"0\"")
                        .append(",\"bidPx\":\"1.49\",\"askPx\":\"1.51\"}");
            }
            return sb.append("]}").toString();
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

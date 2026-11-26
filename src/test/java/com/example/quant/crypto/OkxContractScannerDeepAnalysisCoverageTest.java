package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.market.MarketEnvironmentService;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.scan.ScanDiagnostics;
import com.example.quant.config.AgentProperties;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 深度分析名额在真实 {@code scanV2} 链路上的分配行为。
 *
 * <p>观测手段是 K 线请求：只有被选为深度分析目标的合约才会拉 K 线，
 * 因此"哪些 instId 发起过 candles 请求"就是"哪些候选真正拿到了分析机会"。
 * 这样不需要为测试在生产代码上开任何后门。
 */
class OkxContractScannerDeepAnalysisCoverageTest {

    private static final BigDecimal PRICE = new BigDecimal("1.5");
    private static final int PASSED_COUNT = 40;
    private static final int DEEP_LIMIT = 30;
    private static final int ROTATION_QUOTA = 6;

    @Test
    @DisplayName("静态名单上，流动性合格但成交额排名靠后的候选会在有界轮数内被深度分析")
    void lowTurnoverQualifiedCandidateEventuallyReceivesDeepAnalysis() {
        Fixture fixture = fixture(ROTATION_QUOTA);

        // 成交额最低的合格候选：纯成交额截断下它永远排在第 40 位，恒在 30 个名额之外。
        String lowestTurnover = "SYM" + (PASSED_COUNT - 1) + "-USDT-SWAP";

        int headSize = DEEP_LIMIT - ROTATION_QUOTA;
        int tailSize = PASSED_COUNT - headSize;
        int boundedRounds = (tailSize + ROTATION_QUOTA - 1) / ROTATION_QUOTA;

        boolean analysed = false;
        for (int round = 0; round < boundedRounds; round++) {
            fixture.client.klineRequests.clear();
            fixture.scanner.scan();
            if (fixture.client.klineRequests.contains(lowestTurnover)) {
                analysed = true;
                break;
            }
        }

        assertThat(analysed)
                .as("静态名单上，成交额末位的合格候选必须在 %d 轮内被深度分析", boundedRounds)
                .isTrue();
    }

    @Test
    @DisplayName("静态名单上，轮换在有界轮数内覆盖全部合格候选")
    void rotationCoversEveryQualifiedCandidateWithinBoundedRounds() {
        Fixture fixture = fixture(ROTATION_QUOTA);
        Set<String> analysed = new LinkedHashSet<>();

        int headSize = DEEP_LIMIT - ROTATION_QUOTA;
        int boundedRounds = (PASSED_COUNT - headSize + ROTATION_QUOTA - 1) / ROTATION_QUOTA;
        for (int round = 0; round < boundedRounds; round++) {
            fixture.client.klineRequests.clear();
            fixture.scanner.scan();
            analysed.addAll(fixture.client.klineRequests);
        }

        assertThat(fixture.scanner.lastDiagnostics().funnel().stages())
                .containsEntry("analyzed_last_30m", PASSED_COUNT)
                .containsEntry("rotation_targets", ROTATION_QUOTA)
                .containsEntry("analyzed_signals", DEEP_LIMIT);
        for (int i = 0; i < PASSED_COUNT; i++) {
            assertThat(analysed).contains("SYM" + i + "-USDT-SWAP");
        }
    }

    @Test
    @DisplayName("名额重分配不突破 deepAnalysisLimit：每轮深度分析数量仍然有界")
    void deepAnalysisCountStaysWithinConfiguredLimitEveryRound() {
        Fixture fixture = fixture(ROTATION_QUOTA);

        for (int round = 0; round < 8; round++) {
            fixture.client.klineRequests.clear();
            fixture.scanner.scan();
            ScanDiagnostics diagnostics = fixture.scanner.lastDiagnostics();

            assertThat(diagnostics.deepAnalysisTargetCount()).isEqualTo(DEEP_LIMIT);
            assertThat(analysedUniverse(fixture))
                    .as("候选池中拉过 K 线的合约数量不得超过深度分析上限")
                    .hasSizeLessThanOrEqualTo(DEEP_LIMIT);
        }
    }

    @Test
    @DisplayName("成交额头部名额保持不变：成交额最高的候选每轮都被分析")
    void turnoverHeadIsAnalysedEveryRound() {
        Fixture fixture = fixture(ROTATION_QUOTA);

        for (int round = 0; round < 5; round++) {
            fixture.client.klineRequests.clear();
            fixture.scanner.scan();

            assertThat(fixture.client.klineRequests).contains("SYM0-USDT-SWAP");
        }
    }

    @Test
    @DisplayName("轮换配额为 0 时完整复现改造前的纯成交额截断行为")
    void zeroRotationQuotaReproducesLegacyTurnoverTruncation() {
        Fixture fixture = fixture(0);

        for (int round = 0; round < 3; round++) {
            fixture.client.klineRequests.clear();
            fixture.scanner.scan();

            for (int i = 0; i < DEEP_LIMIT; i++) {
                assertThat(fixture.client.klineRequests).contains("SYM" + i + "-USDT-SWAP");
            }
            for (int i = DEEP_LIMIT; i < PASSED_COUNT; i++) {
                assertThat(fixture.client.klineRequests).doesNotContain("SYM" + i + "-USDT-SWAP");
            }
        }
    }

    @Test
    @DisplayName("交付候选数量仍然受 topCandidateLimit 限制")
    void deliveredCandidateCountStillRespectsTopCandidateLimit() {
        Fixture fixture = fixture(ROTATION_QUOTA);

        assertThat(fixture.scanner.scan()).hasSizeLessThanOrEqualTo(10);
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private record Fixture(OkxContractScanner scanner, RecordingTickerClient client) {
    }

    /**
     * 候选池内被深度分析的合约。
     *
     * <p>K 线请求里还混有 {@code MarketEnvironmentService} 为判断大盘环境固定拉取的
     * BTC / ETH，它们不占深度分析名额，也不随本轮改动变化，因此不计入。
     */
    private static Set<String> analysedUniverse(Fixture fixture) {
        Set<String> universe = new LinkedHashSet<>(fixture.client.instIds);
        universe.retainAll(fixture.client.klineRequests);
        return universe;
    }

    private static Fixture fixture(int rotationQuota) {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(10);
        properties.selection().setDeepAnalysisLimit(DEEP_LIMIT);
        properties.selection().setDeepAnalysisRotationQuota(rotationQuota);

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
                        OkxContractScannerDeepAnalysisCoverageTest::testRules),
                new MarketEnvironmentService(marketService),
                properties,
                qualityProvider(),
                universeFilter(properties)
        );
        return new Fixture(scanner, client);
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

    /** 记录每个 instId 是否发起过 K 线请求 = 是否真正拿到深度分析机会。 */
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
                            {"code":"0","data":[{"bids":[["1.4999","10000"],["1.48","8000"]],"asks":[["1.5001","10000"],["1.52","8000"]]}]}
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

        /** 成交额严格递减：SYM0 最高，SYM39 最低，全部满足硬过滤阈值。 */
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
                        .append(",\"bidPx\":\"1.4999\",\"askPx\":\"1.5001\"}");
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

package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.market.MarketEnvironmentService;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.news.NewsSearchService;
import com.example.quant.news.dto.NewsItem;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OkxContractScannerTest {
    @Test
    void scanUsesRecalculatedScoreForActionAndRetainsExistingGates() {
        var client = new FakeOkxRestClient();
        var scanner = new OkxContractScanner(client, new OkxMarketService(client), null, null, null, null,
                new AgentProperties());
        var book = new OrderBookLiquiditySnapshot("TREND-USDT-SWAP", null, null, null,
                null, null, null, null, true, "", Instant.EPOCH);
        var news = ContractNewsRiskAnalysis.low();
        var type = ContractSignalType.PULLBACK_LONG;
        assertThat(ContractSignalAnalyzer.action(type, 64, "READY", news)).isEqualTo("WAIT_CONFIRM");
        assertThat(scanner.adjustedAction(type, "READY", 69, news, book, new BigDecimal("69")))
                .isEqualTo("AUTO_TRADE_ALLOWED");
        assertThat(scanner.adjustedAction(type, "NOT_READY", 69, news, book, new BigDecimal("69")))
                .isEqualTo("WAIT_CONFIRM");
        assertThat(scanner.adjustedAction(type, "READY", 69, news, book, new BigDecimal("60")))
                .isEqualTo("WAIT");
    }

    @Test
    void ranksByCompositeSignalInsteadOfRawVolumeOnly() throws Exception {
        FakeOkxRestClient client = new FakeOkxRestClient();
        AgentProperties agentProperties = new AgentProperties();
        OkxMarketService marketService = new OkxMarketService(client);
        OkxContractScanner scanner = new OkxContractScanner(
                client,
                marketService,
                new ContractSignalAnalyzer(),
                new ContractNewsAnalysisService(new NewsSearchService((marketType, symbol, name, keywords, maxResults, lookbackHours) ->
                        List.of(new NewsItem("neutral market update", "test", "https://example.test", Instant.now(),
                                "", "NEUTRAL", 0, 10, List.of(symbol), List.of(), MarketType.OKX_SWAP))),
                        agentProperties),
                new OrderBookLiquidityService(client, agentProperties, OkxContractScannerTest::testRules),
                new MarketEnvironmentService(marketService),
                agentProperties,
                new OpenInterestService(null)
        );

        List<ContractCandidate> candidates = scanner.scan();

        assertThat(candidates).hasSize(10);
        assertThat(candidates.get(0).instId()).isEqualTo("TREND-USDT-SWAP");
        assertThat(candidates.get(0).volume24h()).isLessThan(candidates.stream()
                .map(ContractCandidate::volume24h)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO));
        assertThat(candidates.get(0).suggestedLeverage()).isGreaterThan(1);
        assertThat(candidates.get(0).spreadBps()).isLessThan(new BigDecimal("8"));
        assertThat(candidates.get(0).stopLossPrice()).isLessThan(candidates.get(0).lastPrice());
        assertThat(candidates.get(0).takeProfitPrice()).isGreaterThan(candidates.get(0).lastPrice());
        assertThat(candidates.get(0).trendDirection()).isEqualTo(DirectionBias.BULLISH);
        assertThat(candidates.get(0).klineAnalysis().analysisScore()).isNotNull();
        assertThat(candidates.get(0).klineAnalysis().analysisAction()).isNotBlank();
        assertThat(candidates.get(0).openInterestChange()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "1999999,65",
        "2000000,73",
        "4999999,73",
        "5000000,77",
        "9999999,77",
        "10000000,83",
        "49999999,83",
        "50000000,92",
        "149999999,92",
        "150000000,95"
    })
    void convertsSwapBaseVolumeToQuoteTurnoverBeforeScoring(long turnover, int liquidityScore) {
        FakeOkxRestClient client = new FakeOkxRestClient(false, turnover);
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setTopCandidateLimit(40);
        agentProperties.market().setMinVolume24hUsdt(BigDecimal.ONE);
        OkxContractScanner scanner = new OkxContractScanner(
                client,
                new OkxMarketService(client),
                new ContractSignalAnalyzer(),
                new ContractNewsAnalysisService(new NewsSearchService((marketType, symbol, name, keywords, maxResults, lookbackHours) ->
                        List.of(new NewsItem("neutral market update", "test", "https://example.test", Instant.now(),
                                "", "NEUTRAL", 0, 10, List.of(symbol), List.of(), MarketType.OKX_SWAP))),
                        agentProperties),
                new OrderBookLiquidityService(client, agentProperties, OkxContractScannerTest::testRules),
                new MarketEnvironmentService(new OkxMarketService(client)),
                agentProperties,
                new OpenInterestService(null)
        );

        ContractCandidate dormant = scanner.scan().stream()
                .filter(candidate -> candidate.instId().equals("DORMANT-USDT-SWAP"))
                .findFirst()
                .orElseThrow();

        assertThat(dormant.volume24h()).isEqualByComparingTo(BigDecimal.valueOf(turnover));
        assertThat(dormant.scoreBreakdown().liquidity()).isEqualTo(liquidityScore);
    }

    @Test
    void dropsTickerWhenQuoteTurnoverCannotBeComputed() {
        MissingTurnoverOkxRestClient client = new MissingTurnoverOkxRestClient();
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setTopCandidateLimit(40);
        OkxContractScanner scanner = new OkxContractScanner(
                client,
                new OkxMarketService(client),
                new ContractSignalAnalyzer(),
                new ContractNewsAnalysisService(new NewsSearchService((marketType, symbol, name, keywords, maxResults, lookbackHours) ->
                        List.of(new NewsItem("neutral market update", "test", "https://example.test", Instant.now(),
                                "", "NEUTRAL", 0, 10, List.of(symbol), List.of(), MarketType.OKX_SWAP))),
                        agentProperties),
                new OrderBookLiquidityService(client, agentProperties, OkxContractScannerTest::testRules),
                new MarketEnvironmentService(new OkxMarketService(client)),
                agentProperties,
                new OpenInterestService(null)
        );

        List<ContractCandidate> candidates = scanner.scan();

        assertThat(candidates)
                .extracting(ContractCandidate::instId)
                .doesNotContain("NOVOL-USDT-SWAP")
                .contains("GOOD-USDT-SWAP");
    }

    @Test
    void filtersByTradeabilityThenUsesDirectionNeutralTurnoverPrefilter() {
        PreFilterOkxRestClient client = new PreFilterOkxRestClient();
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setTopCandidateLimit(40);
        OkxMarketService marketService = new OkxMarketService(client);
        OkxContractScanner scanner = new OkxContractScanner(
                client,
                marketService,
                new ContractSignalAnalyzer(),
                new ContractNewsAnalysisService(new NewsSearchService((marketType, symbol, name, keywords, maxResults, lookbackHours) ->
                        List.of(new NewsItem("neutral market update", "test", "https://example.test", Instant.now(),
                                "", "NEUTRAL", 0, 10, List.of(symbol), List.of(), MarketType.OKX_SWAP))),
                        agentProperties),
                new OrderBookLiquidityService(client, agentProperties, OkxContractScannerTest::testRules),
                new MarketEnvironmentService(marketService),
                agentProperties,
                new OpenInterestService(null)
        );

        List<ContractCandidate> candidates = scanner.scan();

        assertThat(candidates)
                .extracting(ContractCandidate::instId)
                .contains("GAIN0-USDT-SWAP", "HUGE-USDT-SWAP")
                .doesNotContain("MOON-USD-SWAP", "LOWVOL-USDT-SWAP");
        ContractCandidate usdt = candidates.stream()
                .filter(candidate -> candidate.instId().equals("GAIN0-USDT-SWAP"))
                .findFirst()
                .orElseThrow();
        assertThat(usdt.quoteCurrency()).isEqualTo("USDT");
    }

    @Test
    void aggregatesOnlyCompleteNaturalUtcTwentyMinuteWindows() {
        Instant start = Instant.parse("2026-08-10T00:00:00Z");
        List<ContractCandle> candles = List.of(
                timedCandle(start.plusSeconds(15 * 60), "103", "104"),
                timedCandle(start.plusSeconds(5 * 60), "101", "102"),
                timedCandle(start, "100", "101"),
                timedCandle(start.plusSeconds(10 * 60), "102", "103"),
                timedCandle(start.plusSeconds(20 * 60), "104", "105"),
                timedCandle(start.plusSeconds(30 * 60), "106", "107"),
                timedCandle(start.plusSeconds(35 * 60), "107", "108")
        );

        List<ContractCandle> aggregated = com.example.quant.crypto.util.CandleAggregator.aggregate20m(candles);

        assertThat(aggregated).hasSize(1);
        assertThat(aggregated.get(0).timestamp()).isEqualTo(start);
        assertThat(aggregated.get(0).open()).isEqualByComparingTo("100");
        assertThat(aggregated.get(0).close()).isEqualByComparingTo("104");
        assertThat(aggregated.get(0).volume()).isEqualByComparingTo("4");
    }

    @Test
    void fundingRateFailureSkipsSymbolsInsteadOfTreatingUnknownAsZero() {
        FakeOkxRestClient client = new FakeOkxRestClient(true);
        AgentProperties agentProperties = new AgentProperties();
        OkxMarketService marketService = new OkxMarketService(client);
        OkxContractScanner scanner = new OkxContractScanner(
                client,
                marketService,
                new ContractSignalAnalyzer(),
                new ContractNewsAnalysisService(new NewsSearchService((marketType, symbol, name, keywords, maxResults, lookbackHours) ->
                        List.of()), agentProperties),
                new OrderBookLiquidityService(client, agentProperties, OkxContractScannerTest::testRules),
                new MarketEnvironmentService(marketService),
                agentProperties,
                new OpenInterestService(null)
        );

        assertThat(scanner.scan()).isEmpty();
    }

    @Test
    void safeFundingRateKeepsProviderFailureUnknownInsteadOfZero() throws Exception {
        FakeOkxRestClient client = new FakeOkxRestClient(true);
        AgentProperties agentProperties = new AgentProperties();
        OkxMarketService marketService = new OkxMarketService(client);
        OkxContractScanner scanner = new OkxContractScanner(
                client, marketService, new ContractSignalAnalyzer(),
                new ContractNewsAnalysisService(new NewsSearchService(
                        (marketType, symbol, name, keywords, maxResults, lookbackHours) -> List.of()), agentProperties),
                new OrderBookLiquidityService(client, agentProperties, OkxContractScannerTest::testRules),
                new MarketEnvironmentService(marketService), agentProperties, new OpenInterestService(null));
        java.lang.reflect.Method method = OkxContractScanner.class
                .getDeclaredMethod("safeFundingRate", String.class);
        method.setAccessible(true);

        Object fundingRate = method.invoke(scanner, "BTC-USDT-SWAP");

        assertThat(fundingRate).isNull();
    }

    private static OkxInstrumentRules testRules(String instId) {
        return new OkxInstrumentRules(instId, BigDecimal.ONE, instId.substring(0, instId.indexOf('-')),
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.01"));
    }

    private static final class FakeOkxRestClient extends OkxRestClient {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final boolean failFundingRate;
        private final long dormantTurnover;

        FakeOkxRestClient() {
            this(false);
        }

        FakeOkxRestClient(boolean failFundingRate) {
            this(failFundingRate, 60_000_000L);
        }

        FakeOkxRestClient(boolean failFundingRate, long dormantTurnover) {
            super(null, null, null, null, null);
            this.failFundingRate = failFundingRate;
            this.dormantTurnover = dormantTurnover;
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            try {
                if (requestPath.startsWith("/api/v5/market/tickers")) {
                    return objectMapper.readTree(tickersJson());
                }
                if (requestPath.startsWith("/api/v5/market/candles")) {
                    String instId = requestPath.replaceFirst(".*instId=", "").replaceFirst("&.*", "");
                    return objectMapper.readTree(candlesJson(instId));
                }
                if (requestPath.startsWith("/api/v5/market/books")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"bids":[["99.98","10000"],["99.95","8000"]],"asks":[["100.02","10000"],["100.05","8000"]]}]}
                            """);
                }
                if (requestPath.startsWith("/api/v5/public/funding-rate")) {
                    if (failFundingRate) {
                        throw new IllegalStateException("funding rate unavailable");
                    }
                    String instId = requestPath.replaceFirst(".*instId=", "").replaceFirst("&.*", "");
                    BigDecimal rate = instId.startsWith("HOT") ? BigDecimal.valueOf(0.004) : BigDecimal.valueOf(0.0001);
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"fundingRate":"%s"}]}
                            """.formatted(rate));
                }
                if (requestPath.startsWith("/api/v5/public/open-interest")) {
                    String instId = requestPath.replaceFirst(".*instId=", "").replaceFirst("&.*", "");
                    BigDecimal oi = instId.startsWith("TREND") ? BigDecimal.valueOf(980000000) : BigDecimal.valueOf(500000000);
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"oiCcy":"%s"}]}
                            """.formatted(oi));
                }
                return objectMapper.readTree("{\"code\":\"0\",\"data\":[]}");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private String tickersJson() {
            StringBuilder data = new StringBuilder();
            data.append(ticker("HOT-USDT-SWAP", 100, 100, 9_000_000_000L));
            data.append(",");
            data.append(ticker("TREND-USDT-SWAP", 112, 100, 2_000_000_000L));
            data.append(",");
            data.append(ticker("DORMANT-USDT-SWAP", 100, 100, dormantTurnover));
            for (int i = 0; i < 24; i++) {
                data.append(",");
                data.append(ticker("ALT" + i + "-USDT-SWAP", 100 + i, 100, 1_000_000_000L + i));
            }
            return "{\"code\":\"0\",\"data\":[" + data + "]}";
        }

        private static String ticker(String instId, int last, int open, long quoteVolumeUsdt) {
            // 测试夹具按「目标 USDT 成交额」传参；写入 volCcy24h 时换算为基础币数量。
            String baseVolume = last > 0
                    ? BigDecimal.valueOf(quoteVolumeUsdt)
                    .divide(BigDecimal.valueOf(last), 8, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString()
                    : Long.toString(quoteVolumeUsdt);
            return """
                    {"instId":"%s","last":"%d","open24h":"%d","volCcy24h":"%s"}
                    """.formatted(instId, last, open, baseVolume).trim();
        }

        private static String tickerWithBaseVolume(String instId, int last, int open, long baseVolume) {
            return """
                    {"instId":"%s","last":"%d","open24h":"%d","volCcy24h":"%d","vol24h":"0"}
                    """.formatted(instId, last, open, baseVolume).trim();
        }

        private static String candlesJson(String instId) {
            List<ContractCandle> candles = instId.startsWith("TREND") ? trendCandles()
                    : instId.startsWith("DORMANT") ? dormantCandles()
                    : weakCandles();
            List<String> rows = candles.stream()
                    .map(c -> "[\"%d\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"0\",\"0\",\"1\"]".formatted(
                            c.timestamp().toEpochMilli(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                    .toList();
            List<String> reversed = new ArrayList<>(rows);
            java.util.Collections.reverse(reversed);
            return "{\"code\":\"0\",\"data\":[" + String.join(",", reversed) + "]}";
        }

        private static List<ContractCandle> trendCandles() {
            List<ContractCandle> candles = new ArrayList<>();
            BigDecimal close = BigDecimal.valueOf(100);
            for (int i = 0; i < 240; i++) {
                BigDecimal open = close;
                BigDecimal step = new BigDecimal("0.11");
                close = close.add(i % 3 == 0 ? step.multiply(new BigDecimal("-0.65")) : step);
                candles.add(candle(open, close, i >= 224 ? 2600 : 1000 + i * 4L, i));
            }
            return candles;
        }

        private static List<ContractCandle> weakCandles() {
            List<ContractCandle> candles = new ArrayList<>();
            BigDecimal close = BigDecimal.valueOf(100);
            for (int i = 0; i < 240; i++) {
                BigDecimal open = close;
                close = close.add(BigDecimal.valueOf(i % 2 == 0 ? 0.20 : -0.22));
                candles.add(candle(open, close, 1000 + i, i));
            }
            return candles;
        }

        private static List<ContractCandle> dormantCandles() {
            List<ContractCandle> candles = new ArrayList<>();
            BigDecimal close = BigDecimal.valueOf(100);
            for (int i = 0; i < 240; i++) {
                BigDecimal open = close;
                close = close.add(i % 2 == 0 ? new BigDecimal("0.005") : new BigDecimal("-0.005"));
                candles.add(candle(open, close, 120 + i % 3, i));
            }
            return candles;
        }

        private static ContractCandle candle(BigDecimal open, BigDecimal close, long volume, int index) {
            return new ContractCandle(
                    Instant.parse("2026-08-09T00:00:00Z").plusSeconds(index * 300L),
                    open,
                    open.max(close).multiply(BigDecimal.valueOf(1.004)),
                    open.min(close).multiply(BigDecimal.valueOf(0.996)),
                    close,
                    BigDecimal.valueOf(volume)
            );
        }
    }

    private static ContractCandle timedCandle(Instant timestamp, String open, String close) {
        BigDecimal openValue = new BigDecimal(open);
        BigDecimal closeValue = new BigDecimal(close);
        return new ContractCandle(timestamp, openValue, closeValue.max(openValue), closeValue.min(openValue),
                closeValue, BigDecimal.ONE);
    }

    private static final class PreFilterOkxRestClient extends OkxRestClient {
        private final ObjectMapper objectMapper = new ObjectMapper();

        PreFilterOkxRestClient() {
            super(null, null, null, null, null);
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            try {
                if (requestPath.startsWith("/api/v5/market/tickers")) {
                    return objectMapper.readTree(tickersJson());
                }
                if (requestPath.startsWith("/api/v5/market/candles")) {
                    return objectMapper.readTree(candlesJson());
                }
                if (requestPath.startsWith("/api/v5/market/books")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"bids":[["99.98","10000"],["99.95","8000"]],"asks":[["100.02","10000"],["100.05","8000"]]}]}
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

        private static String tickersJson() {
            StringBuilder data = new StringBuilder();
            data.append(ticker("MOON-USD-SWAP", 200, 100, 2_000_000_000L));
            data.append(",");
            data.append(ticker("LOWVOL-USDT-SWAP", 199, 100, 1_000L));
            for (int i = 0; i < 29; i++) {
                data.append(",");
                data.append(ticker("GAIN" + i + "-USDT-SWAP", 198 - i, 100, 2_000_000_000L));
            }
            data.append(",");
            data.append(ticker("HUGE-USDT-SWAP", 101, 100, 9_000_000_000L));
            return "{\"code\":\"0\",\"data\":[" + data + "]}";
        }

        private static String ticker(String instId, int last, int open, long quoteVolumeUsdt) {
            String baseVolume = last > 0
                    ? BigDecimal.valueOf(quoteVolumeUsdt)
                    .divide(BigDecimal.valueOf(last), 8, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString()
                    : Long.toString(quoteVolumeUsdt);
            return """
                    {"instId":"%s","last":"%d","open24h":"%d","volCcy24h":"%s"}
                    """.formatted(instId, last, open, baseVolume).trim();
        }

        private static String candlesJson() {
            List<ContractCandle> candles = FakeOkxRestClient.weakCandles();
            List<String> rows = candles.stream()
                    .map(c -> "[\"%d\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"0\",\"0\",\"1\"]".formatted(
                            c.timestamp().toEpochMilli(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                    .toList();
            List<String> reversed = new ArrayList<>(rows);
            java.util.Collections.reverse(reversed);
            return "{\"code\":\"0\",\"data\":[" + String.join(",", reversed) + "]}";
        }
    }

    /** 缺少 volCcy24h、仅有 vol24h 且无 ctVal：成交额不可算，不得进入候选。 */
    private static final class MissingTurnoverOkxRestClient extends OkxRestClient {
        private final ObjectMapper objectMapper = new ObjectMapper();

        MissingTurnoverOkxRestClient() {
            super(null, null, null, null, null);
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            try {
                if (requestPath.startsWith("/api/v5/market/tickers")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[
                              {"instId":"NOVOL-USDT-SWAP","last":"100","open24h":"100","volCcy24h":"0","vol24h":"500000"},
                              {"instId":"GOOD-USDT-SWAP","last":"100","open24h":"100","volCcy24h":"20000000","vol24h":"0"}
                            ]}
                            """);
                }
                if (requestPath.startsWith("/api/v5/market/candles")) {
                    return objectMapper.readTree(candlesJson());
                }
                if (requestPath.startsWith("/api/v5/market/books")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"bids":[["99.98","10000"]],"asks":[["100.02","10000"]]}]}
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

        private static String candlesJson() {
            List<ContractCandle> candles = FakeOkxRestClient.weakCandles();
            List<String> rows = candles.stream()
                    .map(c -> "[\"%d\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"0\",\"0\",\"1\"]".formatted(
                            c.timestamp().toEpochMilli(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                    .toList();
            List<String> reversed = new ArrayList<>(rows);
            java.util.Collections.reverse(reversed);
            return "{\"code\":\"0\",\"data\":[" + String.join(",", reversed) + "]}";
        }
    }
}

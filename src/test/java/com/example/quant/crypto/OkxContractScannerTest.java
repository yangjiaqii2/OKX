package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.market.MarketEnvironmentService;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.news.NewsSearchService;
import com.example.quant.news.dto.NewsItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OkxContractScannerTest {

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
                                "", "NEUTRAL", 0, 10, List.of(symbol), List.of(), MarketType.OKX_SWAP)))),
                new OrderBookLiquidityService(client, agentProperties),
                new MarketEnvironmentService(marketService),
                agentProperties
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
    }

    private static final class FakeOkxRestClient extends OkxRestClient {
        private final ObjectMapper objectMapper = new ObjectMapper();

        FakeOkxRestClient() {
            super(null, null, null, null, null);
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

        private static String tickersJson() {
            StringBuilder data = new StringBuilder();
            data.append(ticker("HOT-USDT-SWAP", 100, 100, 9_000_000_000L));
            data.append(",");
            data.append(ticker("TREND-USDT-SWAP", 112, 100, 2_000_000_000L));
            for (int i = 0; i < 24; i++) {
                data.append(",");
                data.append(ticker("ALT" + i + "-USDT-SWAP", 100 + i, 100, 1_000_000_000L + i));
            }
            return "{\"code\":\"0\",\"data\":[" + data + "]}";
        }

        private static String ticker(String instId, int last, int open, long volume) {
            return """
                    {"instId":"%s","last":"%d","open24h":"%d","volCcy24h":"%d"}
                    """.formatted(instId, last, open, volume).trim();
        }

        private static String candlesJson(String instId) {
            List<ContractCandle> candles = instId.startsWith("TREND") ? trendCandles() : weakCandles();
            List<String> rows = candles.stream()
                    .map(c -> "[\"%d\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"]".formatted(
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
                candles.add(candle(open, close, i >= 224 ? 2600 : 1000 + i * 4L));
            }
            return candles;
        }

        private static List<ContractCandle> weakCandles() {
            List<ContractCandle> candles = new ArrayList<>();
            BigDecimal close = BigDecimal.valueOf(100);
            for (int i = 0; i < 240; i++) {
                BigDecimal open = close;
                close = close.add(BigDecimal.valueOf(i % 2 == 0 ? 0.20 : -0.22));
                candles.add(candle(open, close, 1000 + i));
            }
            return candles;
        }

        private static ContractCandle candle(BigDecimal open, BigDecimal close, long volume) {
            return new ContractCandle(
                    Instant.now(),
                    open,
                    open.max(close).multiply(BigDecimal.valueOf(1.004)),
                    open.min(close).multiply(BigDecimal.valueOf(0.996)),
                    close,
                    BigDecimal.valueOf(volume)
            );
        }
    }
}

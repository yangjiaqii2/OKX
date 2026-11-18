package com.example.quant.crypto.provider.coingecko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CoinGecko HTTP 客户端（生产实现）。
 *
 * <p>调用：{@code GET /coins/markets?vs_currency=usd&symbols={base}}
 *      -> 选择首条 -> 解析为 {@link CoinGeckoMarketData}。
 *
 * <p>鉴权：Demo 模式使用 {@code x-cg-demo-api-key} 和 Demo endpoint；Pro 模式使用
 * {@code x-cg-pro-api-key} 和 Pro endpoint。
 * 否则无 key（免费层）。<strong>不依赖</strong>CoinGecko 429 返回做速率控制 —— 限流由 {@link CoinGeckoTokenBucket} 在调用前完成。
 *
 * <p>普通上游异常 → {@link Optional#empty()}；本地中断或超时抛出
 * {@link CoinGeckoRequestException}，以保留取消语义。
 */
public final class CoinGeckoClientImpl implements CoinGeckoHttpClient {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoClientImpl.class);
    private static final String DEMO_BASE_URL = "https://api.coingecko.com/api/v3";
    private static final String PRO_BASE_URL = "https://pro-api.coingecko.com/api/v3";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String apiKeyHeader;

    public CoinGeckoClientImpl(String apiKey) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
                new ObjectMapper(), apiKey, "DEMO");
    }

    public CoinGeckoClientImpl(String apiKey, String apiMode) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
                new ObjectMapper(), apiKey, apiMode);
    }

    public CoinGeckoClientImpl(HttpClient httpClient, ObjectMapper objectMapper, String apiKey) {
        this(httpClient, objectMapper, apiKey, "DEMO");
    }

    public CoinGeckoClientImpl(HttpClient httpClient, ObjectMapper objectMapper,
                               String apiKey, String apiMode) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey;
        boolean pro = "PRO".equalsIgnoreCase(apiMode);
        this.baseUrl = pro ? PRO_BASE_URL : DEMO_BASE_URL;
        this.apiKeyHeader = pro ? "x-cg-pro-api-key" : "x-cg-demo-api-key";
    }

    @Override
    public Optional<CoinGeckoMarketData> fetchBySymbol(String baseSymbol) {
        List<CoinGeckoMarketData> batch = fetchBySymbols(List.of(baseSymbol));
        return batch.isEmpty() ? Optional.empty() : Optional.of(batch.get(0));
    }

    @Override
    public List<CoinGeckoMarketData> fetchBySymbols(List<String> baseSymbols) {
        if (baseSymbols == null || baseSymbols.isEmpty()) {
            return List.of();
        }
        String joined = baseSymbols.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase())
                .distinct()
                .collect(Collectors.joining(","));
        if (joined.isEmpty()) {
            return List.of();
        }
        try {
            URI uri = URI.create(baseUrl + "/coins/markets?vs_currency=usd&symbols=" + joined);
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "quant-assistant/1.0");
            if (apiKey != null) {
                rb.header(apiKeyHeader, apiKey);
            }
            HttpResponse<String> resp = httpClient.send(rb.GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429) {
                log.warn("CoinGecko rate limited upstream symbols={}", joined);
                throw new CoinGeckoRequestException(
                        CoinGeckoRequestException.Kind.RATE_LIMITED, "upstream rate limited");
            }
            if (resp.statusCode() / 100 != 2) {
                log.warn("CoinGecko non-2xx status={} symbols={}", resp.statusCode(), joined);
                return List.of();
            }
            JsonNode root = objectMapper.readTree(resp.body());
            if (!root.isArray() || root.isEmpty()) {
                return List.of();
            }
            List<CoinGeckoMarketData> out = new ArrayList<>(root.size());
            for (JsonNode item : root) {
                CoinGeckoMarketData parsed = parse(item);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
            return out;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("CoinGecko batch fetch interrupted symbols={} : {}", joined, ex.toString());
            throw new CoinGeckoRequestException(
                    CoinGeckoRequestException.Kind.INTERRUPTED, "request interrupted", ex);
        } catch (HttpTimeoutException ex) {
            log.warn("CoinGecko batch fetch timed out symbols={} : {}", joined, ex.toString());
            throw new CoinGeckoRequestException(
                    CoinGeckoRequestException.Kind.TIMEOUT, "request timed out", ex);
        } catch (CoinGeckoRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("CoinGecko batch fetch failed symbols={} : {}", joined, ex.toString());
            return List.of();
        }
    }

    CoinGeckoMarketData parse(JsonNode item) {
        if (item == null || !item.isObject()) {
            return null;
        }
        String id = textOrNull(item, "id");
        String symbol = textOrNull(item, "symbol");
        BigDecimal mcap = decimalOrNull(item, "market_cap");
        BigDecimal fdv = decimalOrNull(item, "fully_diluted_valuation");
        BigDecimal circ = decimalOrNull(item, "circulating_supply");
        BigDecimal total = decimalOrNull(item, "total_supply");
        BigDecimal max = decimalOrNull(item, "max_supply");
        Instant updated = instantOrNull(item, "last_updated");
        return new CoinGeckoMarketData(id, symbol, mcap, fdv, circ, total, max, updated);
    }

    private static String textOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static BigDecimal decimalOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return new BigDecimal(v.decimalValue().toPlainString());
    }

    private static Instant instantOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull() || !v.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(v.asText());
        } catch (Exception ex) {
            return null;
        }
    }
}

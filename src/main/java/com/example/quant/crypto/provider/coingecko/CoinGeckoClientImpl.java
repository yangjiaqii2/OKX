package com.example.quant.crypto.provider.coingecko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CoinGecko 市值客户端。先解析币种身份，再按 coin ID 批量查询行情。 */
public final class CoinGeckoClientImpl implements CoinGeckoHttpClient {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoClientImpl.class);
    private static final String DEMO_BASE_URL = "https://api.coingecko.com/api/v3";
    private static final String PRO_BASE_URL = "https://pro-api.coingecko.com/api/v3";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);

    private static final Duration CATALOG_TTL = Duration.ofHours(6);
    private static final Properties VERIFIED_IDS = loadVerifiedIds();

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String apiKeyHeader;
    private Map<String, String> coinIds = Map.of();
    private Instant catalogExpiresAt = Instant.MIN;

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
        Map<String, String> catalog = coinIds();
        Map<String, String> expectedSymbols = new LinkedHashMap<>();
        for (String base : baseSymbols) {
            if (base == null || base.isBlank()) {
                continue;
            }
            String symbol = base.toLowerCase(Locale.ROOT);
            String id = catalog.get(symbol);
            if (id != null) {
                expectedSymbols.put(id, symbol);
            }
        }
        if (expectedSymbols.isEmpty()) {
            return List.of();
        }

        String ids = URLEncoder.encode(String.join(",", expectedSymbols.keySet()), StandardCharsets.UTF_8);
        JsonNode root = requestArray("/coins/markets?vs_currency=usd&ids=" + ids);
        List<CoinGeckoMarketData> markets = new ArrayList<>(root.size());
        for (JsonNode item : root) {
            CoinGeckoMarketData data = parse(item);
            if (data != null && data.symbol() != null
                    && data.symbol().equalsIgnoreCase(expectedSymbols.get(data.id()))) {
                markets.add(data);
            }
        }
        return markets;
    }

    private synchronized Map<String, String> coinIds() {
        if (Instant.now().isBefore(catalogExpiresAt)) {
            return coinIds;
        }
        JsonNode catalog = requestArray("/coins/list");
        Map<String, Set<String>> idsBySymbol = new HashMap<>();
        for (JsonNode entry : catalog) {
            String symbol = entry.path("symbol").asText("");
            String id = entry.path("id").asText("");
            if (!symbol.isBlank() && !id.isBlank()) {
                idsBySymbol.computeIfAbsent(symbol.toLowerCase(Locale.ROOT), key -> new HashSet<>()).add(id);
            }
        }
        if (idsBySymbol.isEmpty()) {
            throw new CoinGeckoRequestException(CoinGeckoRequestException.Kind.UNAVAILABLE, "empty coin ID catalog");
        }
        Map<String, String> resolved = new HashMap<>();
        idsBySymbol.forEach((symbol, ids) -> {
            String verified = VERIFIED_IDS.getProperty(symbol);
            if (verified != null) {
                if (ids.contains(verified)) {
                    resolved.put(symbol, verified);
                }
            } else if (ids.size() == 1) {
                resolved.put(symbol, ids.iterator().next());
            }
        });
        coinIds = Map.copyOf(resolved);
        catalogExpiresAt = Instant.now().plus(CATALOG_TTL);
        return coinIds;
    }

    private JsonNode requestArray(String path) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "quant-assistant/1.0");
            if (apiKey != null) {
                rb.header(apiKeyHeader, apiKey);
            }
            HttpResponse<String> response = httpClient.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw new CoinGeckoRequestException(
                        CoinGeckoRequestException.Kind.RATE_LIMITED, "upstream rate limited");
            }
            if (response.statusCode() / 100 != 2) {
                throw new CoinGeckoRequestException(
                        CoinGeckoRequestException.Kind.UNAVAILABLE, "upstream HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.isArray()) {
                throw new CoinGeckoRequestException(
                        CoinGeckoRequestException.Kind.UNAVAILABLE, "invalid upstream response");
            }
            return root;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CoinGeckoRequestException(
                    CoinGeckoRequestException.Kind.INTERRUPTED, "request interrupted", ex);
        } catch (HttpTimeoutException ex) {
            throw new CoinGeckoRequestException(
                    CoinGeckoRequestException.Kind.TIMEOUT, "request timed out", ex);
        } catch (IOException ex) {
            log.warn("CoinGecko request failed path={} reason={}", path, ex.getClass().getSimpleName());
            throw new CoinGeckoRequestException(
                    CoinGeckoRequestException.Kind.UNAVAILABLE, "upstream I/O failure", ex);
        }
    }

    private static Properties loadVerifiedIds() {
        Properties ids = new Properties();
        try (var input = CoinGeckoClientImpl.class.getResourceAsStream("/coingecko-okx-coin-ids.properties")) {
            if (input == null) {
                throw new IllegalStateException("CoinGecko OKX coin ID mapping is missing");
            }
            ids.load(input);
            return ids;
        } catch (IOException ex) {
            throw new IllegalStateException("CoinGecko OKX coin ID mapping cannot be read", ex);
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

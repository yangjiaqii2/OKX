package com.example.quant.crypto;

import com.example.quant.account.OkxAccountBindingService;
import com.example.quant.config.OkxProperties;
import com.example.quant.okxtrade.OkxApiRejectedException;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OkxRestClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final OkxProperties okxProperties;
    private final OkxAccountBindingService bindingService;
    private final OkxSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private TradingMode tradingMode = TradingMode.DISABLED;
    private volatile long okxTimeOffsetMillis;
    private volatile long okxTimeOffsetExpiresAtMillis;

    @Autowired
    public OkxRestClient(OkxProperties okxProperties, OkxAccountBindingService bindingService,
                         OkxSigner signer, ObjectMapper objectMapper) {
        this(okxProperties, bindingService, signer, objectMapper,
                buildHttpClient(),
                TradingMode.DISABLED);
    }

    private static HttpClient buildHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT);
        ProxySelector proxySelector = resolveEnvProxySelector();
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        return builder.build();
    }

    private static ProxySelector resolveEnvProxySelector() {
        String raw = firstNonBlank(
                System.getenv("OKX_HTTP_PROXY"),
                System.getenv("HTTPS_PROXY"),
                System.getenv("https_proxy"),
                System.getenv("HTTP_PROXY"),
                System.getenv("http_proxy")
        );
        if (raw == null) {
            return null;
        }
        try {
            String normalized = raw.contains("://") ? raw : "http://" + raw;
            URI uri = URI.create(normalized);
            if (uri.getHost() != null && uri.getPort() > 0) {
                return ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort()));
            }
        } catch (IllegalArgumentException ignored) {
            // Fall back to default system proxy selector.
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    @Autowired
    void configureTradingMode(TradingProperties tradingProperties) {
        this.tradingMode = tradingProperties == null ? TradingMode.DISABLED : tradingProperties.mode();
    }

    OkxRestClient(OkxProperties okxProperties, OkxAccountBindingService bindingService,
                  OkxSigner signer, ObjectMapper objectMapper, HttpClient httpClient) {
        this(okxProperties, bindingService, signer, objectMapper, httpClient, TradingMode.LIVE);
    }

    OkxRestClient(OkxProperties okxProperties, OkxAccountBindingService bindingService,
                  OkxSigner signer, ObjectMapper objectMapper, HttpClient httpClient, TradingMode tradingMode) {
        this.okxProperties = okxProperties;
        this.bindingService = bindingService;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.tradingMode = tradingMode == null ? TradingMode.DISABLED : tradingMode;
    }

    public String status() {
        return "okx-rest-client-ready";
    }

    public boolean hasCredentials() {
        return credentials().isPresent();
    }

    /**
     * 仅供测试用 no-arg 构造器：所有字段为 null，生产代码不应调用。
     * 测试通过子类化并 override {@link #publicGet(String)} 来注入测试数据。
     */
    protected OkxRestClient() {
        this.okxProperties = null;
        this.bindingService = null;
        this.signer = null;
        this.objectMapper = null;
        this.httpClient = null;
        this.tradingMode = TradingMode.DISABLED;
    }

    public JsonNode publicGet(String requestPath) {
        return publicGet(requestPath, REQUEST_TIMEOUT);
    }

    public JsonNode publicGet(String requestPath, Duration timeout) {
        if (httpClient == null || okxProperties == null) {
            return publicGetWithoutHttpClient(requestPath);
        }
        String marketMode = System.getProperty("MARKET_DATA_MODE", System.getenv().getOrDefault("MARKET_DATA_MODE", "PUBLIC"));
        if ("DEMO".equalsIgnoreCase(marketMode)) {
            throw new IllegalStateException("MARKET_DATA_MODE=DEMO is unavailable for OKX public REST market data; refusing silent PUBLIC fallback");
        }
        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? REQUEST_TIMEOUT
                : timeout;
        return send(requestBuilder(requestPath, effectiveTimeout).GET().build());
    }

    protected JsonNode publicGetWithoutHttpClient(String requestPath) {
        throw new IllegalStateException("OKX REST client unavailable for path: " + requestPath);
    }

    public JsonNode privateGet(String requestPath) {
        OkxAccountBindingService.OkxCredentials credentials = credentials()
                .orElseThrow(() -> new IllegalStateException("OKX account is not bound"));
        String timestamp = okxTimestamp();
        String signature = signer.sign(timestamp, "GET", requestPath, "", credentials.secret());
        HttpRequest.Builder builder = requestBuilder(requestPath)
                .GET()
                .header("OK-ACCESS-KEY", credentials.apiKey())
                .header("OK-ACCESS-SIGN", signature)
                .header("OK-ACCESS-TIMESTAMP", timestamp)
                .header("OK-ACCESS-PASSPHRASE", credentials.passphrase());
        applyTradingMode(builder);
        return send(builder.build());
    }

    public JsonNode privatePost(String requestPath, Map<String, String> payload) {
        return privatePostJson(requestPath, payload);
    }

    public JsonNode privatePost(String requestPath, Object payload) {
        return privatePostJson(requestPath, payload);
    }

    private JsonNode privatePostJson(String requestPath, Object payload) {
        OkxAccountBindingService.OkxCredentials credentials = credentials()
                .orElseThrow(() -> new IllegalStateException("OKX account is not bound"));
        try {
            String body = objectMapper.writeValueAsString(payload);
            String timestamp = okxTimestamp();
            String signature = signer.sign(timestamp, "POST", requestPath, body, credentials.secret());
            HttpRequest.Builder builder = requestBuilder(requestPath)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .header("OK-ACCESS-KEY", credentials.apiKey())
                    .header("OK-ACCESS-SIGN", signature)
                    .header("OK-ACCESS-TIMESTAMP", timestamp)
                    .header("OK-ACCESS-PASSPHRASE", credentials.passphrase());
            applyTradingMode(builder);
            return send(builder.build());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("OKX request failed: " + ex.getMessage(), ex);
        }
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Optional<OkxAccountBindingService.OkxCredentials> credentials() {
        Optional<OkxAccountBindingService.OkxCredentials> bound = bindingService.credentials();
        if (bound.isPresent()) {
            return bound;
        }
        if (hasText(okxProperties.api().key()) && hasText(okxProperties.api().secret())
                && hasText(okxProperties.api().passphrase())) {
            return Optional.of(new OkxAccountBindingService.OkxCredentials(
                    okxProperties.api().key(),
                    okxProperties.api().secret(),
                    okxProperties.api().passphrase()
            ));
        }
        return Optional.empty();
    }

    private HttpRequest.Builder requestBuilder(String requestPath) {
        return requestBuilder(requestPath, REQUEST_TIMEOUT);
    }

    private HttpRequest.Builder requestBuilder(String requestPath, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(okxProperties.api().baseUrl() + requestPath))
                .header("User-Agent", "Mozilla/5.0 (compatible; QuantDesk/1.0)")
                .header("Accept", "application/json")
                .timeout(timeout == null || timeout.isZero() || timeout.isNegative() ? REQUEST_TIMEOUT : timeout);
    }

    private void applyTradingMode(HttpRequest.Builder builder) {
        if (tradingMode.demo()) {
            builder.header("x-simulated-trading", "1");
        }
    }

    private String okxTimestamp() {
        long localNowMillis = System.currentTimeMillis();
        long okxNowMillis = localNowMillis + okxTimeOffsetMillis(localNowMillis);
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(okxNowMillis));
    }

    private long okxTimeOffsetMillis(long localNowMillis) {
        if (localNowMillis < okxTimeOffsetExpiresAtMillis) {
            return okxTimeOffsetMillis;
        }
        synchronized (this) {
            long refreshedLocalNowMillis = System.currentTimeMillis();
            if (refreshedLocalNowMillis < okxTimeOffsetExpiresAtMillis) {
                return okxTimeOffsetMillis;
            }
            try {
                JsonNode data = publicGet("/api/v5/public/time").path("data");
                if (data.isArray() && !data.isEmpty()) {
                    long okxServerMillis = Long.parseLong(data.get(0).path("ts").asText());
                    long receivedLocalMillis = System.currentTimeMillis();
                    okxTimeOffsetMillis = okxServerMillis - receivedLocalMillis;
                    okxTimeOffsetExpiresAtMillis = receivedLocalMillis + 60_000L;
                }
            } catch (Exception ex) {
                okxTimeOffsetMillis = 0L;
                okxTimeOffsetExpiresAtMillis = refreshedLocalNowMillis + 10_000L;
            }
            return okxTimeOffsetMillis;
        }
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw httpFailure(response.statusCode(), response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String code = root.path("code").asText("0");
            if (!"0".equals(code)) {
                throw OkxApiRejectedException.fromResponse(root, okxErrorDetail(root));
            }
            return root;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OKX request interrupted", ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("OKX request failed: " + describeException(ex), ex);
        }
    }

    private static String describeException(Throwable ex) {
        if (ex == null) {
            return "unknown error";
        }
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String type = root.getClass().getSimpleName();
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = ex.getMessage();
        }
        return (msg == null || msg.isBlank()) ? type : (type + ": " + msg);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private IllegalStateException httpFailure(int status, String body) {
        String prefix = "OKX HTTP " + status;
        if (body == null || body.isBlank()) return new IllegalStateException(prefix);
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = prefix + ": " + okxErrorDetail(root);
            var failure = OkxApiRejectedException.fromResponse(root, message);
            if (failure instanceof OkxApiRejectedException.DuplicateClientOrderIdException) return failure;
            return new IllegalStateException(message);
        } catch (Exception ignored) {
            return new IllegalStateException(prefix + ": " + compact(body));
        }
    }

    private static String okxErrorDetail(JsonNode root) {
        String code = root.path("code").asText("");
        String msg = root.path("msg").asText("");
        List<String> details = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                String sCode = item.path("sCode").asText("");
                String sMsg = item.path("sMsg").asText("");
                if (hasText(sCode) || hasText(sMsg)) {
                    details.add("sCode=" + valueOrDash(sCode) + ", sMsg=" + valueOrDash(sMsg));
                }
            }
        }
        StringBuilder message = new StringBuilder("OKX");
        if (hasText(code)) {
            message.append(" code=").append(code);
        }
        if (hasText(msg)) {
            message.append(", msg=").append(msg);
        }
        if (!details.isEmpty()) {
            message.append(", details=[").append(String.join("; ", details)).append("]");
        }
        return message.toString();
    }

    private static String valueOrDash(String value) {
        return hasText(value) ? compact(value) : "-";
    }

    private static String compact(String body) {
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.substring(0, Math.min(300, compact.length()));
    }
}

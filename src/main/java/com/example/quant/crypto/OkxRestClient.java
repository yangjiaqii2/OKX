package com.example.quant.crypto;

import com.example.quant.account.OkxAccountBindingService;
import com.example.quant.config.OkxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
    private final OkxProperties okxProperties;
    private final OkxAccountBindingService bindingService;
    private final OkxSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile long okxTimeOffsetMillis;
    private volatile long okxTimeOffsetExpiresAtMillis;

    @Autowired
    public OkxRestClient(OkxProperties okxProperties, OkxAccountBindingService bindingService,
                         OkxSigner signer, ObjectMapper objectMapper) {
        this(okxProperties, bindingService, signer, objectMapper, HttpClient.newHttpClient());
    }

    OkxRestClient(OkxProperties okxProperties, OkxAccountBindingService bindingService,
                  OkxSigner signer, ObjectMapper objectMapper, HttpClient httpClient) {
        this.okxProperties = okxProperties;
        this.bindingService = bindingService;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public String status() {
        return "okx-rest-client-ready";
    }

    public boolean hasCredentials() {
        return credentials().isPresent();
    }

    public JsonNode publicGet(String requestPath) {
        return send(HttpRequest.newBuilder(URI.create(okxProperties.api().baseUrl() + requestPath)).GET().build());
    }

    public JsonNode privateGet(String requestPath) {
        OkxAccountBindingService.OkxCredentials credentials = credentials()
                .orElseThrow(() -> new IllegalStateException("OKX account is not bound"));
        String timestamp = okxTimestamp();
        String signature = signer.sign(timestamp, "GET", requestPath, "", credentials.secret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(okxProperties.api().baseUrl() + requestPath))
                .GET()
                .header("OK-ACCESS-KEY", credentials.apiKey())
                .header("OK-ACCESS-SIGN", signature)
                .header("OK-ACCESS-TIMESTAMP", timestamp)
                .header("OK-ACCESS-PASSPHRASE", credentials.passphrase())
                .build();
        return send(request);
    }

    public JsonNode privatePost(String requestPath, Map<String, String> payload) {
        OkxAccountBindingService.OkxCredentials credentials = credentials()
                .orElseThrow(() -> new IllegalStateException("OKX account is not bound"));
        try {
            String body = objectMapper.writeValueAsString(payload);
            String timestamp = okxTimestamp();
            String signature = signer.sign(timestamp, "POST", requestPath, body, credentials.secret());
            HttpRequest request = HttpRequest.newBuilder(URI.create(okxProperties.api().baseUrl() + requestPath))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .header("OK-ACCESS-KEY", credentials.apiKey())
                    .header("OK-ACCESS-SIGN", signature)
                    .header("OK-ACCESS-TIMESTAMP", timestamp)
                    .header("OK-ACCESS-PASSPHRASE", credentials.passphrase())
                    .build();
            return send(request);
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
                throw new IllegalStateException("OKX HTTP " + response.statusCode() + okxResponseMessage(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String code = root.path("code").asText("0");
            if (!"0".equals(code)) {
                throw new IllegalStateException(okxErrorDetail(root));
            }
            return root;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OKX request interrupted", ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("OKX request failed: " + ex.getMessage(), ex);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String okxResponseMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return ": " + okxErrorDetail(root);
        } catch (Exception ignored) {
            return ": " + compact(body);
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

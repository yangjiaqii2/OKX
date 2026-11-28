package com.example.quant.ai;

import com.example.quant.config.AiProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AiAnalysisService(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public AiAnalysisResult explain(String summary) {
        return new AiAnalysisResult(
                "当前结论仅供观察和风控复核。",
                "谨慎观察",
                "WATCH",
                summary,
                List.of("市场波动风险", "数据源延迟风险", "模型解释不构成投资建议"),
                "数据、趋势和风控条件继续一致。",
                "风险等级升高或关键价格条件失效。",
                "外部行情、新闻和模型输出均存在不确定性。"
        );
    }

    public JsonNode completeJson(String systemPrompt, String userPrompt) {
        if (!aiProperties.enabled()) {
            throw new IllegalStateException("AI分析未启用，请开启 ai.enabled");
        }
        if (!hasText(aiProperties.apiKey())) {
            throw new IllegalStateException("AI_API_KEY 未配置，无法生成AI交易计划");
        }
        if (!hasText(aiProperties.baseUrl())) {
            throw new IllegalStateException("AI_BASE_URL 未配置");
        }
        String model = hasText(aiProperties.model()) ? aiProperties.model() : "moonshot-v1-8k";
        return completeJson(new LlmRuntimeConfig(aiProperties.baseUrl(), aiProperties.apiKey(), model), systemPrompt, userPrompt);
    }

    public JsonNode completeJson(LlmRuntimeConfig config, String systemPrompt, String userPrompt) {
        if (config == null || !hasText(config.apiKey()) || !hasText(config.baseUrl()) || !hasText(config.modelName())) {
            throw new IllegalStateException("大模型 API 配置不完整，请先配置 Base URL、模型名称与 API Key");
        }
        String requestId = UUID.randomUUID().toString();
        URI endpoint = URI.create(chatCompletionsUrl(config.baseUrl()));
        long startedAt = System.nanoTime();
        try {
            Map<String, Object> payload = Map.of(
                    "model", config.modelName().trim(),
                    "temperature", 0.1,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );
            String body = objectMapper.writeValueAsString(payload);
            log.info("AI request started requestId={} endpoint={} model={} payloadChars={}",
                    requestId, endpoint, config.modelName().trim(), body.length());
            HttpResponse<String> response = sendWithRetry(body, endpoint, config.apiKey().trim());
            log.info("AI response received requestId={} httpStatus={} responseChars={} elapsedMs={}",
                    requestId, response.statusCode(), response.body() == null ? 0 : response.body().length(),
                    elapsedMs(startedAt));
            JsonNode result = parseAiJson(response);
            log.info("AI request completed requestId={} httpStatus={} elapsedMs={}",
                    requestId, response.statusCode(), elapsedMs(startedAt));
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("AI request failed requestId={} stage=HTTP reason=INTERRUPTED elapsedMs={}",
                    requestId, elapsedMs(startedAt));
            throw new IllegalStateException("AI request interrupted", ex);
        } catch (IllegalStateException ex) {
            log.warn("AI request failed requestId={} stage=HTTP_OR_PARSE reason={} elapsedMs={}",
                    requestId, compact(ex.getMessage()), elapsedMs(startedAt));
            throw ex;
        } catch (Exception ex) {
            log.warn("AI request failed requestId={} stage=SERIALIZE_OR_HTTP reason={} elapsedMs={}",
                    requestId, compact(ex.getMessage()), elapsedMs(startedAt));
            throw new IllegalStateException("AI request failed: " + ex.getMessage(), ex);
        }
    }

    public LlmConnectionTestResult testConnection(LlmRuntimeConfig config) {
        if (config == null || !hasText(config.apiKey()) || !hasText(config.baseUrl()) || !hasText(config.modelName())) {
            return new LlmConnectionTestResult(false, "", 0L, "大模型 API 配置不完整");
        }
        long startedAt = System.nanoTime();
        String model = config.modelName().trim();
        try {
            URI endpoint = URI.create(chatCompletionsUrl(config.baseUrl()));
            Map<String, Object> payload = Map.of(
                    "model", model,
                    "temperature", 0,
                    "max_tokens", 32,
                    "messages", List.of(
                            Map.of("role", "system", "content", "Return only valid JSON: {\"ok\":true}"),
                            Map.of("role", "user", "content", "ping")
                    )
            );
            String body = objectMapper.writeValueAsString(payload);
            HttpResponse<String> response = sendWithRetry(body, endpoint, config.apiKey().trim());
            long elapsed = elapsedMs(startedAt);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new LlmConnectionTestResult(
                        false,
                        model,
                        elapsed,
                        "接口返回 HTTP " + response.statusCode() + ": " + compact(response.body())
                );
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (!hasText(content)) {
                return new LlmConnectionTestResult(false, model, elapsed, "模型返回空响应");
            }
            return new LlmConnectionTestResult(true, model, elapsed, "连通性验证通过");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new LlmConnectionTestResult(false, model, elapsedMs(startedAt), "连通性测试被中断");
        } catch (Exception ex) {
            return new LlmConnectionTestResult(false, model, elapsedMs(startedAt), compact(ex.getMessage()));
        }
    }

    /**
     * Calls TypeSafe System One with typed Choice/Score/Noul questions.
     * The raw structured response is returned so the caller can apply its own
     * deterministic validation and trading gates.
     */
    public JsonNode completeTyped(Object state, Map<String, Object> questions) {
        if (!aiProperties.enabled()) {
            throw new IllegalStateException("AI分析未启用，请开启 ai.enabled");
        }
        if (!aiProperties.isTypeSafe()) {
            throw new IllegalStateException("当前 AI_PROVIDER 不是 TYPESAFE");
        }
        if (!hasText(aiProperties.effectiveTypeSafeApiKey())) {
            throw new IllegalStateException("TYPESAFE_API_KEY 未配置，无法调用 Jev");
        }
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("TypeSafe questions 不能为空");
        }
        String requestId = UUID.randomUUID().toString();
        URI endpoint = URI.create(typeSafeSystemOneUrl(aiProperties.effectiveTypeSafeBaseUrl()));
        long startedAt = System.nanoTime();
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("state", state);
            payload.put("model", aiProperties.effectiveTypeSafeModel());
            payload.put("questions", questions);
            String body = objectMapper.writeValueAsString(payload);
            log.info("TypeSafe request started requestId={} endpoint={} model={} questionCount={} payloadChars={}",
                    requestId, endpoint, aiProperties.effectiveTypeSafeModel(), questions.size(), body.length());
            HttpResponse<String> response = sendWithRetry(body, endpoint, aiProperties.effectiveTypeSafeApiKey());
            log.info("TypeSafe response received requestId={} httpStatus={} responseChars={} elapsedMs={}",
                    requestId, response.statusCode(), response.body() == null ? 0 : response.body().length(),
                    elapsedMs(startedAt));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("TypeSafe request failed HTTP " + response.statusCode()
                        + ": " + compact(response.body()));
            }
            JsonNode result;
            try {
                result = objectMapper.readTree(response.body());
            } catch (JsonProcessingException ex) {
                throw new InvalidAiResponse("TypeSafe request failed: " + ex.getMessage(), null);
            }
            if (result == null || !result.isObject() || !result.has("answers")) {
                throw new InvalidAiResponse("TypeSafe响应缺少 answers", result);
            }
            log.info("TypeSafe request completed requestId={} httpStatus={} elapsedMs={}",
                    requestId, response.statusCode(), elapsedMs(startedAt));
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("TypeSafe request failed requestId={} stage=HTTP reason=INTERRUPTED elapsedMs={}",
                    requestId, elapsedMs(startedAt));
            throw new IllegalStateException("TypeSafe request interrupted", ex);
        } catch (IllegalStateException ex) {
            log.warn("TypeSafe request failed requestId={} stage=HTTP_OR_PARSE reason={} elapsedMs={}",
                    requestId, compact(ex.getMessage()), elapsedMs(startedAt));
            throw ex;
        } catch (Exception ex) {
            log.warn("TypeSafe request failed requestId={} stage=SERIALIZE_OR_HTTP reason={} elapsedMs={}",
                    requestId, compact(ex.getMessage()), elapsedMs(startedAt));
            throw new IllegalStateException("TypeSafe request failed: " + ex.getMessage(), ex);
        }
    }

    public boolean isTypeSafeProvider() {
        return aiProperties.isTypeSafe();
    }

    public String selectionModel(boolean typeSafe) {
        return typeSafe ? aiProperties.effectiveTypeSafeModel()
                : hasText(aiProperties.model()) ? aiProperties.model() : "moonshot-v1-8k";
    }

    public static final class InvalidAiResponse extends IllegalStateException {
        private final JsonNode response;

        InvalidAiResponse(String message, JsonNode response) {
            super(message);
            this.response = response;
        }

        public JsonNode response() {
            return response;
        }
    }

    private HttpResponse<String> sendWithRetry(String body, URI endpoint, String apiKey) throws Exception {
        int maxAttempts = aiProperties.effectiveMaxRetries() + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(aiProperties.effectiveRequestTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException ex) {
                if (attempt >= maxAttempts) {
                    throw new IllegalStateException("AI request timed out after "
                            + aiProperties.effectiveRequestTimeoutSeconds()
                            + "s, attempts=" + maxAttempts, ex);
                }
            }
        }
        throw new IllegalStateException("AI request failed before sending");
    }

    private static long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private JsonNode parseAiJson(HttpResponse<String> response) throws Exception {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() == 401) {
                throw new IllegalStateException("AI鉴权失败：请确认 AI_API_KEY 来自 Moonshot/Kimi 开放平台，且 AI_BASE_URL 与模型匹配");
            }
            throw new IllegalStateException("AI request failed HTTP " + response.statusCode() + ": " + compact(response.body()));
        }
        JsonNode root = null;
        try {
            root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (!hasText(content)) {
                throw new IllegalStateException("AI响应为空");
            }
            return objectMapper.readTree(extractJson(content));
        } catch (JsonProcessingException ex) {
            throw new InvalidAiResponse("AI request failed: " + ex.getMessage(), root);
        } catch (IllegalStateException ex) {
            throw new InvalidAiResponse(ex.getMessage(), root);
        }
    }

    private static String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        throw new IllegalStateException("AI响应不是JSON: " + compact(trimmed));
    }

    private static String chatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1") || normalized.endsWith("/coding/v1")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    private static String typeSafeSystemOneUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/v1/systemone")) {
            return normalized;
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/systemone";
        }
        return normalized + "/v1/systemone";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String compact(String body) {
        if (body == null) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.substring(0, Math.min(300, compact.length()));
    }
}

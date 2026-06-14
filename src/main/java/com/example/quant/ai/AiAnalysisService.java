package com.example.quant.ai;

import com.example.quant.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
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
        try {
            Map<String, Object> payload = Map.of(
                    "model", hasText(aiProperties.model()) ? aiProperties.model() : "moonshot-v1-8k",
                    "temperature", 0.2,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(chatCompletionsUrl(aiProperties.baseUrl())))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiProperties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) {
                    throw new IllegalStateException("AI鉴权失败：请确认 AI_API_KEY 来自 Moonshot/Kimi 开放平台，且 AI_BASE_URL 与模型匹配");
                }
                throw new IllegalStateException("AI request failed HTTP " + response.statusCode() + ": " + compact(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (!hasText(content)) {
                throw new IllegalStateException("AI响应为空");
            }
            return objectMapper.readTree(extractJson(content));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI request interrupted", ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("AI request failed: " + ex.getMessage(), ex);
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

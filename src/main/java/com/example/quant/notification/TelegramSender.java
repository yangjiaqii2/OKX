package com.example.quant.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class TelegramSender {
    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiBase;

    @Autowired
    public TelegramSender(NotificationProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
                "https://api.telegram.org");
    }

    TelegramSender(NotificationProperties properties, ObjectMapper objectMapper, HttpClient httpClient, String apiBase) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.apiBase = apiBase;
    }

    public TelegramSendResult send(String text) {
        NotificationProperties.Telegram telegram = properties.telegram();
        if (!properties.enabled() || !telegram.configured()) {
            return TelegramSendResult.failed(0, false, Duration.ZERO, "TELEGRAM_NOT_CONFIGURED");
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "chat_id", telegram.chatId(), "text", text, "parse_mode", "HTML",
                    "disable_web_page_preview", true));
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/bot" + telegram.botToken() + "/sendMessage"))
                    .timeout(Duration.ofSeconds(telegram.sendTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json;
            try {
                json = objectMapper.readTree(response.body());
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                return TelegramSendResult.failed(response.statusCode(), retryable, Duration.ZERO, "MALFORMED_RESPONSE");
            }
            if (response.statusCode() / 100 == 2 && json.path("ok").asBoolean(false)) {
                return TelegramSendResult.sent(response.statusCode());
            }
            boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
            long retrySeconds = json.path("parameters").path("retry_after").asLong(0);
            String error = compact(json.path("description").asText("HTTP " + response.statusCode()));
            return TelegramSendResult.failed(response.statusCode(), retryable,
                    Duration.ofSeconds(Math.max(0, retrySeconds)), error);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return TelegramSendResult.failed(0, true, Duration.ZERO, "INTERRUPTED");
        } catch (Exception ex) {
            return TelegramSendResult.failed(0, true, Duration.ZERO, compact(ex.getMessage()));
        }
    }

    private static String compact(String value) {
        if (value == null) return "UNKNOWN";
        String safe = value.replaceAll("(?i)bot[0-9]+:[A-Za-z0-9_-]+", "bot[REDACTED]")
                .replaceAll("\\s+", " ").trim();
        return safe.substring(0, Math.min(300, safe.length()));
    }
}

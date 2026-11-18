package com.example.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai")
public record AiProperties(
        boolean enabled,
        String provider,
        String model,
        String apiKey,
        String baseUrl,
        int requestTimeoutSeconds,
        int maxRetries,
        String typesafeApiKey,
        String typesafeBaseUrl,
        String typesafeModel
) {
    public int effectiveRequestTimeoutSeconds() {
        return requestTimeoutSeconds > 0 ? requestTimeoutSeconds : 20;
    }

    public int effectiveMaxRetries() {
        return Math.max(0, maxRetries);
    }

    public boolean isTypeSafe() {
        return "TYPESAFE".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    public String effectiveTypeSafeApiKey() {
        return typesafeApiKey == null ? "" : typesafeApiKey.trim();
    }

    public String effectiveTypeSafeBaseUrl() {
        return hasText(typesafeBaseUrl) ? typesafeBaseUrl.trim() : "https://api.typesafe.ai";
    }

    public String effectiveTypeSafeModel() {
        return hasText(typesafeModel) ? typesafeModel.trim() : "jev-latest";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

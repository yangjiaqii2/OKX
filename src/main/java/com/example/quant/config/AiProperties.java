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
        int maxRetries
) {
    public int effectiveRequestTimeoutSeconds() {
        return requestTimeoutSeconds > 0 ? requestTimeoutSeconds : 20;
    }

    public int effectiveMaxRetries() {
        return Math.max(0, maxRetries);
    }
}

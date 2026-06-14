package com.example.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai")
public record AiProperties(boolean enabled, String provider, String model, String apiKey, String baseUrl) {
}

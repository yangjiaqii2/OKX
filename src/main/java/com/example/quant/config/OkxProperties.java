package com.example.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "okx")
public record OkxProperties(Api api, Websocket websocket) {
    public record Api(String baseUrl, String key, String secret, String passphrase) {
    }

    public record Websocket(
            String publicUrl,
            String privateUrl,
            long reconnectDelayMs,
            long heartbeatIntervalMs,
            long maxMarketDelayMs
    ) {
    }
}

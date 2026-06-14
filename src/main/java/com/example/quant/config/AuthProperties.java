package com.example.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quant.auth")
public record AuthProperties(
        String username,
        String password,
        int sessionTtlMinutes
) {
    public String effectiveUsername() {
        return hasText(username) ? username : "admin";
    }

    public String effectivePassword() {
        return hasText(password) ? password : "admin123";
    }

    public int effectiveSessionTtlMinutes() {
        return sessionTtlMinutes > 0 ? sessionTtlMinutes : 480;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

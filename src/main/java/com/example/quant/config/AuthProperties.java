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
        if (!hasText(password)) {
            throw new IllegalStateException("首次启动必须配置QUANT_AUTH_PASSWORD");
        }
        return password;
    }

    public int effectiveSessionTtlMinutes() {
        return sessionTtlMinutes > 0 ? sessionTtlMinutes : 480;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

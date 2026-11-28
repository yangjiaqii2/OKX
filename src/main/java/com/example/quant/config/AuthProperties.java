package com.example.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "quant.auth")
public record AuthProperties(
        String username,
        String password,
        int sessionTtlMinutes,
        Boolean registrationEnabled,
        String inviteCode
) {
    @ConstructorBinding
    public AuthProperties {
    }

    public AuthProperties(String username, String password, int sessionTtlMinutes) {
        this(username, password, sessionTtlMinutes, true, "");
    }

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

    public boolean effectiveRegistrationEnabled() {
        return registrationEnabled == null || registrationEnabled;
    }

    public String effectiveInviteCode() {
        return hasText(inviteCode) ? inviteCode.trim() : "";
    }

    public boolean isInviteCodeRequired() {
        return !effectiveInviteCode().isEmpty();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

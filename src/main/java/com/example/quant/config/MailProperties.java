package com.example.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "quant.mail")
public record MailProperties(
        String provider,
        String fromAddress,
        String smtpHost,
        int smtpPort,
        String smtpUsername,
        String smtpPassword,
        Boolean smtpSsl,
        String resendApiKey,
        Boolean exposeDevCode
) {
    @ConstructorBinding
    public MailProperties {
    }

    public static MailProperties defaults() {
        return new MailProperties("DEV_LOG", "noreply@quantdesk.local", "", 465, "", "", true, "", true);
    }

    public String effectiveProvider() {
        return hasText(provider) ? provider.trim().toUpperCase() : "DEV_LOG";
    }

    public String effectiveFromAddress() {
        return hasText(fromAddress) ? fromAddress.trim() : "noreply@quantdesk.local";
    }

    public boolean effectiveSmtpSsl() {
        return smtpSsl == null || smtpSsl;
    }

    public boolean effectiveExposeDevCode() {
        return exposeDevCode == null || exposeDevCode;
    }

    private static boolean hasText(String v) {
        return v != null && !v.trim().isEmpty();
    }
}

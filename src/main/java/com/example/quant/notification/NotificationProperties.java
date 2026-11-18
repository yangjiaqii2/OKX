package com.example.quant.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {
    private boolean enabled;
    private Telegram telegram = new Telegram();

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Telegram telegram() { return telegram; }
    public void setTelegram(Telegram telegram) { this.telegram = telegram == null ? new Telegram() : telegram; }

    public static class Telegram {
        private boolean enabled;
        private String botToken = "";
        private String chatId = "";
        private int sendTimeoutSeconds = 8;
        private int maxAttempts = 5;

        public boolean enabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String botToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
        public String chatId() { return chatId; }
        public void setChatId(String chatId) { this.chatId = chatId; }
        public int sendTimeoutSeconds() { return Math.max(1, sendTimeoutSeconds); }
        public void setSendTimeoutSeconds(int sendTimeoutSeconds) { this.sendTimeoutSeconds = sendTimeoutSeconds; }
        public int maxAttempts() { return Math.max(1, maxAttempts); }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public boolean configured() { return enabled && !botToken.isBlank() && !chatId.isBlank(); }
    }
}

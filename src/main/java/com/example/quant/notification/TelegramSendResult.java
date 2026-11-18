package com.example.quant.notification;

import java.time.Duration;

public record TelegramSendResult(boolean sent, boolean retryable, int httpStatus, Duration retryAfter, String error) {
    static TelegramSendResult sent(int status) { return new TelegramSendResult(true, false, status, Duration.ZERO, null); }
    static TelegramSendResult failed(int status, boolean retryable, Duration retryAfter, String error) {
        return new TelegramSendResult(false, retryable, status, retryAfter == null ? Duration.ZERO : retryAfter, error);
    }
}

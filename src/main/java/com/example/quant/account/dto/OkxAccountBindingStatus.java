package com.example.quant.account.dto;

public record OkxAccountBindingStatus(
        boolean bound,
        String maskedApiKey,
        boolean liveTradingEnabled
) {
    public static OkxAccountBindingStatus unbound(boolean liveTradingEnabled) {
        return new OkxAccountBindingStatus(false, "", liveTradingEnabled);
    }
}

package com.example.quant.account.dto;

import com.example.quant.config.TradingMode;

public record OkxAccountBindingStatus(
        boolean bound,
        String maskedApiKey,
        TradingMode tradingMode
) {
    public static OkxAccountBindingStatus unbound(TradingMode tradingMode) {
        return new OkxAccountBindingStatus(false, "", tradingMode);
    }
}

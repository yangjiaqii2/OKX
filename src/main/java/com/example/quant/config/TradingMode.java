package com.example.quant.config;

public enum TradingMode {
    DISABLED,
    DEMO,
    LIVE;

    public boolean writesEnabled() {
        return this == DEMO || this == LIVE;
    }

    public boolean demo() {
        return this == DEMO;
    }
}

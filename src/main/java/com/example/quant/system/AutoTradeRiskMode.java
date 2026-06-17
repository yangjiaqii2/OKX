package com.example.quant.system;

public enum AutoTradeRiskMode {
    STRICT,
    NO_RISK;

    public boolean noRisk() {
        return this == NO_RISK;
    }
}

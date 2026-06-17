package com.example.quant.crypto.dto;

public record ContractNewsRiskDecision(
        boolean allowTrade,
        boolean forceNoTrade,
        boolean downgradeToWait,
        String reason
) {
    public static ContractNewsRiskDecision allow(String reason) {
        return new ContractNewsRiskDecision(true, false, false, reason);
    }

    public static ContractNewsRiskDecision waitOnly(String reason) {
        return new ContractNewsRiskDecision(false, false, true, reason);
    }

    public static ContractNewsRiskDecision noTrade(String reason) {
        return new ContractNewsRiskDecision(false, true, false, reason);
    }
}

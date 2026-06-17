package com.example.quant.crypto.dto;

import java.math.BigDecimal;

public record ContractKlineAnalysis(
        String trend20m,
        String trend1h,
        String trend4h,
        String emaState20m,
        String macdState20m,
        BigDecimal rsi20m,
        BigDecimal adx20m,
        String structure,
        String entryTiming5m,
        BigDecimal volumeRatio20m,
        BigDecimal atrPct20m,
        BigDecimal distanceFromEma20Pct,
        boolean twentyMinuteStructureClear
) {
    public static ContractKlineAnalysis unavailable(String reason) {
        return new ContractKlineAnalysis(
                "NEUTRAL",
                "NEUTRAL",
                "NEUTRAL",
                "MIXED",
                "NEUTRAL",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                reason == null || reason.isBlank() ? "RANGE" : reason,
                "NOT_READY",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false
        );
    }
}

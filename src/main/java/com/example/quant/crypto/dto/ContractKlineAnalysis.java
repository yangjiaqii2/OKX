package com.example.quant.crypto.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
        boolean twentyMinuteStructureClear,
        Instant decisionCandleTimestamp,
        Integer analysisScore,
        String analysisAction,
        List<ContractCandle> recentClosed5mCandles
) {
    public ContractKlineAnalysis {
        recentClosed5mCandles = recentClosed5mCandles == null ? List.of() : List.copyOf(recentClosed5mCandles);
    }

    public ContractKlineAnalysis(
            String trend20m, String trend1h, String trend4h, String emaState20m, String macdState20m,
            BigDecimal rsi20m, BigDecimal adx20m, String structure, String entryTiming5m,
            BigDecimal volumeRatio20m, BigDecimal atrPct20m, BigDecimal distanceFromEma20Pct,
            boolean twentyMinuteStructureClear, Instant decisionCandleTimestamp, Integer analysisScore,
            String analysisAction
    ) {
        this(trend20m, trend1h, trend4h, emaState20m, macdState20m, rsi20m, adx20m, structure,
                entryTiming5m, volumeRatio20m, atrPct20m, distanceFromEma20Pct, twentyMinuteStructureClear,
                decisionCandleTimestamp, analysisScore, analysisAction, List.of());
    }

    public ContractKlineAnalysis(
            String trend20m, String trend1h, String trend4h, String emaState20m, String macdState20m,
            BigDecimal rsi20m, BigDecimal adx20m, String structure, String entryTiming5m,
            BigDecimal volumeRatio20m, BigDecimal atrPct20m, BigDecimal distanceFromEma20Pct,
            boolean twentyMinuteStructureClear, Instant decisionCandleTimestamp
    ) {
        this(trend20m, trend1h, trend4h, emaState20m, macdState20m, rsi20m, adx20m, structure,
                entryTiming5m, volumeRatio20m, atrPct20m, distanceFromEma20Pct,
                twentyMinuteStructureClear, decisionCandleTimestamp, null, null);
    }

    public ContractKlineAnalysis(
            String trend20m, String trend1h, String trend4h, String emaState20m, String macdState20m,
            BigDecimal rsi20m, BigDecimal adx20m, String structure, String entryTiming5m,
            BigDecimal volumeRatio20m, BigDecimal atrPct20m, BigDecimal distanceFromEma20Pct,
            boolean twentyMinuteStructureClear
    ) {
        this(trend20m, trend1h, trend4h, emaState20m, macdState20m, rsi20m, adx20m, structure,
                entryTiming5m, volumeRatio20m, atrPct20m, distanceFromEma20Pct,
                twentyMinuteStructureClear, null);
    }

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
                false,
                null
        );
    }

    public ContractKlineAnalysis withDecisionCandleTimestamp(Instant timestamp) {
        return new ContractKlineAnalysis(
                trend20m, trend1h, trend4h, emaState20m, macdState20m, rsi20m, adx20m, structure,
                entryTiming5m, volumeRatio20m, atrPct20m, distanceFromEma20Pct,
                twentyMinuteStructureClear, timestamp, analysisScore, analysisAction, recentClosed5mCandles);
    }

    public ContractKlineAnalysis withAnalysisDecision(int score, String action) {
        return new ContractKlineAnalysis(
                trend20m, trend1h, trend4h, emaState20m, macdState20m, rsi20m, adx20m, structure,
                entryTiming5m, volumeRatio20m, atrPct20m, distanceFromEma20Pct,
                twentyMinuteStructureClear, decisionCandleTimestamp, score, action, recentClosed5mCandles);
    }
}

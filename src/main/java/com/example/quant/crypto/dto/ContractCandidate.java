package com.example.quant.crypto.dto;

import com.example.quant.crypto.provider.dto.FundingHistorySnapshot;
import com.example.quant.crypto.provider.dto.OpenInterestHistorySnapshot;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 合约候选。
 *
 * <p>canonical 48 参（含 5 个 V2 Universe / Market Quality 字段 + 2 个 Wave 2 Derivatives 字段）。
 * 显式定义 30 参 / 41 参 / 46 参 overload 构造器，新字段传 null，自动适配所有现有调用点。
 *
 * <p>V2 字段：{@code marketCapUsd / fdvUsd / circulationRatio / volumeMcapRatio / marketQualityNote}。
 * V2 阶段由 {@code OkxContractScanner#analyzeSnapshotV2} 通过 {@link #withV2MarketQuality} 注入。
 *
 * <p>Wave 2 字段：{@code openInterestHistory / fundingHistory}。
 * V2 阶段由 {@code OkxContractScanner#analyzeSnapshotV2} 通过 {@link #withDerivatives} 注入。
 *
 * <p><b>设计原则：</b>聚合 Snapshot，不扩散 primitive。OI Direction 不进入 candidate（由聚合层
 * 临时用 {@code OIDirection.classify(priceChange24h, oiChange24h)} 计算，强度由 snapshot
 * 内部的 {@code oiChange1h / 4h / 24h} 数值字段体现）。
 */
public record ContractCandidate(
        MarketType marketType,
        String instId,
        String baseCurrency,
        String quoteCurrency,
        BigDecimal lastPrice,
        BigDecimal changePercent24h,
        BigDecimal changePercent5m,
        BigDecimal volume24h,
        BigDecimal volumeSpikeRatio,
        BigDecimal fundingRate,
        BigDecimal openInterest,
        BigDecimal openInterestChange,
        DirectionBias trendDirection,
        BigDecimal volatility,
        int score,
        ContractFactorScore factorScore,
        BigDecimal entryPrice,
        BigDecimal stopLossPrice,
        BigDecimal takeProfitPrice,
        int suggestedLeverage,
        BigDecimal riskRewardRatio,
        BigDecimal spreadBps,
        BigDecimal bidDepthUsdt,
        BigDecimal askDepthUsdt,
        BigDecimal estimatedSlippageBps,
        DirectionBias btcTrend,
        DirectionBias ethTrend,
        RiskLevel marketRiskLevel,
        List<String> candidateReasonList,
        List<String> riskTagList,
        Instant createdAt,
        ContractSignalType signalType,
        String action,
        String entryType,
        BigDecimal todayChangePct,
        BigDecimal atrPct20m,
        BigDecimal stopLossPct,
        BigDecimal finalRankScore,
        ContractScoreBreakdown scoreBreakdown,
        ContractKlineAnalysis klineAnalysis,
        ContractNewsRiskAnalysis newsAnalysis,
        // ---- V2 Universe / Market Quality ----
        BigDecimal marketCapUsd,
        BigDecimal fdvUsd,
        BigDecimal circulationRatio,
        BigDecimal volumeMcapRatio,
        String marketQualityNote,
        // ---- Wave 2 Derivatives (OI / Funding History Snapshot) ----
        OpenInterestHistorySnapshot openInterestHistory,
         FundingHistorySnapshot fundingHistory,
         com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot relativeStrength,
         com.example.quant.crypto.provider.dto.AthBreakoutSnapshot athBreakout,
         BigDecimal openInterestUsd
) {
    /**
     * 30 参 overload（向后兼容）：V2 5 字段 + Wave 2 2 字段传 null + 中间 11 字段（signalType..newsAnalysis）派生。
     */
    public ContractCandidate(
            MarketType marketType,
            String instId,
            String baseCurrency,
            String quoteCurrency,
            BigDecimal lastPrice,
            BigDecimal changePercent24h,
            BigDecimal changePercent5m,
            BigDecimal volume24h,
            BigDecimal volumeSpikeRatio,
            BigDecimal fundingRate,
            BigDecimal openInterest,
            BigDecimal openInterestChange,
            DirectionBias trendDirection,
            BigDecimal volatility,
            int score,
            ContractFactorScore factorScore,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            BigDecimal takeProfitPrice,
            int suggestedLeverage,
            BigDecimal riskRewardRatio,
            BigDecimal spreadBps,
            BigDecimal bidDepthUsdt,
            BigDecimal askDepthUsdt,
            BigDecimal estimatedSlippageBps,
            DirectionBias btcTrend,
            DirectionBias ethTrend,
            RiskLevel marketRiskLevel,
            List<String> candidateReasonList,
            List<String> riskTagList,
            Instant createdAt
    ) {
        this(
                marketType,
                instId,
                baseCurrency,
                quoteCurrency,
                lastPrice,
                changePercent24h,
                changePercent5m,
                volume24h,
                volumeSpikeRatio,
                fundingRate,
                openInterest,
                openInterestChange,
                trendDirection,
                volatility,
                score,
                factorScore,
                entryPrice,
                stopLossPrice,
                takeProfitPrice,
                suggestedLeverage,
                riskRewardRatio,
                spreadBps,
                bidDepthUsdt,
                askDepthUsdt,
                estimatedSlippageBps,
                btcTrend,
                ethTrend,
                marketRiskLevel,
                candidateReasonList,
                riskTagList,
                createdAt,
                trendDirection == DirectionBias.BEARISH ? ContractSignalType.TREND_SHORT : ContractSignalType.STRONG_LONG,
                score >= 65 ? "AUTO_TRADE_ALLOWED" : "WAIT_CONFIRM",
                "LIMIT",
                changePercent24h,
                volatility == null ? BigDecimal.ZERO : volatility.multiply(BigDecimal.valueOf(100)),
                BigDecimal.ZERO,
                BigDecimal.valueOf(score),
                new ContractScoreBreakdown(score, score, score, score, score, score, factorScore.newsRiskScore() * 100 / 7),
                ContractKlineAnalysis.unavailable("LEGACY_ANALYSIS"),
                ContractNewsRiskAnalysis.low(),
                null, null, null, null, null,
                 null, null, null, null, null
         );
    }

    /**
     * 41 参 overload（向后兼容）：原 V1 canonical 签名，V2 5 字段 + Wave 2 2 字段传 null。
     */
    public ContractCandidate(
            MarketType marketType,
            String instId,
            String baseCurrency,
            String quoteCurrency,
            BigDecimal lastPrice,
            BigDecimal changePercent24h,
            BigDecimal changePercent5m,
            BigDecimal volume24h,
            BigDecimal volumeSpikeRatio,
            BigDecimal fundingRate,
            BigDecimal openInterest,
            BigDecimal openInterestChange,
            DirectionBias trendDirection,
            BigDecimal volatility,
            int score,
            ContractFactorScore factorScore,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            BigDecimal takeProfitPrice,
            int suggestedLeverage,
            BigDecimal riskRewardRatio,
            BigDecimal spreadBps,
            BigDecimal bidDepthUsdt,
            BigDecimal askDepthUsdt,
            BigDecimal estimatedSlippageBps,
            DirectionBias btcTrend,
            DirectionBias ethTrend,
            RiskLevel marketRiskLevel,
            List<String> candidateReasonList,
            List<String> riskTagList,
            Instant createdAt,
            ContractSignalType signalType,
            String action,
            String entryType,
            BigDecimal todayChangePct,
            BigDecimal atrPct20m,
            BigDecimal stopLossPct,
            BigDecimal finalRankScore,
            ContractScoreBreakdown scoreBreakdown,
            ContractKlineAnalysis klineAnalysis,
            ContractNewsRiskAnalysis newsAnalysis
    ) {
        this(marketType, instId, baseCurrency, quoteCurrency, lastPrice, changePercent24h, changePercent5m,
                volume24h, volumeSpikeRatio, fundingRate, openInterest, openInterestChange, trendDirection,
                volatility, score, factorScore, entryPrice, stopLossPrice, takeProfitPrice, suggestedLeverage,
                riskRewardRatio, spreadBps, bidDepthUsdt, askDepthUsdt, estimatedSlippageBps, btcTrend, ethTrend,
                marketRiskLevel, candidateReasonList, riskTagList, createdAt, signalType, action, entryType,
                todayChangePct, atrPct20m, stopLossPct, finalRankScore, scoreBreakdown, klineAnalysis,
                 newsAnalysis, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 46 参 overload（向后兼容）：原 V2 canonical 签名，Wave 2 2 字段传 null。
     */
    public ContractCandidate(
            MarketType marketType,
            String instId,
            String baseCurrency,
            String quoteCurrency,
            BigDecimal lastPrice,
            BigDecimal changePercent24h,
            BigDecimal changePercent5m,
            BigDecimal volume24h,
            BigDecimal volumeSpikeRatio,
            BigDecimal fundingRate,
            BigDecimal openInterest,
            BigDecimal openInterestChange,
            DirectionBias trendDirection,
            BigDecimal volatility,
            int score,
            ContractFactorScore factorScore,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            BigDecimal takeProfitPrice,
            int suggestedLeverage,
            BigDecimal riskRewardRatio,
            BigDecimal spreadBps,
            BigDecimal bidDepthUsdt,
            BigDecimal askDepthUsdt,
            BigDecimal estimatedSlippageBps,
            DirectionBias btcTrend,
            DirectionBias ethTrend,
            RiskLevel marketRiskLevel,
            List<String> candidateReasonList,
            List<String> riskTagList,
            Instant createdAt,
            ContractSignalType signalType,
            String action,
            String entryType,
            BigDecimal todayChangePct,
            BigDecimal atrPct20m,
            BigDecimal stopLossPct,
            BigDecimal finalRankScore,
            ContractScoreBreakdown scoreBreakdown,
            ContractKlineAnalysis klineAnalysis,
            ContractNewsRiskAnalysis newsAnalysis,
            // V2 fields
            BigDecimal marketCapUsd,
            BigDecimal fdvUsd,
            BigDecimal circulationRatio,
            BigDecimal volumeMcapRatio,
            String marketQualityNote
    ) {
        this(marketType, instId, baseCurrency, quoteCurrency, lastPrice, changePercent24h, changePercent5m,
                volume24h, volumeSpikeRatio, fundingRate, openInterest, openInterestChange, trendDirection,
                volatility, score, factorScore, entryPrice, stopLossPrice, takeProfitPrice, suggestedLeverage,
                riskRewardRatio, spreadBps, bidDepthUsdt, askDepthUsdt, estimatedSlippageBps, btcTrend, ethTrend,
                marketRiskLevel, candidateReasonList, riskTagList, createdAt, signalType, action, entryType,
                todayChangePct, atrPct20m, stopLossPct, finalRankScore, scoreBreakdown, klineAnalysis,
                newsAnalysis, marketCapUsd, fdvUsd, circulationRatio, volumeMcapRatio, marketQualityNote,
                 null, null, null, null, null);
    }

    /**
     * 兼容旧 canonical（无 openInterestUsd）：名义价值字段默认 null。
     */
    public ContractCandidate(
            MarketType marketType,
            String instId,
            String baseCurrency,
            String quoteCurrency,
            BigDecimal lastPrice,
            BigDecimal changePercent24h,
            BigDecimal changePercent5m,
            BigDecimal volume24h,
            BigDecimal volumeSpikeRatio,
            BigDecimal fundingRate,
            BigDecimal openInterest,
            BigDecimal openInterestChange,
            DirectionBias trendDirection,
            BigDecimal volatility,
            int score,
            ContractFactorScore factorScore,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            BigDecimal takeProfitPrice,
            int suggestedLeverage,
            BigDecimal riskRewardRatio,
            BigDecimal spreadBps,
            BigDecimal bidDepthUsdt,
            BigDecimal askDepthUsdt,
            BigDecimal estimatedSlippageBps,
            DirectionBias btcTrend,
            DirectionBias ethTrend,
            RiskLevel marketRiskLevel,
            List<String> candidateReasonList,
            List<String> riskTagList,
            Instant createdAt,
            ContractSignalType signalType,
            String action,
            String entryType,
            BigDecimal todayChangePct,
            BigDecimal atrPct20m,
            BigDecimal stopLossPct,
            BigDecimal finalRankScore,
            ContractScoreBreakdown scoreBreakdown,
            ContractKlineAnalysis klineAnalysis,
            ContractNewsRiskAnalysis newsAnalysis,
            BigDecimal marketCapUsd,
            BigDecimal fdvUsd,
            BigDecimal circulationRatio,
            BigDecimal volumeMcapRatio,
            String marketQualityNote,
            OpenInterestHistorySnapshot openInterestHistory,
            FundingHistorySnapshot fundingHistory,
            com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot relativeStrength,
            com.example.quant.crypto.provider.dto.AthBreakoutSnapshot athBreakout
    ) {
        this(marketType, instId, baseCurrency, quoteCurrency, lastPrice, changePercent24h, changePercent5m,
                volume24h, volumeSpikeRatio, fundingRate, openInterest, openInterestChange, trendDirection,
                volatility, score, factorScore, entryPrice, stopLossPrice, takeProfitPrice, suggestedLeverage,
                riskRewardRatio, spreadBps, bidDepthUsdt, askDepthUsdt, estimatedSlippageBps, btcTrend, ethTrend,
                marketRiskLevel, candidateReasonList, riskTagList, createdAt, signalType, action, entryType,
                todayChangePct, atrPct20m, stopLossPct, finalRankScore, scoreBreakdown, klineAnalysis,
                newsAnalysis, marketCapUsd, fdvUsd, circulationRatio, volumeMcapRatio, marketQualityNote,
                openInterestHistory, fundingHistory, relativeStrength, athBreakout, null);
    }

    /**
     * 复制并替换 V2 5 字段。返回新 record。Wave 2 Derivatives 字段保留不变。
     */
    public ContractCandidate withV2MarketQuality(
            BigDecimal marketCapUsd,
            BigDecimal fdvUsd,
            BigDecimal circulationRatio,
            BigDecimal volumeMcapRatio,
            String marketQualityNote
    ) {
        return new ContractCandidate(
                marketType, instId, baseCurrency, quoteCurrency, lastPrice,
                changePercent24h, changePercent5m, volume24h, volumeSpikeRatio, fundingRate,
                openInterest, openInterestChange, trendDirection, volatility, score, factorScore,
                entryPrice, stopLossPrice, takeProfitPrice, suggestedLeverage, riskRewardRatio,
                spreadBps, bidDepthUsdt, askDepthUsdt, estimatedSlippageBps, btcTrend, ethTrend,
                marketRiskLevel, candidateReasonList, riskTagList, createdAt, signalType, action,
                entryType, todayChangePct, atrPct20m, stopLossPct, finalRankScore, scoreBreakdown,
                klineAnalysis, newsAnalysis,
                marketCapUsd, fdvUsd, circulationRatio, volumeMcapRatio, marketQualityNote,
                 openInterestHistory, fundingHistory, relativeStrength, athBreakout, openInterestUsd
        );
    }

    /**
     * Wave 2 — 复制并替换 Derivatives 2 字段。返回新 record。V2 Universe / Market Quality 字段保留不变。
     *
     * <p>任何字段为 null 时，保留原值不变（便于"只更新 OI，不更新 Funding"或反之）。
     */
    public ContractCandidate withDerivatives(
            OpenInterestHistorySnapshot openInterestHistory,
            FundingHistorySnapshot fundingHistory
    ) {
        return new ContractCandidate(
                marketType, instId, baseCurrency, quoteCurrency, lastPrice,
                changePercent24h, changePercent5m, volume24h, volumeSpikeRatio, fundingRate,
                openInterest, openInterestChange, trendDirection, volatility, score, factorScore,
                entryPrice, stopLossPrice, takeProfitPrice, suggestedLeverage, riskRewardRatio,
                spreadBps, bidDepthUsdt, askDepthUsdt, estimatedSlippageBps, btcTrend, ethTrend,
                marketRiskLevel, candidateReasonList, riskTagList, createdAt, signalType, action,
                entryType, todayChangePct, atrPct20m, stopLossPct, finalRankScore, scoreBreakdown,
                klineAnalysis, newsAnalysis,
                marketCapUsd, fdvUsd, circulationRatio, volumeMcapRatio, marketQualityNote,
                openInterestHistory == null ? this.openInterestHistory : openInterestHistory,
                 fundingHistory == null ? this.fundingHistory : fundingHistory,
                 relativeStrength, athBreakout, openInterestUsd
         );
    }

    public ContractCandidate withRelativeStrength(
            com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot snapshot) {
        return new ContractCandidate(
                marketType, instId, baseCurrency, quoteCurrency, lastPrice,
                changePercent24h, changePercent5m, volume24h, volumeSpikeRatio, fundingRate,
                openInterest, openInterestChange, trendDirection, volatility, score, factorScore,
                entryPrice, stopLossPrice, takeProfitPrice, suggestedLeverage, riskRewardRatio,
                spreadBps, bidDepthUsdt, askDepthUsdt, estimatedSlippageBps, btcTrend, ethTrend,
                marketRiskLevel, candidateReasonList, riskTagList, createdAt, signalType, action,
                entryType, todayChangePct, atrPct20m, stopLossPct, finalRankScore, scoreBreakdown,
                klineAnalysis, newsAnalysis, marketCapUsd, fdvUsd, circulationRatio, volumeMcapRatio,
                marketQualityNote, openInterestHistory, fundingHistory, snapshot, athBreakout, openInterestUsd);
    }

    public ContractCandidate withAthBreakout(
            com.example.quant.crypto.provider.dto.AthBreakoutSnapshot snapshot) {
        return new ContractCandidate(
                marketType, instId, baseCurrency, quoteCurrency, lastPrice,
                changePercent24h, changePercent5m, volume24h, volumeSpikeRatio, fundingRate,
                openInterest, openInterestChange, trendDirection, volatility, score, factorScore,
                entryPrice, stopLossPrice, takeProfitPrice, suggestedLeverage, riskRewardRatio,
                spreadBps, bidDepthUsdt, askDepthUsdt, estimatedSlippageBps, btcTrend, ethTrend,
                marketRiskLevel, candidateReasonList, riskTagList, createdAt, signalType, action,
                entryType, todayChangePct, atrPct20m, stopLossPct, finalRankScore, scoreBreakdown,
                klineAnalysis, newsAnalysis, marketCapUsd, fdvUsd, circulationRatio, volumeMcapRatio,
                marketQualityNote, openInterestHistory, fundingHistory, relativeStrength, snapshot, openInterestUsd);
    }

    /** 设置资金流评分专用的 USD/USDT 名义持仓价值；null 表示不可用（不是零）。 */
    public ContractCandidate withOpenInterestUsd(BigDecimal openInterestUsd) {
        return new ContractCandidate(
                marketType, instId, baseCurrency, quoteCurrency, lastPrice,
                changePercent24h, changePercent5m, volume24h, volumeSpikeRatio, fundingRate,
                openInterest, openInterestChange, trendDirection, volatility, score, factorScore,
                entryPrice, stopLossPrice, takeProfitPrice, suggestedLeverage, riskRewardRatio,
                spreadBps, bidDepthUsdt, askDepthUsdt, estimatedSlippageBps, btcTrend, ethTrend,
                marketRiskLevel, candidateReasonList, riskTagList, createdAt, signalType, action,
                entryType, todayChangePct, atrPct20m, stopLossPct, finalRankScore, scoreBreakdown,
                klineAnalysis, newsAnalysis, marketCapUsd, fdvUsd, circulationRatio, volumeMcapRatio,
                marketQualityNote, openInterestHistory, fundingHistory, relativeStrength, athBreakout,
                openInterestUsd);
    }

    /**
     * Copies only live market fields after Jev selection. Analysis-derived
     * entry/stop/target fields are intentionally retained; callers must reject
     * stale or materially changed market data instead of mirroring old risk.
     */
    public ContractCandidate withLiveMarketData(
            BigDecimal livePrice,
            BigDecimal liveFundingRate,
            BigDecimal liveOpenInterest,
            BigDecimal liveSpreadBps,
            BigDecimal liveBidDepthUsdt,
            BigDecimal liveAskDepthUsdt,
            BigDecimal liveEstimatedSlippageBps,
            Instant refreshedAt,
            Instant latestCandleTimestamp
    ) {
        return withLiveMarketData(livePrice, liveFundingRate, liveOpenInterest, liveSpreadBps,
                liveBidDepthUsdt, liveAskDepthUsdt, liveEstimatedSlippageBps, refreshedAt,
                latestCandleTimestamp, null);
    }

    public ContractCandidate withLiveMarketData(
            BigDecimal livePrice,
            BigDecimal liveFundingRate,
            BigDecimal liveOpenInterest,
            BigDecimal liveSpreadBps,
            BigDecimal liveBidDepthUsdt,
            BigDecimal liveAskDepthUsdt,
            BigDecimal liveEstimatedSlippageBps,
            Instant refreshedAt,
            Instant latestCandleTimestamp,
            Instant jevDecisionTimestamp
    ) {
        ContractKlineAnalysis refreshedKline = klineAnalysis == null || latestCandleTimestamp == null
                ? klineAnalysis
                : klineAnalysis.withDecisionCandleTimestamp(latestCandleTimestamp);
        java.util.ArrayList<String> provenance = new java.util.ArrayList<>(candidateReasonList == null
                ? java.util.List.of() : candidateReasonList);
        provenance.add("analysisTimestamp=" + createdAt);
        provenance.add("jevDecisionTimestamp=" + jevDecisionTimestamp);
        provenance.add("refreshTimestamp=" + refreshedAt);
        provenance.add("latestCandleTimestamp=" + latestCandleTimestamp);
        return new ContractCandidate(
                marketType, instId, baseCurrency, quoteCurrency,
                livePrice == null || livePrice.signum() <= 0 ? lastPrice : livePrice,
                changePercent24h, changePercent5m, volume24h, volumeSpikeRatio,
                liveFundingRate == null ? fundingRate : liveFundingRate,
                liveOpenInterest == null ? openInterest : liveOpenInterest,
                openInterestChange, trendDirection, volatility, score, factorScore,
                entryPrice, stopLossPrice, takeProfitPrice, suggestedLeverage, riskRewardRatio,
                liveSpreadBps == null ? spreadBps : liveSpreadBps,
                liveBidDepthUsdt == null ? bidDepthUsdt : liveBidDepthUsdt,
                liveAskDepthUsdt == null ? askDepthUsdt : liveAskDepthUsdt,
                liveEstimatedSlippageBps == null ? estimatedSlippageBps : liveEstimatedSlippageBps,
                btcTrend, ethTrend, marketRiskLevel, java.util.List.copyOf(provenance), riskTagList,
                refreshedAt == null ? createdAt : refreshedAt, signalType, action, entryType,
                todayChangePct, atrPct20m, stopLossPct, finalRankScore, scoreBreakdown,
                refreshedKline, newsAnalysis, marketCapUsd, fdvUsd, circulationRatio, volumeMcapRatio,
                marketQualityNote, openInterestHistory, fundingHistory, relativeStrength, athBreakout,
                openInterestUsd);
    }
}

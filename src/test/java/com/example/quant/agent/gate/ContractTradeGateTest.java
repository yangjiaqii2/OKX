package com.example.quant.agent.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskDecision;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

class ContractTradeGateTest {

    @Test
    void reportsLowVolumeAndLiquiditySubScores() {
        ContractCandidate candidate = candidate(BigDecimal.valueOf(150_000), BigDecimal.valueOf(180_000));

        List<String> reasons = ContractTradeGate.planDenyReasons(candidate, new AgentProperties());

        assertThat(reasons).contains("volume_score_below_15", "liquidity_score_below_10");
    }

    @Test
    void reportsLowOrderBookDepthAsLiquidityRisk() {
        ContractCandidate candidate = candidate(BigDecimal.valueOf(150_000), BigDecimal.valueOf(80_000));

        List<String> reasons = ContractTradeGate.planDenyReasons(candidate, new AgentProperties());

        assertThat(reasons).contains("liquidity_score_below_10");
    }

    @Test
    void comparesNewsThresholdOnRawZeroToOneHundredScaleAtBoundary() throws Exception {
        AgentProperties properties = new AgentProperties();
        var descriptor = BeanUtils.getPropertyDescriptor(AgentProperties.Score.class, "minNewsRiskRawScore");
        assertThat(descriptor).as("raw news threshold property").isNotNull();
        descriptor.getWriteMethod().invoke(properties.score(), 70);

        assertThat(ContractTradeGate.planDenyReasons(candidateWithNews(69, "LOW",
                ContractNewsRiskDecision.allow("test")), properties))
                .contains("news_risk_raw_score_below_70");
        assertThat(ContractTradeGate.planDenyReasons(candidateWithNews(70, "LOW",
                ContractNewsRiskDecision.allow("test")), properties))
                .noneMatch(reason -> reason.startsWith("news_"));
        assertThat(ContractTradeGate.planDenyReasons(candidateWithNews(71, "LOW",
                ContractNewsRiskDecision.allow("test")), properties))
                .noneMatch(reason -> reason.startsWith("news_"));
    }

    @Test
    void rejectsUnavailableAndHighNewsIndependentlyFromRawScore() {
        AgentProperties properties = new AgentProperties();

        assertThat(ContractTradeGate.planDenyReasons(candidateWithNews(70, "UNKNOWN",
                ContractNewsRiskDecision.waitOnly("unavailable")), properties))
                .contains("news_data_unavailable");
        assertThat(ContractTradeGate.planDenyReasons(candidateWithNews(100, "HIGH",
                ContractNewsRiskDecision.noTrade("high")), properties))
                .contains("news_risk_high");
        assertThat(ContractTradeGate.planDenyReasons(candidateWithNews(100, "CRITICAL",
                ContractNewsRiskDecision.noTrade("critical")), properties))
                .contains("news_risk_critical");
    }

    @Test
    void successfulNoMatchOnlyClearsNewsGateAndDoesNotBypassOtherHardRules() {
        AgentProperties properties = new AgentProperties();
        ContractCandidate lowVolumeCandidate = candidateWithNewsAndVolumeFactor(70, "LOW",
                ContractNewsRiskDecision.allow("no match in queried coverage"), 0);

        assertThat(ContractTradeGate.planDenyReasons(lowVolumeCandidate, properties))
                .contains("volume_score_below_15")
                .noneMatch(reason -> reason.startsWith("news_"));
    }

    @Test
    void acceptsTrendAndVolumeAtFifteenWeightedPoints() {
        ContractCandidate candidate = candidateWithFactors(70, "LOW", ContractNewsRiskDecision.allow("test"),
                new ContractScoreBreakdown(60, 75, 100, 100, 100, 100, 70));

        assertThat(ContractTradeGate.planDenyReasons(candidate, new AgentProperties())).isEmpty();
    }

    @Test
    void rejectsDeprecatedWeightedNewsThresholdConfigurationExplicitly() {
        AgentProperties properties = new AgentProperties();
        properties.score().setMinNewsRiskScore(7);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        ContractTradeGate.planDenyReasons(candidateWithNews(100, "LOW",
                                ContractNewsRiskDecision.allow("test")), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("min-news-risk-raw-score");
    }

    private static ContractCandidate candidateWithNews(int rawScore, String level,
                                                       ContractNewsRiskDecision decision) {
        return candidateWithNewsAndVolumeFactor(rawScore, level, decision, 100);
    }

    private static ContractCandidate candidateWithNewsAndVolumeFactor(int rawScore, String level,
                                                                      ContractNewsRiskDecision decision,
                                                                      int volumeRawScore) {
        ContractScoreBreakdown breakdown = new ContractScoreBreakdown(
                100, volumeRawScore, 100, 100, 100, 100, rawScore);
        return candidateWithFactors(rawScore, level, decision, breakdown);
    }

    private static ContractCandidate candidateWithFactors(int rawScore, String level,
                                                           ContractNewsRiskDecision decision,
                                                           ContractScoreBreakdown breakdown) {
        ContractCandidate base = candidate(BigDecimal.valueOf(500_000), BigDecimal.valueOf(500_000));
        ContractNewsRiskAnalysis news = new ContractNewsRiskAnalysis(
                rawScore, level, List.of(), List.of(), List.of(), decision);
        return new ContractCandidate(
                base.marketType(), base.instId(), base.baseCurrency(), base.quoteCurrency(), base.lastPrice(),
                base.changePercent24h(), base.changePercent5m(), base.volume24h(), base.volumeSpikeRatio(),
                base.fundingRate(), base.openInterest(), base.openInterestChange(), base.trendDirection(),
                base.volatility(), 95, breakdown.weightedFactorScore(), base.entryPrice(), base.stopLossPrice(),
                base.takeProfitPrice(), base.suggestedLeverage(), base.riskRewardRatio(), base.spreadBps(),
                base.bidDepthUsdt(), base.askDepthUsdt(), base.estimatedSlippageBps(), base.btcTrend(),
                base.ethTrend(), base.marketRiskLevel(), base.candidateReasonList(), base.riskTagList(),
                base.createdAt(), ContractSignalType.STRONG_LONG, "AUTO_TRADE_ALLOWED", "LIMIT",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.valueOf(95), breakdown,
                ContractKlineAnalysis.unavailable("test"), news);
    }

    private static ContractCandidate candidate(BigDecimal bidDepth, BigDecimal askDepth) {
        return new ContractCandidate(
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                "BTC",
                "USDT",
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(3),
                BigDecimal.valueOf(0.8),
                BigDecimal.valueOf(80_000_000),
                BigDecimal.ONE,
                new BigDecimal("0.0001"),
                BigDecimal.valueOf(100_000_000),
                BigDecimal.ZERO,
                DirectionBias.BULLISH,
                new BigDecimal("0.015"),
                95,
                new ContractFactorScore(20, 11, 12, 3, 8, 10, 8),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(105),
                3,
                new BigDecimal("2.5"),
                BigDecimal.valueOf(2),
                bidDepth,
                askDepth,
                BigDecimal.ONE,
                DirectionBias.BULLISH,
                DirectionBias.BULLISH,
                RiskLevel.LOW,
                List.of(),
                List.of(),
                Instant.now()
        );
    }
}

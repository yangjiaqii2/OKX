package com.example.quant.agent.entry;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmartEntryModeResolverTest {

    @Test
    void allowsMarketForCleanStrongLong() {
        SmartEntryModeDecision decision = new SmartEntryModeResolver(new AgentProperties())
                .resolve(candidate(ContractSignalType.STRONG_LONG, 90, "READY", "BREAKOUT", "LOW",
                        new BigDecimal("0.8"), new BigDecimal("2.2"), BigDecimal.valueOf(2), BigDecimal.valueOf(8)));

        assertThat(decision.mode()).isEqualTo(SmartEntryMode.MARKET);
        assertThat(decision.allowSubmit()).isTrue();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("MARKET"));
    }

    @Test
    void waitsForPullbackWhenPullbackLongIsExtended() {
        SmartEntryModeDecision decision = new SmartEntryModeResolver(new AgentProperties())
                .resolve(candidate(ContractSignalType.PULLBACK_LONG, 88, "WAIT_PULLBACK", "HIGHER_HIGH_HIGHER_LOW", "LOW",
                        new BigDecimal("2.4"), new BigDecimal("2.0"), BigDecimal.valueOf(2), BigDecimal.valueOf(8)));

        assertThat(decision.mode()).isEqualTo(SmartEntryMode.WAIT_PULLBACK);
        assertThat(decision.allowSubmit()).isFalse();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("等待回踩"));
    }

    @Test
    void waitsForRetestWhenBreakoutIsNotReady() {
        SmartEntryModeDecision decision = new SmartEntryModeResolver(new AgentProperties())
                .resolve(candidate(ContractSignalType.STRONG_LONG, 89, "WAIT_RETEST", "BREAKOUT_PREV_HIGH", "LOW",
                        new BigDecimal("1.2"), new BigDecimal("2.1"), BigDecimal.valueOf(2), BigDecimal.valueOf(8)));

        assertThat(decision.mode()).isEqualTo(SmartEntryMode.WAIT_RETEST);
        assertThat(decision.allowSubmit()).isFalse();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("回踩确认"));
    }

    @Test
    void blocksNoEntryForOverheatedSignal() {
        SmartEntryModeDecision decision = new SmartEntryModeResolver(new AgentProperties())
                .resolve(candidate(ContractSignalType.WAIT_OVERHEATED, 91, "NOT_READY", "RANGE", "LOW",
                        new BigDecimal("3.2"), new BigDecimal("2.5"), BigDecimal.valueOf(2), BigDecimal.valueOf(8)));

        assertThat(decision.mode()).isEqualTo(SmartEntryMode.NO_ENTRY);
        assertThat(decision.allowSubmit()).isFalse();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("不可入场"));
    }

    @Test
    void blocksNoEntryForHighNewsRisk() {
        SmartEntryModeDecision decision = new SmartEntryModeResolver(new AgentProperties())
                .resolve(candidate(ContractSignalType.STRONG_LONG, 90, "READY", "BREAKOUT", "HIGH",
                        new BigDecimal("0.8"), new BigDecimal("2.2"), BigDecimal.valueOf(2), BigDecimal.valueOf(8)));

        assertThat(decision.mode()).isEqualTo(SmartEntryMode.NO_ENTRY);
        assertThat(decision.allowSubmit()).isFalse();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("新闻风险"));
    }

    private static ContractCandidate candidate(ContractSignalType signalType,
                                               int score,
                                               String entryTiming5m,
                                               String structure,
                                               String newsRiskLevel,
                                               BigDecimal distanceFromEma20Pct,
                                               BigDecimal riskReward,
                                               BigDecimal spreadBps,
                                               BigDecimal slippageBps) {
        ContractNewsRiskAnalysis news = "HIGH".equals(newsRiskLevel)
                ? new ContractNewsRiskAnalysis(45, "HIGH", List.of("NEGATIVE"), List.of(), List.of("可信负面风险"),
                com.example.quant.crypto.dto.ContractNewsRiskDecision.waitOnly("新闻风险升高"))
                : ContractNewsRiskAnalysis.low();
        return new ContractCandidate(
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                "BTC",
                "USDT",
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(5),
                BigDecimal.ONE,
                BigDecimal.valueOf(500_000_000),
                BigDecimal.valueOf(1.5),
                new BigDecimal("0.0001"),
                BigDecimal.valueOf(200_000_000),
                BigDecimal.ZERO,
                DirectionBias.BULLISH,
                new BigDecimal("0.015"),
                score,
                new ContractFactorScore(90, 90, 90, 90, 90, 90, 90),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(105),
                3,
                riskReward,
                spreadBps,
                BigDecimal.valueOf(200_000),
                BigDecimal.valueOf(200_000),
                slippageBps,
                DirectionBias.BULLISH,
                DirectionBias.BULLISH,
                RiskLevel.LOW,
                List.of("20m结构清晰"),
                List.of(),
                Instant.now(),
                signalType,
                "AUTO_TRADE_ALLOWED",
                "MARKET",
                BigDecimal.valueOf(5),
                new BigDecimal("1.5"),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(score),
                new ContractScoreBreakdown(score, 88, 90, 90, 90, 90, 90),
                new ContractKlineAnalysis("UP", "UP", "UP", "BULLISH", "STRENGTHENING",
                        BigDecimal.valueOf(62), BigDecimal.valueOf(24), structure, entryTiming5m,
                        BigDecimal.valueOf(1.5), new BigDecimal("1.5"), distanceFromEma20Pct, true),
                news
        );
    }
}

package com.example.quant.agent.execution;

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
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreConfirmRefreshServiceTest {

    @Test
    void unknownNewsRiskDoesNotRejectPreConfirmRefresh() {
        PreConfirmRefreshService service = new PreConfirmRefreshService(new AgentProperties());

        PreConfirmRefreshResult result = service.check(candidateWithUnknownNews(), plan());

        assertThat(result.passed()).isTrue();
        assertThat(result.reasons()).doesNotContain("NEWS_RISK_UPGRADED_UNKNOWN");
    }

    private static ContractCandidate candidateWithUnknownNews() {
        return new ContractCandidate(
                MarketType.OKX_SWAP,
                "PEPE-USDT-SWAP",
                "PEPE",
                "USDT",
                BigDecimal.valueOf(100),
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.valueOf(500_000_000),
                BigDecimal.valueOf(1.4),
                new BigDecimal("0.0001"),
                BigDecimal.valueOf(100_000_000),
                BigDecimal.ZERO,
                DirectionBias.BULLISH,
                new BigDecimal("0.015"),
                82,
                new ContractFactorScore(20, 20, 8, 12, 8, 8, 1),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(105),
                3,
                new BigDecimal("2.5"),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(300_000),
                BigDecimal.valueOf(300_000),
                BigDecimal.ONE,
                DirectionBias.BULLISH,
                DirectionBias.BULLISH,
                RiskLevel.LOW,
                List.of(),
                List.of(),
                Instant.now(),
                ContractSignalType.STRONG_LONG,
                "AUTO_TRADE_ALLOWED",
                "LIMIT",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(82),
                new ContractScoreBreakdown(82, 82, 82, 82, 82, 82, 1),
                new ContractKlineAnalysis("UP", "UP", "UP", "BULLISH", "STRENGTHENING",
                        BigDecimal.valueOf(55), BigDecimal.valueOf(25), "BREAKOUT", "READY",
                        BigDecimal.valueOf(1.4), BigDecimal.ONE, BigDecimal.ZERO, true),
                ContractNewsRiskAnalysis.unknown("新闻源无返回")
        );
    }

    private static TradePlan plan() {
        return new TradePlan(
                UUID.randomUUID(),
                "PEPE-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "LIMIT",
                DirectionBias.BULLISH,
                BigDecimal.ONE,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(105),
                3,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ONE,
                new BigDecimal("2.5"),
                82,
                new BigDecimal("0.0001"),
                new BigDecimal("0.015"),
                BigDecimal.valueOf(500_000_000),
                "cross",
                List.of(),
                List.of(),
                "",
                true,
                Instant.now().plusSeconds(120)
        );
    }
}

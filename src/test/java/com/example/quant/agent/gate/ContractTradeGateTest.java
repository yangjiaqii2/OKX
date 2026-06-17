package com.example.quant.agent.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractTradeGateTest {

    @Test
    void reportsLowVolumeAndLiquiditySubScores() {
        ContractCandidate candidate = candidate(BigDecimal.valueOf(150_000), BigDecimal.valueOf(180_000));

        List<String> reasons = ContractTradeGate.planDenyReasons(candidate, new AgentProperties());

        assertThat(reasons).contains("volume_score_below_17", "liquidity_score_below_10");
    }

    @Test
    void reportsLowOrderBookDepthAsLiquidityRisk() {
        ContractCandidate candidate = candidate(BigDecimal.valueOf(150_000), BigDecimal.valueOf(80_000));

        List<String> reasons = ContractTradeGate.planDenyReasons(candidate, new AgentProperties());

        assertThat(reasons).contains("liquidity_score_below_10");
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

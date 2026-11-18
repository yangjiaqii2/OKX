package com.example.quant.agent.execution;

import com.example.quant.account.dto.PositionSummary;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class CorrelationExposureGuardTest {
    @ParameterizedTest
    @CsvSource({"BULLISH,net,5", "BEARISH,net,-5", "BULLISH,long,5", "BEARISH,short,5"})
    void sameDirectionLimitIncludesSignedNetPositions(DirectionBias direction, String side, String size) {
        var decision = guard().evaluate(List.of(position("SOL", side, size), position("XRP", side, size)),
                candidate(), direction);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("same_direction_alt_exposure_limit_2");
    }

    @ParameterizedTest
    @CsvSource({"BULLISH,-5", "BEARISH,5"})
    void oppositeNetDirectionDoesNotConsumeSameDirectionCapacity(DirectionBias direction, String size) {
        var decision = guard().evaluate(List.of(position("SOL", "net", size), position("XRP", "net", size)),
                candidate(), direction);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo("same_direction_alt_exposure_0_of_2");
    }

    @Test
    void flatPositionsDoNotBlockEntry() {
        var decision = guard().evaluate(List.of(position("SOL", "long", "0"), position("XRP", "long", "0")),
                candidate(), DirectionBias.BULLISH);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo("same_direction_alt_exposure_0_of_2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid"})
    void unknownPositionSizeCannotBeCountedAsFreeCapacity(String size) {
        assertThatThrownBy(() -> guard().evaluate(List.of(position("SOL", "net", size)),
                candidate(), DirectionBias.BULLISH))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("POSITION_SIZE_UNAVAILABLE");
    }

    private static CorrelationExposureGuard guard() {
        return new CorrelationExposureGuard(new AgentProperties());
    }

    private static PositionSummary position(String base, String side, String size) {
        return new PositionSummary(base + "-USDT-SWAP", side, size, BigDecimal.TEN, BigDecimal.ZERO, "OKX_REAL");
    }

    private static ContractCandidate candidate() {
        BigDecimal price = BigDecimal.TEN;
        return new ContractCandidate(MarketType.OKX_SWAP, "DOGE-USDT-SWAP", "DOGE", "USDT",
                price, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100000000"), BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, DirectionBias.BULLISH, BigDecimal.ZERO,
                80, new ContractFactorScore(20, 20, 5, 20, 5, 5, 5), price, new BigDecimal("9.8"),
                new BigDecimal("10.4"), 2, new BigDecimal("2"), BigDecimal.ONE, new BigDecimal("100000"),
                new BigDecimal("100000"), BigDecimal.ZERO, DirectionBias.BULLISH, DirectionBias.BULLISH,
                RiskLevel.LOW, List.of(), List.of(), Instant.now());
    }
}

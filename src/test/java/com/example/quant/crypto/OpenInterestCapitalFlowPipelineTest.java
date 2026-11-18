package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.crypto.selection.V2ScoreDimension;
import com.example.quant.crypto.selection.V2SelectionScoreCalculator;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 扫描器 OI 解析 → 候选字段 → 资金流评分的实际传递链路。
 */
class OpenInterestCapitalFlowPipelineTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("扫描器解析的 USD 名义 OI 进入候选后，资金流按名义价值而非币数量评分")
    void scannerUsdNotionalFlowsIntoCapitalFlowScore() {
        ObjectNode lowPriceRow = MAPPER.createObjectNode();
        lowPriceRow.put("oiCcy", "1000");
        ObjectNode highPriceRow = MAPPER.createObjectNode();
        highPriceRow.put("oiCcy", "200");

        OkxContractScanner.OpenInterestReading low =
                OkxContractScanner.OpenInterestReading.fromOkxRow("LOW-USDT-SWAP", lowPriceRow, new BigDecimal("50"));
        OkxContractScanner.OpenInterestReading high =
                OkxContractScanner.OpenInterestReading.fromOkxRow("HIGH-USDT-SWAP", highPriceRow, new BigDecimal("250"));

        assertThat(low.usdNotional()).isEqualByComparingTo(high.usdNotional());

        ContractCandidate left = candidate("LOW-USDT-SWAP", low.coinAmount(), low.usdNotional());
        ContractCandidate right = candidate("HIGH-USDT-SWAP", high.coinAmount(), high.usdNotional());

        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        BigDecimal leftScore = calculator.calculate(left).dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW);
        BigDecimal rightScore = calculator.calculate(right).dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW);

        assertThat(left.openInterest()).isEqualByComparingTo("1000");
        assertThat(right.openInterest()).isEqualByComparingTo("200");
        assertThat(left.openInterestUsd()).isEqualByComparingTo("50000");
        assertThat(right.openInterestUsd()).isEqualByComparingTo("50000");
        assertThat(leftScore).isEqualByComparingTo(rightScore);
    }

    @Test
    @DisplayName("OI 请求失败（unavailable）进入候选后，资金流退出且覆盖率下降")
    void unavailableOpenInterestExcludesCapitalFlowThroughCandidate() {
        OkxContractScanner.OpenInterestReading missing = OkxContractScanner.OpenInterestReading.unavailable();
        ContractCandidate withUsd = candidate("OK-USDT-SWAP", new BigDecimal("10"), new BigDecimal("100000"));
        ContractCandidate withoutUsd = candidate("BAD-USDT-SWAP", missing.coinAmount(), missing.usdNotional());

        V2SelectionScoreCalculator calculator = new V2SelectionScoreCalculator();
        V2SelectionScoreResult present = calculator.calculate(withUsd);
        V2SelectionScoreResult absent = calculator.calculate(withoutUsd);

        assertThat(withoutUsd.openInterest()).isNull();
        assertThat(withoutUsd.openInterestUsd()).isNull();
        assertThat(present.dimensionScores()).containsKey(V2ScoreDimension.CAPITAL_FLOW);
        assertThat(absent.dimensionScores()).doesNotContainKey(V2ScoreDimension.CAPITAL_FLOW);
        assertThat(absent.availableWeight()).isLessThan(present.availableWeight());
        assertThat(absent.missingDimensions()).contains(V2ScoreDimension.CAPITAL_FLOW);
    }

    private static ContractCandidate candidate(String instId, BigDecimal openInterestCoin, BigDecimal openInterestUsd) {
        ContractKlineAnalysis kline = new ContractKlineAnalysis(
                "UP", "UP", "UP", "BULLISH", "STRENGTHENING", BigDecimal.valueOf(60),
                BigDecimal.valueOf(25), "HIGHER_HIGH_HIGHER_LOW", "READY", BigDecimal.valueOf(1.5),
                BigDecimal.valueOf(2), BigDecimal.ONE, true);
        return new ContractCandidate(
                MarketType.OKX_SWAP, instId, "X", "USDT", BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("500000000"), new BigDecimal("2"),
                new BigDecimal("0.0001"), openInterestCoin, BigDecimal.ZERO, DirectionBias.BULLISH,
                new BigDecimal("0.02"), 80, new ContractFactorScore(80, 80, 80, 80, 80, 80, 80),
                BigDecimal.TEN, new BigDecimal("9.7"), new BigDecimal("12"), 3, new BigDecimal("2.5"),
                new BigDecimal("2"), new BigDecimal("200000"), new BigDecimal("200000"), BigDecimal.ONE,
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW, List.of(), List.of(), NOW,
                ContractSignalType.STRONG_LONG, "AUTO_TRADE_ALLOWED", "LIMIT",
                BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("0.02"), BigDecimal.valueOf(80),
                new ContractScoreBreakdown(80, 80, 80, 80, 80, 80, 80), kline,
                ContractNewsRiskAnalysis.low(),
                null, null, null, null, null, null, null, null, null, openInterestUsd);
    }
}

package com.example.quant.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.OkxMarketService;
import com.example.quant.crypto.OkxRestClient;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveMarketRefreshServiceTest {
    private static final Instant ANALYSIS_CANDLE = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant SAME_CANDLE = ANALYSIS_CANDLE;

    @Test
    void refreshesTickerOrderBookFundingAndCandleBeforePlanRebuild() {
        LiveMarketRefreshService service = service(SAME_CANDLE);

        LiveMarketRefreshResult result = service.refresh(candidate());

        assertThat(result.passed()).isTrue();
        assertThat(result.candidate().lastPrice()).isEqualByComparingTo("100.1");
        assertThat(result.candidate().fundingRate()).isEqualByComparingTo("0.0002");
        assertThat(result.candidate().spreadBps()).isEqualByComparingTo("2");
        assertThat(result.candidate().createdAt()).isEqualTo(result.refreshTimestamp());
        assertThat(result.latestCandleTimestamp()).isEqualTo(ANALYSIS_CANDLE);
    }

    @Test
    void newerCandleInvalidatesSelectionInsteadOfUsingStaleStructure() {
        LiveMarketRefreshService service = service(ANALYSIS_CANDLE.plusSeconds(300));

        LiveMarketRefreshResult result = service.refresh(candidate());

        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).contains("LIVE_REFRESH_CANDLE_CHANGED_REANALYZE_REQUIRED");
        assertThat(result.candidate()).isNull();
    }

    private static LiveMarketRefreshService service(Instant latestCandle) {
        ObjectMapper mapper = new ObjectMapper();
        OkxRestClient rest = new OkxRestClient() {
            @Override
            public JsonNode publicGet(String path, Duration timeout) {
                try {
                    if (path.contains("market/ticker")) {
                        return mapper.readTree("{\"data\":[{\"last\":\"100.1\",\"markPx\":\"100.1\"}]}");
                    }
                    return mapper.readTree("{\"data\":[{\"fundingRate\":\"0.0002\"}]}");
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
        OkxMarketService market = new OkxMarketService(null) {
            @Override
            public List<ContractCandle> candles(String instId, String bar, int limit, Duration timeout) {
                return List.of(new ContractCandle(latestCandle, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
            }
        };
        OrderBookLiquidityService book = new OrderBookLiquidityService(null, new AgentProperties()) {
            @Override
            public OrderBookLiquiditySnapshot snapshot(String instId) {
                return new OrderBookLiquiditySnapshot(instId, new BigDecimal("100.0"), new BigDecimal("100.02"),
                        new BigDecimal("100.01"), new BigDecimal("2"), new BigDecimal("100000"),
                        new BigDecimal("100000"), BigDecimal.ONE, true, "", Instant.now());
            }
        };
        return new LiveMarketRefreshService(rest, market, book, new AgentProperties());
    }

    private static ContractCandidate candidate() {
        return new ContractCandidate(
                MarketType.OKX_SWAP, "BTC-USDT-SWAP", "BTC", "USDT", new BigDecimal("100"), BigDecimal.ONE,
                BigDecimal.ZERO, new BigDecimal("500000000"), new BigDecimal("1.5"), new BigDecimal("0.0001"),
                new BigDecimal("200000000"), BigDecimal.ZERO, DirectionBias.BULLISH, new BigDecimal("0.015"), 92,
                new ContractFactorScore(90, 90, 90, 90, 90, 90, 90), new BigDecimal("100"), new BigDecimal("98"),
                new BigDecimal("105"), 3, new BigDecimal("2.5"), new BigDecimal("2"), new BigDecimal("200000"),
                new BigDecimal("200000"), BigDecimal.ONE, DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW,
                List.of(), List.of(), ANALYSIS_CANDLE, ContractSignalType.STRONG_LONG, "AUTO_TRADE_ALLOWED", "MARKET",
                BigDecimal.ONE, new BigDecimal("1.5"), new BigDecimal("2"), new BigDecimal("92"),
                new ContractScoreBreakdown(92, 90, 90, 90, 90, 90, 90),
                new ContractKlineAnalysis("UP", "UP", "UP", "BULLISH", "STRENGTHENING", new BigDecimal("60"),
                        new BigDecimal("24"), "HIGHER_HIGH_HIGHER_LOW", "READY", new BigDecimal("1.5"),
                        new BigDecimal("1.5"), BigDecimal.ONE, true, ANALYSIS_CANDLE), ContractNewsRiskAnalysis.low());
    }
}

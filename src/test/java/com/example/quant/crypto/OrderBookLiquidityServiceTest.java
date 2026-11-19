package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderBookLiquidityServiceTest {

    @ParameterizedTest
    @CsvSource({
            "5000,50.02,true",
            "5000,50.00,false",
            "20000,50.02,false"
    })
    void appliesConfiguredFloorToThinnerSide(int floor, String bidContracts, boolean expected) {
        AgentProperties properties = new AgentProperties();
        properties.market().setMinDepthUsdt(BigDecimal.valueOf(floor));
        var book = new FakeOkxRestClient(bidContracts);
        var service = new OrderBookLiquidityService(book, properties, instId ->
                new OkxInstrumentRules(instId, BigDecimal.ONE, "BTC", BigDecimal.ONE,
                        BigDecimal.ONE, new BigDecimal("0.01")));

        var snapshot = service.snapshot("BTC-USDT-SWAP");
        assertThat(snapshot.tradable()).isEqualTo(expected);
        if (!expected) assertThat(snapshot.denyReason()).isEqualTo("depth_usdt_below_" + floor);
    }

    @Test
    void convertsOkxSwapContractSizeToUsdtDepthWithContractValue() {
        OrderBookLiquiditySnapshot snapshot = new OrderBookLiquidityService(
                new FakeOkxRestClient(),
                new AgentProperties(),
                instId -> new OkxInstrumentRules(instId, new BigDecimal("0.01"), "BTC",
                        BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.1")))
                .snapshot("BTC-USDT-SWAP");

        assertThat(snapshot.bidDepthUsdt()).isGreaterThan(BigDecimal.valueOf(100_000));
        assertThat(snapshot.askDepthUsdt()).isGreaterThan(BigDecimal.valueOf(100_000));
        assertThat(snapshot.tradable()).isTrue();
    }

    @Test
    void unavailableInstrumentRulesFailClosedInsteadOfAssumingOneCoinPerContract() {
        OrderBookLiquiditySnapshot snapshot = new OrderBookLiquidityService(
                new FakeOkxRestClient(),
                new AgentProperties(),
                instId -> { throw new IllegalStateException("instrument unavailable"); })
                .snapshot("BTC-USDT-SWAP");

        assertThat(snapshot.tradable()).isFalse();
        assertThat(snapshot.denyReason()).contains("合约规则");
        assertThat(snapshot.availableDepthUsdt()).isZero();
    }

    private static final class FakeOkxRestClient extends OkxRestClient {
        private final ObjectMapper objectMapper = new ObjectMapper();

        private final String bidContracts;

        FakeOkxRestClient() {
            this("101000");
        }

        FakeOkxRestClient(String bidContracts) {
            super(null, null, null, null, null);
            this.bidContracts = bidContracts;
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            try {
                if (requestPath.startsWith("/api/v5/market/books")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"bids":[["99.98","%s"]],"asks":[["100.02","101000"]]}]}
                            """.formatted(bidContracts));
                }
                if (requestPath.startsWith("/api/v5/public/instruments")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"instId":"BTC-USDT-SWAP","ctVal":"0.01","ctValCcy":"BTC"}]}
                            """);
                }
                return objectMapper.readTree("{\"code\":\"0\",\"data\":[]}");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}

package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrderBookLiquidityServiceTest {

    @Test
    void convertsOkxSwapContractSizeToUsdtDepthWithContractValue() {
        OrderBookLiquiditySnapshot snapshot = new OrderBookLiquidityService(new FakeOkxRestClient(), new AgentProperties())
                .snapshot("BTC-USDT-SWAP");

        assertThat(snapshot.bidDepthUsdt()).isGreaterThan(BigDecimal.valueOf(100_000));
        assertThat(snapshot.askDepthUsdt()).isGreaterThan(BigDecimal.valueOf(100_000));
        assertThat(snapshot.tradable()).isTrue();
    }

    private static final class FakeOkxRestClient extends OkxRestClient {
        private final ObjectMapper objectMapper = new ObjectMapper();

        FakeOkxRestClient() {
            super(null, null, null, null, null);
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            try {
                if (requestPath.startsWith("/api/v5/market/books")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"bids":[["99.98","101000"]],"asks":[["100.02","101000"]]}]}
                            """);
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

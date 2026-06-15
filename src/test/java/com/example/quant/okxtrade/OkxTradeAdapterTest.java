package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.market.MarketType;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OkxTradeAdapterTest {
    @Test
    void placesRealOkxOrderThroughGateway() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway);
        PendingOrder order = new PendingOrder(
                UUID.randomUUID(),
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "buy",
                "long",
                "LIMIT",
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(5),
                2,
                "cross",
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(104),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(2),
                78,
                BigDecimal.valueOf(0.0002),
                BigDecimal.valueOf(0.02),
                BigDecimal.valueOf(25000000),
                UUID.randomUUID(),
                "token",
                Instant.parse("2026-06-14T00:00:00Z"),
                Instant.parse("2026-06-14T00:02:00Z")
        );

        OrderExecutionResult result = adapter.placeOrder(order);

        assertThat(result.executed()).isTrue();
        assertThat(result.liveOrder()).isTrue();
        assertThat(result.externalOrderId()).isEqualTo("123456");
        assertThat(gateway.payload).containsEntry("instId", "BTC-USDT-SWAP");
        assertThat(gateway.payload).containsEntry("tdMode", "cross");
        assertThat(gateway.payload).containsEntry("side", "buy");
        assertThat(gateway.payload).containsEntry("posSide", "long");
        assertThat(gateway.payload).containsEntry("ordType", "limit");
        assertThat(gateway.payload).containsEntry("px", "100");
        assertThat(gateway.payload).containsEntry("sz", "5");
    }

    private static class CapturingGateway implements OkxOrderGateway {
        private Map<String, String> payload;

        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
            this.payload = payload;
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject().put("ordId", "123456");
            return root;
        }
    }
}

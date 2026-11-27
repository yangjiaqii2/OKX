package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OkxCurrentOrderServiceTest {

    @Test
    void mapsCurrentNormalOrdersToVisibleFields() {
        OkxCurrentOrderService service = new OkxCurrentOrderService(new CurrentOrderGateway());

        List<OkxCurrentOrderView> orders = service.currentOrders();

        assertThat(orders).hasSize(1);
        OkxCurrentOrderView order = orders.get(0);
        assertThat(order.instId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(order.ordId()).isEqualTo("ord-1");
        assertThat(order.clOrdId()).isEqualTo("AUTOentry1");
        assertThat(order.role()).isEqualTo("ENTRY");
        assertThat(order.side()).isEqualTo("sell");
        assertThat(order.posSide()).isEqualTo("short");
        assertThat(order.ordType()).isEqualTo("limit");
        assertThat(order.reduceOnly()).isFalse();
        assertThat(order.status()).isEqualTo("live");
    }

    @Test
    void mapsCurrentAlgoOrdersToProtectionFields() {
        OkxCurrentOrderService service = new OkxCurrentOrderService(new CurrentOrderGateway());

        List<OkxCurrentOrderView> orders = service.currentAlgoOrders();

        assertThat(orders).hasSize(1);
        OkxCurrentOrderView order = orders.get(0);
        assertThat(order.instId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(order.algoId()).isEqualTo("algo-1");
        assertThat(order.algoClOrdId()).isEqualTo("qa123sl");
        assertThat(order.role()).isEqualTo("STOP_LOSS");
        assertThat(order.triggerPrice()).isEqualByComparingTo("98");
        assertThat(order.reduceOnly()).isTrue();
        assertThat(order.status()).isEqualTo("live");
    }

    @Test
    void keepsAlgoIdSeparateFromTriggeredChildOrderIds() {
        OkxCurrentOrderService service = new OkxCurrentOrderService(new TriggeredAlgoGateway());

        OkxCurrentOrderService.AlgoOrderDetail detail = service.algoOrderDetail("algo-parent", "SLstable");

        assertThat(detail.algoId()).isEqualTo("algo-parent");
        assertThat(detail.childOrderIds()).containsExactly("child-1", "child-2");
        assertThat(detail.childOrderIds()).doesNotContain(detail.algoId());
    }

    @ParameterizedTest
    @CsvSource({"1750, 1750", "'', 2000"})
    void usesActualExecutionTimeAndFallsBackToRecordTime(String fillTime, long expectedMillis) {
        var gateway = new CurrentOrderGateway() {
            @Override
            public JsonNode fills(Map<String, String> payload) {
                ObjectNode root = new ObjectMapper().createObjectNode();
                ObjectNode fill = root.putArray("data").addObject();
                fill.put("instId", "BTC-USDT-SWAP").put("ordId", "ord-1").put("tradeId", "trade-1")
                        .put("fillSz", "3").put("fillPx", "100").put("ts", "2000");
                if (!fillTime.isEmpty()) fill.put("fillTime", fillTime);
                return root;
            }
        };

        assertThat(new OkxCurrentOrderService(gateway).fills("BTC-USDT-SWAP", "ord-1"))
                .extracting(OkxCurrentOrderService.Fill::timestamp)
                .containsExactly(Instant.ofEpochMilli(expectedMillis));
    }

    private static class CurrentOrderGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode currentOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("instId", "BTC-USDT-SWAP");
            item.put("ordId", "ord-1");
            item.put("clOrdId", "AUTOentry1");
            item.put("side", "sell");
            item.put("posSide", "short");
            item.put("ordType", "limit");
            item.put("reduceOnly", "false");
            item.put("sz", "5");
            item.put("px", "100");
            item.put("state", "live");
            item.put("cTime", "1780000000000");
            return root;
        }

        @Override
        public JsonNode currentAlgoOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("instId", "BTC-USDT-SWAP");
            item.put("algoId", "algo-1");
            item.put("algoClOrdId", "qa123sl");
            item.put("side", "buy");
            item.put("posSide", "short");
            item.put("ordType", "conditional");
            item.put("sz", "5");
            item.put("slTriggerPx", "98");
            item.put("state", "live");
            item.put("cTime", "1780000000000");
            return root;
        }
    }

    private static final class TriggeredAlgoGateway extends CurrentOrderGateway {
        @Override
        public JsonNode queryAlgoOrder(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("algoId", "algo-parent");
            item.put("algoClOrdId", "SLstable");
            item.put("state", "effective");
            item.put("sz", "717");
            item.put("actualSz", "717");
            item.putArray("ordIdList").addObject().put("ordId", "child-1");
            item.withArray("ordIdList").add("child-2");
            return root;
        }
    }
}

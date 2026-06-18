package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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

    private static class CurrentOrderGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
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
}

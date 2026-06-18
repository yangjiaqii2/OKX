package com.example.quant.okxtrade;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public interface OkxOrderGateway {
    default JsonNode setLeverage(Map<String, String> payload) {
        return null;
    }

    JsonNode placeOrder(Map<String, String> payload);

    default JsonNode queryOrder(Map<String, String> payload) {
        return null;
    }

    default JsonNode currentOrders(Map<String, String> payload) {
        return null;
    }

    default JsonNode currentAlgoOrders(Map<String, String> payload) {
        return null;
    }

    default JsonNode placeAlgoOrder(Map<String, String> payload) {
        return null;
    }

    default JsonNode cancelOrder(Map<String, String> payload) {
        return null;
    }

    default JsonNode cancelAlgoOrder(Map<String, String> payload) {
        return null;
    }

    default JsonNode closePosition(Map<String, String> payload) {
        return null;
    }
}

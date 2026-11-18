package com.example.quant.okxtrade;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public interface OkxOrderGateway {
    default boolean hasCredentials() {
        return true;
    }

    default JsonNode setLeverage(Map<String, String> payload) {
        return null;
    }

    JsonNode placeOrder(Map<String, Object> payload);

    default JsonNode placeOrder(Map<String, Object> payload, TradingOperationContext context) {
        return placeOrder(payload);
    }

    default JsonNode queryOrder(Map<String, String> payload) {
        return null;
    }

    default JsonNode queryOrderHistory(Map<String, String> payload) {
        return null;
    }

    default JsonNode currentOrders(Map<String, String> payload) {
        return null;
    }

    default JsonNode fills(Map<String, String> payload) {
        return null;
    }

    default JsonNode currentAlgoOrders(Map<String, String> payload) {
        return null;
    }

    default JsonNode queryAlgoOrder(Map<String, String> payload) {
        return null;
    }

    default JsonNode placeAlgoOrder(Map<String, String> payload) {
        return null;
    }

    default JsonNode placeAlgoOrder(Map<String, String> payload, TradingOperationContext context) {
        return placeAlgoOrder(payload);
    }

    default JsonNode cancelOrder(Map<String, String> payload) {
        return null;
    }

    default JsonNode cancelAlgoOrder(Map<String, String> payload) {
        return null;
    }

    /**
     * In-place change of an existing TP/SL algo order
     * (POST /api/v5/trade/amend-algos). Only supported for stop-loss /
     * take-profit algo orders; never for move_order_stop.
     */
    default JsonNode amendAlgoOrder(Map<String, String> payload) {
        return null;
    }

    default JsonNode amendAlgoOrder(Map<String, String> payload, TradingOperationContext context) {
        return amendAlgoOrder(payload);
    }

    default JsonNode closePosition(Map<String, String> payload) {
        return null;
    }

    default JsonNode closePosition(Map<String, String> payload, TradingOperationContext context) {
        return closePosition(payload);
    }
}

package com.example.quant.okxtrade;

import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.PendingOrder;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OkxTradeAdapter {
    private final OkxOrderGateway okxOrderGateway;

    public OkxTradeAdapter(OkxOrderGateway okxOrderGateway) {
        this.okxOrderGateway = okxOrderGateway;
    }

    public OrderExecutionResult placeOrder(PendingOrder order) {
        JsonNode response = okxOrderGateway.placeOrder(orderPayload(order));
        JsonNode data = response.path("data");
        if (!data.isArray() || data.isEmpty() || data.get(0).path("ordId").asText().isBlank()) {
            throw new IllegalStateException("OKX order response does not contain ordId");
        }
        String orderId = data.get(0).path("ordId").asText();
        return new OrderExecutionResult(true, true, orderId, "OKX实盘订单已提交");
    }

    private static Map<String, String> orderPayload(PendingOrder order) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("tdMode", order.tdMode());
        payload.put("side", order.side());
        payload.put("posSide", order.posSide());
        payload.put("ordType", order.orderType().toLowerCase());
        payload.put("sz", value(order.size()));
        if (!"market".equalsIgnoreCase(order.orderType())) {
            payload.put("px", value(order.price()));
        }
        return payload;
    }

    private static String value(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}

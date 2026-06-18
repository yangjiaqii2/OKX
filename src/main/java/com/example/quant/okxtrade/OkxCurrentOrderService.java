package com.example.quant.okxtrade;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Service;

@Service
public class OkxCurrentOrderService {
    private final OkxOrderGateway okxOrderGateway;

    public OkxCurrentOrderService(OkxOrderGateway okxOrderGateway) {
        this.okxOrderGateway = okxOrderGateway;
    }

    public List<OkxCurrentOrderView> currentOrders() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instType", "SWAP");
        JsonNode data = data(okxOrderGateway.currentOrders(payload));
        return StreamSupport.stream(data.spliterator(), false)
                .map(this::normalOrder)
                .toList();
    }

    public List<OkxCurrentOrderView> currentAlgoOrders() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instType", "SWAP");
        payload.put("ordType", "conditional");
        JsonNode data = data(okxOrderGateway.currentAlgoOrders(payload));
        return StreamSupport.stream(data.spliterator(), false)
                .map(this::algoOrder)
                .toList();
    }

    private OkxCurrentOrderView normalOrder(JsonNode item) {
        return new OkxCurrentOrderView(
                item.path("instId").asText(""),
                item.path("ordId").asText(""),
                null,
                item.path("clOrdId").asText(""),
                null,
                item.path("reduceOnly").asBoolean(false) ? "REDUCE_ONLY" : "ENTRY",
                item.path("side").asText(""),
                item.path("posSide").asText(""),
                item.path("ordType").asText(""),
                bool(item, "reduceOnly"),
                decimal(item, "sz"),
                decimal(item, "px"),
                BigDecimal.ZERO,
                item.path("state").asText(""),
                time(item.path("cTime").asText(""))
        );
    }

    private OkxCurrentOrderView algoOrder(JsonNode item) {
        return new OkxCurrentOrderView(
                item.path("instId").asText(""),
                null,
                item.path("algoId").asText(""),
                null,
                item.path("algoClOrdId").asText(""),
                algoRole(item),
                item.path("side").asText(""),
                item.path("posSide").asText(""),
                item.path("ordType").asText(""),
                true,
                decimal(item, "sz"),
                BigDecimal.ZERO,
                triggerPrice(item),
                item.path("state").asText(""),
                time(item.path("cTime").asText(""))
        );
    }

    private static String algoRole(JsonNode item) {
        String clOrdId = item.path("algoClOrdId").asText("").toLowerCase();
        if (hasText(item.path("slTriggerPx").asText("")) || clOrdId.endsWith("sl")) {
            return "STOP_LOSS";
        }
        if (clOrdId.endsWith("tp1")) {
            return "TP1";
        }
        if (clOrdId.endsWith("tp2")) {
            return "TP2";
        }
        if (clOrdId.endsWith("tp3")) {
            return "TP3";
        }
        return "TAKE_PROFIT";
    }

    private static BigDecimal triggerPrice(JsonNode item) {
        BigDecimal sl = decimal(item, "slTriggerPx");
        return sl.signum() > 0 ? sl : decimal(item, "tpTriggerPx");
    }

    private static JsonNode data(JsonNode root) {
        JsonNode data = root == null ? null : root.path("data");
        return data != null && data.isArray() ? data : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (!hasText(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static boolean bool(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static Instant time(String epochMillis) {
        if (!hasText(epochMillis)) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(epochMillis));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

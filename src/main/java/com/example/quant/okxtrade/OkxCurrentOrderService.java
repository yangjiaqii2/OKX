package com.example.quant.okxtrade;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    public boolean hasCredentials() {
        return okxOrderGateway.hasCredentials();
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
                decimal(item, "accFillSz"),
                decimal(item, "px"),
                decimal(item, "avgPx"),
                BigDecimal.ZERO,
                item.path("state").asText(""),
                time(item.path("cTime").asText("")),
                time(item.path("uTime").asText(""))
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
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                triggerPrice(item),
                item.path("state").asText(""),
                time(item.path("cTime").asText("")),
                time(item.path("uTime").asText(""))
        );
    }

    public OrderDetail orderDetail(String instId, String ordId, String clOrdId) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", instId);
        payload.put("ordId", ordId == null ? "" : ordId);
        payload.put("clOrdId", clOrdId == null ? "" : clOrdId);
        JsonNode data = data(okxOrderGateway.queryOrder(payload));
        if (data.isEmpty()) {
            return null;
        }
        return orderDetail(data.get(0));
    }

    public OrderDetail orderHistory(String instId, String ordId, String clOrdId) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instType", "SWAP");
        payload.put("instId", instId);
        payload.put("ordId", ordId == null ? "" : ordId);
        payload.put("clOrdId", clOrdId == null ? "" : clOrdId);
        JsonNode data = data(okxOrderGateway.queryOrderHistory(payload));
        return data.isEmpty() ? null : orderDetail(data.get(0));
    }

    private static OrderDetail orderDetail(JsonNode item) {
        return new OrderDetail(
                item.path("ordId").asText(""),
                item.path("clOrdId").asText(""),
                item.path("state").asText(""),
                decimal(item, "sz"),
                decimal(item, "accFillSz"),
                decimal(item, "avgPx"),
                time(item.path("uTime").asText(""))
        );
    }

    public AlgoOrderDetail algoOrderDetail(String algoId, String algoClOrdId) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("algoId", algoId == null ? "" : algoId);
        payload.put("algoClOrdId", algoClOrdId == null ? "" : algoClOrdId);
        JsonNode data = data(okxOrderGateway.queryAlgoOrder(payload));
        if (data.isEmpty()) {
            return null;
        }
        JsonNode item = data.get(0);
        return new AlgoOrderDetail(
                item.path("algoId").asText(""),
                item.path("state").asText(""),
                decimal(item, "sz"),
                decimal(item, "actualSz"),
                decimal(item, "actualPx"),
                time(item.path("uTime").asText("")),
                item.path("failCode").asText(""),
                childOrderIds(item)
        );
    }

    public List<Fill> fills(String instId, String ordId) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instType", "SWAP");
        payload.put("instId", instId);
        payload.put("ordId", ordId);
        return StreamSupport.stream(data(okxOrderGateway.fills(payload)).spliterator(), false)
                .map(item -> new Fill(
                        item.path("tradeId").asText(""),
                        item.path("side").asText(""),
                        item.path("posSide").asText(""),
                        decimal(item, "fillPx"),
                        decimal(item, "fillSz"),
                        nullableDecimal(item, "fee"),
                        item.path("feeCcy").asText(""),
                        nullableDecimal(item, "fillPnl"),
                        time(item.path("ts").asText(""))))
                .toList();
    }

    public record OrderDetail(String ordId, String clOrdId, String state, BigDecimal requestedSize, BigDecimal filledSize,
                              BigDecimal averageFillPrice, Instant updatedAt) {
    }

    public record AlgoOrderDetail(String algoId, String state, BigDecimal requestedSize, BigDecimal actualSize,
                                  BigDecimal actualPrice, Instant updatedAt, String failCode,
                                  List<String> childOrderIds) {
    }

    public record Fill(String tradeId, String side, String posSide, BigDecimal fillPrice,
                       BigDecimal fillSize, BigDecimal fee, String feeCurrency,
                       BigDecimal fillPnl, Instant timestamp) {
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

    private static List<String> childOrderIds(JsonNode item) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        addText(ids, item.path("ordId").asText(""));
        JsonNode list = item.path("ordIdList");
        if (list.isArray()) {
            for (JsonNode child : list) {
                if (child.isTextual() || child.isNumber()) {
                    addText(ids, child.asText(""));
                } else {
                    addText(ids, child.path("ordId").asText(""));
                }
            }
        }
        return List.copyOf(ids);
    }

    private static void addText(LinkedHashSet<String> values, String value) {
        if (hasText(value)) {
            values.add(value.trim());
        }
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

    private static BigDecimal nullableDecimal(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (!hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
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

package com.example.quant.okxtrade;

import com.example.quant.agent.execution.TradeOrderRecordService;
import com.example.quant.agent.execution.TradeOrderRecordService.OrderRecordPayload;
import com.example.quant.config.AgentProperties;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.PendingOrder;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OkxTradeAdapter {
    private static final Logger log = LoggerFactory.getLogger(OkxTradeAdapter.class);

    private final OkxOrderGateway okxOrderGateway;
    private final TradeOrderRecordService tradeOrderRecordService;
    private final OkxInstrumentRulesProvider instrumentRulesProvider;
    private final OkxPositionModeProvider positionModeProvider;
    private final AgentProperties agentProperties;

    @Autowired
    public OkxTradeAdapter(OkxOrderGateway okxOrderGateway, TradeOrderRecordService tradeOrderRecordService,
                           OkxInstrumentRulesProvider instrumentRulesProvider,
                           OkxPositionModeProvider positionModeProvider,
                           AgentProperties agentProperties) {
        this.okxOrderGateway = okxOrderGateway;
        this.tradeOrderRecordService = tradeOrderRecordService;
        this.instrumentRulesProvider = instrumentRulesProvider == null
                ? OkxInstrumentRules::defaultFor
                : instrumentRulesProvider;
        this.positionModeProvider = positionModeProvider == null
                ? () -> OkxPositionMode.LONG_SHORT
                : positionModeProvider;
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
    }

    public OkxTradeAdapter(OkxOrderGateway okxOrderGateway) {
        this(okxOrderGateway, null, OkxInstrumentRules::defaultFor, () -> OkxPositionMode.LONG_SHORT, new AgentProperties());
    }

    public OkxTradeAdapter(OkxOrderGateway okxOrderGateway, OkxInstrumentRulesProvider instrumentRulesProvider) {
        this(okxOrderGateway, null, instrumentRulesProvider, () -> OkxPositionMode.LONG_SHORT, new AgentProperties());
    }

    public OkxTradeAdapter(OkxOrderGateway okxOrderGateway, OkxInstrumentRulesProvider instrumentRulesProvider,
                           OkxPositionModeProvider positionModeProvider) {
        this(okxOrderGateway, null, instrumentRulesProvider, positionModeProvider, new AgentProperties());
    }

    public OrderExecutionResult placeOrder(PendingOrder order) {
        OkxInstrumentRules rules = instrumentRulesProvider.rules(order.instId());
        if (rules == null) {
            rules = OkxInstrumentRules.defaultFor(order.instId());
        }
        OkxPositionMode positionMode = positionModeProvider.positionMode();
        if (positionMode == null) {
            positionMode = OkxPositionMode.NET;
        }
        setLeverage(order, positionMode);
        Map<String, String> payload = orderPayload(order, rules, positionMode);
        OrderRecordPayload entryRecord = entryRecord(order, payload, null);
        log.info("Calling OKX trade order instId={} side={} posSide={} posMode={} ordType={} tdMode={} sz={} px={} clOrdId={}",
                payload.get("instId"), payload.get("side"), payload.getOrDefault("posSide", ""), positionMode,
                payload.get("ordType"),
                payload.get("tdMode"), payload.get("sz"), payload.getOrDefault("px", ""), payload.get("clOrdId"));
        try {
            JsonNode response = okxOrderGateway.placeOrder(payload);
            String orderId = orderId(response, "ordId");
            log.info("OKX trade order accepted instId={} ordId={} clOrdId={}", order.instId(), orderId, payload.get("clOrdId"));
            recordSubmitted(order, entryRecord.withOkxOrdId(orderId));
            if (!"market".equalsIgnoreCase(order.orderType())) {
                return new OrderExecutionResult(true, true, orderId,
                        "入场委托已提交，等待成交",
                        true, false, false, false);
            }
            ProtectionPlanResult protectionPlan = protectionPlan(order, rules, positionMode, orderId, new BigDecimal(payload.get("sz")));
            if (!protectionPlan.valid()) {
                String reason = protectionPlan.failureReason();
                if ("ENTRY_FILL_NOT_CONFIRMED".equals(reason)) {
                    return new OrderExecutionResult(true, true, orderId,
                            "ENTRY_FILL_UNCONFIRMED: OKX委托已提交，订单号 " + orderId
                                    + "，但成交数量未确认，等待生命周期恢复查询。",
                            true, false, false, false);
                }
                log.error("OKX protection plan invalid instId={} ordId={} reason={}", order.instId(), orderId, reason);
                if ("CLOSE_POSITION".equalsIgnoreCase(agentProperties.protection().protectionFailAction())) {
                    closePosition(order.instId(), order.posSide(), order.tdMode());
                    return new OrderExecutionResult(true, true, orderId,
                            "OKX委托已成交，订单号 " + orderId + "；保护单未提交：" + reason + "，已请求平仓保护。",
                            true, true, false, false);
                }
                return new OrderExecutionResult(true, true, orderId,
                        "OKX委托已成交，订单号 " + orderId + "；保护单未提交：" + reason + "，需要人工处理。",
                        true, true, false, false);
            }
            ProtectionSubmitResult protection = placeProtectionOrders(order, protectionPlan.payloads());
            return new OrderExecutionResult(true, true, orderId,
                    protection.submitted() > 0 || protection.failed() > 0
                            ? "OKX委托已成交，订单号 " + orderId + "；保护单已提交 " + protection.submitted()
                            + " 个，失败 " + protection.failed() + " 个。"
                            : "OKX委托已成交，订单号 " + orderId + "，未生成保护单，请人工确认。",
                    true, true, false, false);
        } catch (RuntimeException ex) {
            if (isTimeout(ex)) {
                OrderExecutionResult recovered = recoverTimedOutSubmit(order, payload, entryRecord, ex);
                if (recovered != null) {
                    return recovered;
                }
                throw ex;
            }
            recordFailed(order, entryRecord, ex.getMessage());
            throw ex;
        }
    }

    public OrderExecutionResult recoverUnknownSubmitStatus(PendingOrder order) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("clOrdId", order.clientOrderId());
        JsonNode response = okxOrderGateway.queryOrder(payload);
        JsonNode item = firstDataItem(response);
        if (item == null) {
            return new OrderExecutionResult(false, true, null,
                    "OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT: " + order.clientOrderId());
        }
        String ordId = item.path("ordId").asText("");
        String state = item.path("state").asText("");
        if (ordId.isBlank()) {
            return new OrderExecutionResult(false, true, null,
                    "OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT: " + order.clientOrderId());
        }
        BigDecimal filledSize = decimal(item, "accFillSz");
        boolean filled = filledSize.signum() > 0 && "filled".equalsIgnoreCase(state);
        boolean partiallyFilled = filledSize.signum() > 0 && !filled;
        return new OrderExecutionResult(true, true, ordId,
                "OKX clOrdId recovered: clOrdId=" + order.clientOrderId() + ", state=" + state,
                true, filled, partiallyFilled, false);
    }

    private OrderExecutionResult recoverTimedOutSubmit(PendingOrder order, Map<String, String> payload,
                                                       OrderRecordPayload entryRecord, RuntimeException timeout) {
        String clOrdId = payload.get("clOrdId");
        if (!hasText(clOrdId)) {
            return null;
        }
        Map<String, String> queryPayload = new LinkedHashMap<>();
        queryPayload.put("instId", order.instId());
        queryPayload.put("clOrdId", clOrdId);
        try {
            JsonNode response = okxOrderGateway.queryOrder(queryPayload);
            JsonNode item = firstDataItem(response);
            if (item == null) {
                return null;
            }
            String ordId = item.path("ordId").asText("");
            String state = item.path("state").asText("");
            if (!hasText(ordId)) {
                return null;
            }
            recordSubmitted(order, entryRecord.withOkxOrdId(ordId));
            log.warn("OKX order submit timeout recovered by clOrdId instId={} clOrdId={} ordId={} state={}",
                    order.instId(), clOrdId, ordId, state);
            BigDecimal filledSize = decimal(item, "accFillSz");
            boolean filled = filledSize.signum() > 0 && "filled".equalsIgnoreCase(state);
            boolean partiallyFilled = filledSize.signum() > 0 && !filled;
            return new OrderExecutionResult(true, true, ordId,
                    "OKX提交超时后已通过clOrdId恢复：clOrdId=" + clOrdId + "，订单号 " + ordId + "，state=" + state,
                    true, filled, partiallyFilled, false);
        } catch (RuntimeException queryEx) {
            timeout.addSuppressed(queryEx);
            return null;
        }
    }

    public OrderExecutionResult closePosition(String instId, String posSide, String marginMode) {
        if (instId == null || instId.isBlank()) {
            throw new IllegalArgumentException("平仓合约不能为空");
        }
        OkxPositionMode positionMode = positionModeProvider.positionMode();
        if (positionMode == null) {
            positionMode = OkxPositionMode.NET;
        }
        Map<String, String> payload = closePositionPayload(instId, posSide, marginMode, positionMode);
        log.warn("Calling OKX close-position instId={} posSide={} posMode={} mgnMode={}",
                payload.get("instId"), payload.getOrDefault("posSide", ""), positionMode, payload.get("mgnMode"));
        JsonNode response = okxOrderGateway.closePosition(payload);
        String closeId = closePositionId(response, instId, payload.getOrDefault("posSide", ""));
        return new OrderExecutionResult(true, true, closeId,
                "OKX一键平仓请求已提交：" + instId + " " + payload.getOrDefault("posSide", "net")
                        + "。请在OKX当前委托/持仓确认最终成交。");
    }

    private void setLeverage(PendingOrder order, OkxPositionMode positionMode) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("lever", String.valueOf(order.leverage()));
        payload.put("mgnMode", order.tdMode());
        if (positionMode == OkxPositionMode.LONG_SHORT) {
            payload.put("posSide", order.posSide());
        }
        okxOrderGateway.setLeverage(payload);
    }

    private ProtectionSubmitResult placeProtectionOrders(PendingOrder order, List<Map<String, String>> protectionOrders) {
        int submitted = 0;
        int failed = 0;
        for (Map<String, String> payload : protectionOrders) {
            OrderRecordPayload recordPayload = protectionRecord(order, payload, null);
            try {
                JsonNode response = okxOrderGateway.placeAlgoOrder(apiPayload(payload));
                String algoId = orderId(response, "algoId");
                recordSubmitted(order, recordPayload.withOkxOrdId(algoId));
                submitted++;
            } catch (RuntimeException ex) {
                failed++;
                recordFailed(order, recordPayload, ex.getMessage());
                log.warn("OKX protection order failed instId={} role={} clOrdId={} message={}",
                        order.instId(), payload.get("orderRole"), payload.get("algoClOrdId"), ex.getMessage());
            }
        }
        return new ProtectionSubmitResult(submitted, failed);
    }

    static Map<String, String> orderPayload(PendingOrder order, OkxInstrumentRules rules, OkxPositionMode positionMode) {
        BigDecimal contractSize = rules.normalizeEntrySize(order.size(), order.price());
        if (contractSize.signum() <= 0) {
            throw new IllegalStateException("OKX下单数量无效，无法按合约步进规整");
        }
        BigDecimal rawContractSize = rules.contractSizeFromBaseQuantity(order.size(), order.price());
        if (rawContractSize.compareTo(contractSize) != 0) {
            log.warn("OKX order size normalized instId={} baseSize={} rawContracts={} finalContracts={} lotSz={} minSz={}",
                    order.instId(), value(order.size()), value(rawContractSize), value(contractSize),
                    value(rules.lotSize()), value(rules.minSize()));
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("tdMode", order.tdMode());
        payload.put("side", order.side());
        if (positionMode == OkxPositionMode.LONG_SHORT) {
            payload.put("posSide", order.posSide());
        }
        payload.put("ordType", order.orderType().toLowerCase());
        payload.put("sz", value(contractSize));
        payload.put("clOrdId", hasText(order.clientOrderId()) ? order.clientOrderId() : clientOrderId(order.id(), "e"));
        if (!"market".equalsIgnoreCase(order.orderType())) {
            payload.put("px", value(rules.normalizePrice(order.price())));
        }
        return payload;
    }

    static Map<String, String> closePositionPayload(String instId, String posSide, String marginMode,
                                                    OkxPositionMode positionMode) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", instId);
        payload.put("mgnMode", hasText(marginMode) ? marginMode : "cross");
        payload.put("autoCxl", "true");
        if (positionMode == OkxPositionMode.LONG_SHORT && hasText(posSide) && !"net".equalsIgnoreCase(posSide)) {
            payload.put("posSide", posSide);
        }
        return payload;
    }

    private ProtectionPlanResult protectionPlan(PendingOrder order, OkxInstrumentRules rules,
                                                OkxPositionMode positionMode, String orderId,
                                                BigDecimal submittedContractSize) {
        OkxOrderFill fill = protectionFill(order, orderId, submittedContractSize);
        if (!fill.complete()) {
            return new ProtectionPlanResult(false, "ENTRY_FILL_NOT_CONFIRMED", fill, List.of());
        }
        if ("market".equalsIgnoreCase(order.orderType())) {
            BigDecimal deviationPct = fill.avgPx().subtract(order.price()).abs()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(order.price(), 8, RoundingMode.HALF_UP);
            if (deviationPct.compareTo(agentProperties.protection().maxEntryPriceDeviationPct()) > 0) {
                return new ProtectionPlanResult(false,
                        "ENTRY_PRICE_DEVIATION_TOO_WIDE_" + deviationPct + "%", fill, List.of());
            }
            BigDecimal stopLossPct = fill.avgPx().subtract(order.stopLossPrice()).abs()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(fill.avgPx(), 8, RoundingMode.HALF_UP);
            if (stopLossPct.compareTo(agentProperties.protection().maxStopLossPctAfterFill()) > 0) {
                return new ProtectionPlanResult(false,
                        "STOP_LOSS_PCT_TOO_WIDE_AFTER_FILL_" + stopLossPct + "%", fill, List.of());
            }
        }
        return new ProtectionPlanResult(true, null, fill,
                protectionPayloads(order, rules, positionMode, fill.filledSize(), fill.avgPx()));
    }

    private OkxOrderFill protectionFill(PendingOrder order, String orderId, BigDecimal submittedContractSize) {
        if (!"market".equalsIgnoreCase(order.orderType()) || !agentProperties.protection().recalcAfterFill()) {
            return new OkxOrderFill(orderId, order.price(), submittedContractSize);
        }
        Map<String, String> queryPayload = new LinkedHashMap<>();
        queryPayload.put("instId", order.instId());
        queryPayload.put("ordId", orderId);
        JsonNode root = okxOrderGateway.queryOrder(queryPayload);
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return new OkxOrderFill(orderId, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        JsonNode item = data.get(0);
        return new OkxOrderFill(
                orderId,
                decimal(item, "avgPx"),
                decimal(item, "accFillSz")
        );
    }

    private List<Map<String, String>> protectionPayloads(PendingOrder order, OkxInstrumentRules rules,
                                                         OkxPositionMode positionMode,
                                                         BigDecimal filledContractSize,
                                                         BigDecimal avgPx) {
        List<Map<String, String>> payloads = new ArrayList<>();
        BigDecimal normalizedFilledSize = rules.floorToLot(filledContractSize);
        payloads.add(stopLossPayload(order, rules, positionMode, normalizedFilledSize));
        List<TakeProfitLeg> legs = takeProfitLegs(normalizedFilledSize, rules);
        BigDecimal risk = avgPx.subtract(order.stopLossPrice()).abs();
        for (TakeProfitLeg leg : legs) {
            BigDecimal price = takeProfitPrice(order, avgPx, risk, leg.rMultiple());
            payloads.add(takeProfitPayload(order, rules, positionMode, leg.suffix(), leg.size(), price));
        }
        return payloads.stream()
                .filter(payload -> new BigDecimal(payload.get("sz")).signum() > 0)
                .toList();
    }

    private List<TakeProfitLeg> takeProfitLegs(BigDecimal filledSize, OkxInstrumentRules rules) {
        BigDecimal tp1 = rules.floorToLot(filledSize.multiply(agentProperties.takeProfit().tp1Ratio()));
        BigDecimal tp2 = rules.floorToLot(filledSize.multiply(agentProperties.takeProfit().tp2Ratio()));
        if (tp1.compareTo(rules.minSize()) < 0) {
            tp1 = BigDecimal.ZERO;
        }
        if (tp2.compareTo(rules.minSize()) < 0) {
            tp2 = BigDecimal.ZERO;
        }
        BigDecimal tp3 = rules.floorToLot(filledSize.subtract(tp1).subtract(tp2));
        if (tp3.compareTo(rules.minSize()) < 0) {
            if (tp2.compareTo(rules.minSize()) >= 0) {
                tp2 = tp2.add(tp3);
            } else if (tp1.compareTo(rules.minSize()) >= 0) {
                tp1 = tp1.add(tp3);
            } else {
                tp3 = filledSize;
            }
            if (!filledSize.equals(tp3)) {
                tp3 = BigDecimal.ZERO;
            }
        }
        List<TakeProfitLeg> legs = new ArrayList<>();
        if (tp1.compareTo(rules.minSize()) >= 0) {
            legs.add(new TakeProfitLeg("tp1", tp1, BigDecimal.ONE));
        }
        if (tp2.compareTo(rules.minSize()) >= 0) {
            legs.add(new TakeProfitLeg("tp2", tp2, BigDecimal.valueOf(2)));
        }
        if (tp3.compareTo(rules.minSize()) >= 0) {
            legs.add(new TakeProfitLeg("tp3", tp3, BigDecimal.valueOf(3)));
        }
        return legs;
    }

    private static Map<String, String> stopLossPayload(PendingOrder order, OkxInstrumentRules rules,
                                                       OkxPositionMode positionMode,
                                                       BigDecimal entryContractSize) {
        Map<String, String> payload = baseAlgoPayload(order, positionMode, "sl");
        payload.put("orderRole", "STOP_LOSS");
        payload.put("sz", value(entryContractSize));
        payload.put("slTriggerPx", value(rules.normalizePrice(order.stopLossPrice())));
        payload.put("slOrdPx", "-1");
        return payload;
    }

    private static Map<String, String> takeProfitPayload(PendingOrder order, OkxInstrumentRules rules,
                                                         OkxPositionMode positionMode, String suffix,
                                                         BigDecimal size, BigDecimal price) {
        Map<String, String> payload = baseAlgoPayload(order, positionMode, suffix);
        payload.put("orderRole", suffix.toUpperCase());
        payload.put("sz", value(size));
        payload.put("tpTriggerPx", value(rules.normalizePrice(price)));
        payload.put("tpOrdPx", "-1");
        return payload;
    }

    private static Map<String, String> baseAlgoPayload(PendingOrder order, OkxPositionMode positionMode,
                                                       String suffix) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("tdMode", order.tdMode());
        payload.put("side", closeSide(order));
        if (positionMode == OkxPositionMode.LONG_SHORT) {
            payload.put("posSide", order.posSide());
        }
        payload.put("ordType", "conditional");
        payload.put("reduceOnly", "true");
        payload.put("algoClOrdId", clientOrderId(order.id(), suffix));
        return payload;
    }

    private static Map<String, String> apiPayload(Map<String, String> payload) {
        Map<String, String> apiPayload = new LinkedHashMap<>(payload);
        apiPayload.remove("orderRole");
        return apiPayload;
    }

    private static BigDecimal takeProfitPrice(PendingOrder order, BigDecimal avgPx, BigDecimal risk, BigDecimal multiple) {
        BigDecimal distance = risk.multiply(multiple);
        return "short".equalsIgnoreCase(order.posSide()) ? avgPx.subtract(distance) : avgPx.add(distance);
    }

    private static String closeSide(PendingOrder order) {
        return "buy".equalsIgnoreCase(order.side()) ? "sell" : "buy";
    }

    private void recordSubmitted(PendingOrder order, OrderRecordPayload payload) {
        if (tradeOrderRecordService != null) {
            tradeOrderRecordService.recordSubmitted(order, payload);
        }
    }

    private void recordFailed(PendingOrder order, OrderRecordPayload payload, String message) {
        if (tradeOrderRecordService != null) {
            tradeOrderRecordService.recordFailed(order, payload, message);
        }
    }

    private static OrderRecordPayload entryRecord(PendingOrder order, Map<String, String> payload, String okxOrdId) {
        return new OrderRecordPayload(
                "ENTRY",
                payload.get("side"),
                order.posSide(),
                payload.get("ordType"),
                new BigDecimal(payload.get("sz")),
                payload.containsKey("px") ? new BigDecimal(payload.get("px")) : order.price(),
                false,
                payload.get("clOrdId"),
                okxOrdId
        );
    }

    private static OrderRecordPayload protectionRecord(PendingOrder order, Map<String, String> payload, String okxOrdId) {
        return new OrderRecordPayload(
                payload.get("orderRole"),
                payload.get("side"),
                order.posSide(),
                payload.get("ordType"),
                new BigDecimal(payload.get("sz")),
                triggerPrice(payload),
                true,
                payload.getOrDefault("algoClOrdId", payload.get("clOrdId")),
                okxOrdId
        );
    }

    private static BigDecimal triggerPrice(Map<String, String> payload) {
        String value = payload.getOrDefault("slTriggerPx", payload.getOrDefault("tpTriggerPx", "0"));
        return new BigDecimal(value);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = node == null ? "" : node.path(field).asText("");
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static JsonNode firstDataItem(JsonNode response) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return null;
        }
        return data.get(0);
    }

    private static boolean isTimeout(RuntimeException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        return message.contains("timeout") || message.contains("timed out") || message.contains("超时");
    }

    private static String orderId(JsonNode response, String preferredField) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("OKX order response does not contain data");
        }
        JsonNode item = data.get(0);
        String sCode = item.path("sCode").asText("");
        if (!sCode.isBlank() && !"0".equals(sCode)) {
            String sMsg = item.path("sMsg").asText("");
            throw new IllegalStateException("OKX order rejected: sCode=" + sCode + ", sMsg=" + sMsg);
        }
        String id = item.path(preferredField).asText("");
        if (id.isBlank()) {
            id = item.path("ordId").asText(item.path("algoId").asText(""));
        }
        if (id.isBlank()) {
            throw new IllegalStateException("OKX order response does not contain order id");
        }
        return id;
    }

    private static String closePositionId(JsonNode response, String instId, String posSide) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("OKX close-position response does not contain data");
        }
        JsonNode item = data.get(0);
        String sCode = item.path("sCode").asText("");
        if (!sCode.isBlank() && !"0".equals(sCode)) {
            String sMsg = item.path("sMsg").asText("");
            throw new IllegalStateException("OKX close-position rejected: sCode=" + sCode + ", sMsg=" + sMsg);
        }
        String id = item.path("ordId").asText("");
        if (!id.isBlank()) {
            return id;
        }
        String responseInstId = item.path("instId").asText(instId);
        String responsePosSide = item.path("posSide").asText(posSide);
        return responseInstId + ":" + (hasText(responsePosSide) ? responsePosSide : "net");
    }

    private static String clientOrderId(UUID orderId, String suffix) {
        String compact = orderId.toString().replace("-", "");
        String cleanSuffix = suffix == null ? "" : suffix.replaceAll("[^A-Za-z0-9]", "");
        int suffixLength = cleanSuffix.length();
        int prefixLength = Math.max(1, Math.min(30 - suffixLength, compact.length()));
        return "qa" + compact.substring(0, prefixLength) + cleanSuffix;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String value(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record ProtectionSubmitResult(int submitted, int failed) {
    }

    private record TakeProfitLeg(String suffix, BigDecimal size, BigDecimal rMultiple) {
    }
}

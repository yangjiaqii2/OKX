package com.example.quant.okxtrade;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.agent.execution.TradeOrderRecordService;
import com.example.quant.agent.execution.TradeOrderRecordService.OrderRecordPayload;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.config.TradingMode;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.PendingOrder;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import com.example.quant.tradeplan.CanonicalPlanCompiler;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
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
    private final TradingMode tradingMode;
    private CanonicalPlanCompiler canonicalPlanCompiler;

    @Autowired
    public OkxTradeAdapter(OkxOrderGateway okxOrderGateway, TradeOrderRecordService tradeOrderRecordService,
                           OkxInstrumentRulesProvider instrumentRulesProvider,
                           OkxPositionModeProvider positionModeProvider,
                           AgentProperties agentProperties,
                           TradingProperties tradingProperties) {
        this.okxOrderGateway = okxOrderGateway;
        this.tradeOrderRecordService = tradeOrderRecordService;
        this.instrumentRulesProvider = instrumentRulesProvider == null
                ? OkxInstrumentRules::defaultFor
                : instrumentRulesProvider;
        this.positionModeProvider = positionModeProvider == null
                ? () -> OkxPositionMode.LONG_SHORT
                : positionModeProvider;
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
        this.tradingMode = tradingProperties == null ? TradingMode.DISABLED : tradingProperties.mode();
    }

    @Autowired(required = false)
    public void setCanonicalPlanCompiler(CanonicalPlanCompiler canonicalPlanCompiler) {
        this.canonicalPlanCompiler = canonicalPlanCompiler;
    }

    public OkxTradeAdapter(OkxOrderGateway okxOrderGateway, TradeOrderRecordService tradeOrderRecordService,
                           OkxInstrumentRulesProvider instrumentRulesProvider,
                           OkxPositionModeProvider positionModeProvider,
                           AgentProperties agentProperties) {
        this(okxOrderGateway, tradeOrderRecordService, instrumentRulesProvider,
                positionModeProvider, agentProperties,
                new TradingProperties(TradingMode.LIVE, true, 120, false));
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
        OkxInstrumentRules rules;
        OkxPositionMode positionMode;
        Map<String, Object> payload;
        OrderRecordPayload entryRecord;
        try {
            requireTradingEnabled();
            rules = instrumentRulesProvider.rules(order.instId());
            if (rules == null) {
                throw new IllegalStateException("无法加载OKX合约规则，禁止下单：" + order.instId());
            }
            if (canonicalPlanCompiler != null) {
                CanonicalExecutablePlan canonicalPlan = order.canonicalExecutablePlan();
                if (canonicalPlan == null
                        || canonicalPlan.status() != CanonicalExecutablePlan.CompilationStatus.ACCEPTED) {
                    throw new IllegalStateException("CANONICAL_PLAN_MISSING");
                }
                if (order.executableContractQuantity() == null
                        || canonicalPlan.entry().executableContractQuantity()
                        .compareTo(order.executableContractQuantity()) != 0) {
                    throw new IllegalStateException("CANONICAL_ENTRY_QUANTITY_MISMATCH");
                }
                if (!canonicalPlanCompiler.matchesSnapshot(canonicalPlan, rules)) {
                    throw new IllegalStateException("CANONICAL_PLAN_RULES_CHANGED");
                }
            }
            positionMode = positionModeProvider.positionMode();
            if (positionMode == null) {
                positionMode = OkxPositionMode.NET;
            }
            setLeverage(order, positionMode);
            payload = orderPayload(order, rules, positionMode);
            entryRecord = entryRecord(order, payload, null);
        } catch (RuntimeException ex) {
            throw new OrderNotSubmittedException("PRE_ORDER_SETUP_FAILED: " + compact(ex.getMessage()), ex);
        }
        log.info("Calling OKX trade order instId={} side={} posSide={} posMode={} ordType={} tdMode={} sz={} px={} clOrdId={}",
                payload.get("instId"), payload.get("side"), payload.getOrDefault("posSide", ""), positionMode,
                payload.get("ordType"),
                payload.get("tdMode"), payload.get("sz"), payload.getOrDefault("px", ""), payload.get("clOrdId"));
        String acceptedOrderId;
        try {
            JsonNode response = okxOrderGateway.placeOrder(payload);
            acceptedOrderId = orderId(response, "ordId");
        } catch (RuntimeException ex) {
            if (ex instanceof OrderNotSubmittedException || ex instanceof OkxApiRejectedException) {
                recordFailed(order, entryRecord, ex.getMessage());
                throw ex;
            }
            OrderExecutionResult recovered = recoverUncertainSubmit(order, payload, entryRecord, ex);
            if (recovered != null) return recovered;
            throw new com.example.quant.order.OrderSubmitStatusUnknownException(ex.getMessage(), ex);
        }
        log.info("OKX trade order accepted instId={} ordId={} clOrdId={}",
                order.instId(), acceptedOrderId, payload.get("clOrdId"));
        recordAccepted(order, entryRecord.withOkxOrdId(acceptedOrderId));
        return OrderExecutionResult.submitted(acceptedOrderId,
                "入场委托已提交，等待成交确认（以OKX订单状态为准）；计划止损已随单附加");
    }

    public OrderExecutionResult recoverUnknownSubmitStatus(PendingOrder order) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("clOrdId", order.clientOrderId());
        JsonNode item = findOrderByClientOrderId(payload, order.clientOrderId());
        if (item == null) {
            return new OrderExecutionResult(false, true, null,
                    "OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT: " + order.clientOrderId(),
                    false, false, false, false);
        }
        String ordId = item.path("ordId").asText("");
        String state = item.path("state").asText("");
        if (ordId.isBlank()) {
            return new OrderExecutionResult(false, true, null,
                    "OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT: " + order.clientOrderId(),
                    false, false, false, false);
        }
        BigDecimal filledSize = decimal(item, "accFillSz");
        if (filledSize.signum() <= 0) {
            filledSize = decimal(item, "fillSz");
        }
        boolean filled = filledSize.signum() > 0 && "filled".equalsIgnoreCase(state);
        boolean partiallyFilled = filledSize.signum() > 0 && !filled;
        return new OrderExecutionResult(true, true, ordId,
                "OKX clOrdId recovered: clOrdId=" + order.clientOrderId() + ", state=" + state,
                true, filled, partiallyFilled, false);
    }

    private JsonNode findOrderByClientOrderId(Map<String, String> payload, String clientOrderId) {
        try {
            JsonNode direct = matchingDataItem(okxOrderGateway.queryOrder(payload), clientOrderId);
            if (direct != null) {
                return direct;
            }
        } catch (RuntimeException ex) {
            if (!isOrderNotFound(ex)) {
                throw ex;
            }
        }
        Map<String, String> currentPayload = Map.of("instType", "SWAP");
        JsonNode current = matchingDataItem(okxOrderGateway.currentOrders(currentPayload), clientOrderId);
        if (current != null) {
            return current;
        }
        Map<String, String> historyPayload = new LinkedHashMap<>(payload);
        historyPayload.put("instType", "SWAP");
        JsonNode history = matchingDataItem(okxOrderGateway.queryOrderHistory(historyPayload), clientOrderId);
        if (history != null) {
            return history;
        }
        Map<String, String> fillPayload = new LinkedHashMap<>();
        fillPayload.put("instType", "SWAP");
        fillPayload.put("instId", payload.getOrDefault("instId", ""));
        return matchingDataItem(okxOrderGateway.fills(fillPayload), clientOrderId);
    }

    private static JsonNode matchingDataItem(JsonNode response, String clientOrderId) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return null;
        }
        for (JsonNode item : data) {
            String itemClientId = item.path("clOrdId").asText("");
            if (!hasText(clientOrderId) || clientOrderId.equals(itemClientId)) {
                return item;
            }
        }
        return null;
    }

    private static boolean isOrderNotFound(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (hasText(message) && (message.contains("51603")
                    || message.toLowerCase(java.util.Locale.ROOT).contains("order does not exist"))) {
                return true;
            }
        }
        return false;
    }

    private OrderExecutionResult recoverUncertainSubmit(PendingOrder order, Map<String, Object> payload,
                                                       OrderRecordPayload entryRecord, RuntimeException submitFailure) {
        String clOrdId = text(payload.get("clOrdId"));
        if (!hasText(clOrdId)) {
            return null;
        }
        Map<String, String> queryPayload = new LinkedHashMap<>();
        queryPayload.put("instId", order.instId());
        queryPayload.put("clOrdId", clOrdId);
        try {
            JsonNode response = okxOrderGateway.queryOrder(queryPayload);
            JsonNode item = firstDataItem(response);
            if (item == null || (hasText(item.path("clOrdId").asText(""))
                    && !clOrdId.equals(item.path("clOrdId").asText("")))) {
                return null;
            }
            String ordId = item.path("ordId").asText("");
            String state = item.path("state").asText("");
            if (!hasText(ordId)) {
                return null;
            }
            recordAccepted(order, entryRecord.withOkxOrdId(ordId));
            log.warn("OKX order submit uncertain result recovered by clOrdId instId={} clOrdId={} ordId={} state={}",
                    order.instId(), clOrdId, ordId, state);
            BigDecimal filledSize = decimal(item, "accFillSz");
            boolean filled = filledSize.signum() > 0 && "filled".equalsIgnoreCase(state);
            boolean partiallyFilled = filledSize.signum() > 0 && !filled;
            return new OrderExecutionResult(true, true, ordId,
                    "OKX提交结果不确定后已通过clOrdId恢复：clOrdId=" + clOrdId + "，订单号 " + ordId + "，state=" + state,
                    true, filled, partiallyFilled, false);
        } catch (RuntimeException queryEx) {
            submitFailure.addSuppressed(queryEx);
            return null;
        }
    }

    public void settleEntryBeforeClose(PendingOrder order) {
        if (order.submittedAt() != null && !EntryOrderReconciliation.ensureTerminal(okxOrderGateway, order)) {
            throw new IllegalStateException("ENTRY_REMAINDER_UNCONFIRMED");
        }
    }

    public OrderExecutionResult closePosition(String instId, String posSide, String marginMode) {
        return closePosition(instId, posSide, marginMode, null);
    }

    public OrderExecutionResult closePosition(String instId, String posSide, String marginMode, String clOrdId) {
        return closePosition(instId, posSide, marginMode, clOrdId,
                TradingOperationContext.of(TradingOperationType.CLOSE_POSITION,
                        currentUsername(), null, null));
    }

    public OrderExecutionResult closePosition(String instId, String posSide, String marginMode, String clOrdId,
                                              TradingOperationContext context) {
        requireTradingEnabled();
        if (instId == null || instId.isBlank()) {
            throw new IllegalArgumentException("平仓合约不能为空");
        }
        OkxPositionMode positionMode = positionModeProvider.positionMode();
        if (positionMode == null) {
            positionMode = OkxPositionMode.NET;
        }
        Map<String, String> payload = closePositionPayload(instId, posSide, marginMode, positionMode, clOrdId);
        log.warn("Calling OKX close-position instId={} posSide={} posMode={} mgnMode={} clOrdId={}",
                payload.get("instId"), payload.getOrDefault("posSide", ""), positionMode, payload.get("mgnMode"),
                payload.getOrDefault("clOrdId", ""));
        JsonNode response = okxOrderGateway.closePosition(payload, context);
        String closeId = closePositionId(response, instId, payload.getOrDefault("posSide", ""));
        return OrderExecutionResult.submitted(closeId,
                "OKX一键平仓请求已提交：" + instId + " " + payload.getOrDefault("posSide", "net")
                        + "。请在OKX当前委托/持仓确认最终成交。");
    }

    private void requireTradingEnabled() {
        if (!tradingMode.writesEnabled()) {
            throw new IllegalStateException("交易模式为DISABLED（实盘交易已禁用）；未向交易所发送请求");
        }
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
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

    static Map<String, Object> orderPayload(PendingOrder order, OkxInstrumentRules rules, OkxPositionMode positionMode) {
        BigDecimal rawContractSize = rules.contractSizeFromBaseQuantity(order.size(), order.price());
        CanonicalExecutablePlan canonicalPlan = order.canonicalExecutablePlan();
        BigDecimal contractSize = canonicalPlan != null
                && canonicalPlan.status() == CanonicalExecutablePlan.CompilationStatus.ACCEPTED
                ? canonicalPlan.entry().executableContractQuantity()
                : order.executableContractQuantity() == null
                ? rules.normalizeEntrySize(order.size(), order.price()) : order.executableContractQuantity();
        if (contractSize.signum() <= 0) {
            if (rawContractSize.signum() > 0 && rawContractSize.compareTo(rules.minSize()) < 0) {
                throw new IllegalStateException("下单数量低于OKX最小下单量，禁止自动放大：raw="
                        + value(rawContractSize) + ", minSz=" + value(rules.minSize()));
            }
            throw new IllegalStateException("OKX下单数量无效，无法按合约步进规整");
        }
        if (rawContractSize.compareTo(contractSize) != 0) {
            log.warn("OKX order size normalized instId={} baseSize={} rawContracts={} finalContracts={} lotSz={} minSz={}",
                    order.instId(), value(order.size()), value(rawContractSize), value(contractSize),
                    value(rules.lotSize()), value(rules.minSize()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
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
            BigDecimal normalizedEntry = canonicalPlan != null
                    && canonicalPlan.status() == CanonicalExecutablePlan.CompilationStatus.ACCEPTED
                    ? canonicalPlan.entry().normalizedEntryPrice() : rules.normalizePrice(order.price());
            payload.put("px", value(normalizedEntry));
        }
        payload.put("attachAlgoOrds", List.of(attachedStopLoss(order, rules)));
        return payload;
    }

    private static Map<String, String> attachedStopLoss(PendingOrder order, OkxInstrumentRules rules) {
        BigDecimal entry = order.price() == null ? BigDecimal.ZERO : order.price();
        BigDecimal stopLoss = order.stopLossPrice() == null ? BigDecimal.ZERO : order.stopLossPrice();
        if (entry.signum() <= 0 || stopLoss.signum() <= 0) {
            throw new IllegalStateException("止损强制要求：计划入场价或止损价无效，禁止交易");
        }
        boolean shortSide = "short".equalsIgnoreCase(order.posSide());
        if ((!shortSide && stopLoss.compareTo(entry) >= 0) || (shortSide && stopLoss.compareTo(entry) <= 0)) {
            throw new IllegalStateException("止损强制要求：计划止损价方向错误，禁止交易");
        }
        CanonicalExecutablePlan canonicalPlan = order.canonicalExecutablePlan();
        BigDecimal normalized = canonicalPlan != null
                && canonicalPlan.status() == CanonicalExecutablePlan.CompilationStatus.ACCEPTED
                ? canonicalPlan.stopLoss().normalizedPrice() : rules.normalizePrice(stopLoss);
        if (normalized.signum() <= 0) {
            throw new IllegalStateException("止损强制要求：止损价无效，禁止交易");
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("attachAlgoClOrdId", attachedStopLossClientOrderId(order));
        payload.put("slTriggerPx", value(normalized));
        payload.put("slOrdPx", "-1");
        return payload;
    }

    public static String attachedStopLossClientOrderId(PendingOrder order) {
        return clientOrderId(order.id(), "sl");
    }

    static Map<String, String> closePositionPayload(String instId, String posSide, String marginMode,
                                                    OkxPositionMode positionMode) {
        return closePositionPayload(instId, posSide, marginMode, positionMode, null);
    }

    static Map<String, String> closePositionPayload(String instId, String posSide, String marginMode,
                                                    OkxPositionMode positionMode, String clOrdId) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", instId);
        payload.put("mgnMode", hasText(marginMode) ? marginMode : "cross");
        payload.put("autoCxl", "true");
        if (hasText(clOrdId)) {
            payload.put("clOrdId", clOrdId);
        }
        if (positionMode == OkxPositionMode.LONG_SHORT && hasText(posSide) && !"net".equalsIgnoreCase(posSide)) {
            payload.put("posSide", posSide);
        }
        return payload;
    }

    private static String closeSide(PendingOrder order) {
        return "buy".equalsIgnoreCase(order.side()) ? "sell" : "buy";
    }

    private void recordAccepted(PendingOrder order, OrderRecordPayload payload) {
        try {
            recordSubmitted(order, payload);
        } catch (RuntimeException ex) {
            // Exchange acceptance survives failures in local recording/auditing.
            log.error("OKX_ACK_LOCAL_RECORD_FAILED pendingOrderId={} ordId={} clOrdId={}",
                    order.id(), payload.okxOrdId(), payload.clOrdId(), ex);
        }
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

    private static OrderRecordPayload entryRecord(PendingOrder order, Map<String, Object> payload, String okxOrdId) {
        return new OrderRecordPayload(
                "ENTRY",
                text(payload.get("side")),
                order.posSide(),
                text(payload.get("ordType")),
                new BigDecimal(text(payload.get("sz"))),
                payload.containsKey("px") ? new BigDecimal(text(payload.get("px"))) : order.price(),
                false,
                text(payload.get("clOrdId")),
                okxOrdId
        );
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

    private static String orderId(JsonNode response, String preferredField) {
        JsonNode item = firstDataItem(response);
        String sCode = item == null ? "" : item.path("sCode").asText("");
        // A per-item timeout stays uncertain even when the envelope says failed.
        if (!sCode.isBlank() && !"0".equals(sCode)) {
            throw OkxApiRejectedException.fromResponse(sCode, "OKX order response: sCode=" + sCode
                    + ", sMsg=" + item.path("sMsg").asText(""));
        }
        if (response != null && !"0".equals(response.path("code").asText("0"))) {
            String code = response.path("code").asText();
            throw OkxApiRejectedException.fromResponse(code, "OKX order response: code=" + code
                    + ", msg=" + response.path("msg").asText(""));
        }
        if (item == null) {
            throw new IllegalStateException("OKX order response does not contain data");
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

    private static String compact(String message) {
        if (!hasText(message)) {
            return "unknown";
        }
        String value = message.replaceAll("\\s+", " ").trim();
        return value.length() > 240 ? value.substring(0, 240) : value;
    }

    private static String value(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

}

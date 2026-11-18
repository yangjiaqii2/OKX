package com.example.quant.okxtrade;

import com.example.quant.config.TradingProperties;
import com.example.quant.config.TradingMode;
import com.example.quant.crypto.OkxRestClient;
import com.example.quant.system.TradingReadinessService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OkxRestOrderGateway implements OkxOrderGateway {
    private static final Logger log = LoggerFactory.getLogger(OkxRestOrderGateway.class);

    private final OkxRestClient okxRestClient;
    private final TradingMode tradingMode;
    private TradingReadinessService tradingReadinessService;
    private ExposureReductionGuard exposureReductionGuard;

    public OkxRestOrderGateway(OkxRestClient okxRestClient) {
        this(okxRestClient, null);
    }

    @Autowired
    public OkxRestOrderGateway(OkxRestClient okxRestClient, TradingProperties tradingProperties) {
        this.okxRestClient = okxRestClient;
        this.tradingMode = tradingProperties == null ? TradingMode.DISABLED : tradingProperties.mode();
    }

    @Autowired(required = false)
    void configureTradingReadiness(TradingReadinessService tradingReadinessService) {
        this.tradingReadinessService = tradingReadinessService;
    }

    @Autowired(required = false)
    void configureExposureReductionGuard(ExposureReductionGuard exposureReductionGuard) {
        this.exposureReductionGuard = exposureReductionGuard;
    }

    @Override
    public boolean hasCredentials() {
        return okxRestClient.hasCredentials();
    }

    @Override
    public JsonNode setLeverage(Map<String, String> payload) {
        requireTradingEnabled(context(TradingOperationType.SET_LEVERAGE), payload);
        log.info("POST OKX /api/v5/account/set-leverage instId={} mgnMode={} posSide={} lever={}",
                payload.get("instId"), payload.get("mgnMode"), payload.getOrDefault("posSide", ""), payload.get("lever"));
        return okxRestClient.privatePost("/api/v5/account/set-leverage", payload);
    }

    @Override
    public JsonNode placeOrder(Map<String, Object> payload) {
        return placeOrder(payload, context(isReduceOnly(payload)
                ? TradingOperationType.REDUCE_POSITION : TradingOperationType.OPEN_POSITION));
    }

    @Override
    public JsonNode placeOrder(Map<String, Object> payload, TradingOperationContext context) {
        try {
            requireTradingEnabled(context, stringPayload(payload));
        } catch (RuntimeException ex) {
            throw new OrderNotSubmittedException("PRE_ORDER_SETUP_FAILED: " + ex.getMessage(), ex);
        }
        log.info("POST OKX /api/v5/trade/order instId={} side={} posSide={} ordType={} sz={} clOrdId={}",
                payload.get("instId"), payload.get("side"), payload.get("posSide"), payload.get("ordType"),
                payload.get("sz"), payload.getOrDefault("clOrdId", ""));
        return okxRestClient.privatePost("/api/v5/trade/order", payload);
    }

    @Override
    public JsonNode queryOrder(Map<String, String> payload) {
        String instId = payload.getOrDefault("instId", "");
        String ordId = payload.getOrDefault("ordId", "");
        String clOrdId = payload.getOrDefault("clOrdId", "");
        log.info("GET OKX /api/v5/trade/order instId={} ordId={} clOrdId={}", instId, ordId, clOrdId);
        String path = "/api/v5/trade/order?instId=" + OkxRestClient.encode(instId);
        if (!ordId.isBlank()) {
            path += "&ordId=" + OkxRestClient.encode(ordId);
        } else if (!clOrdId.isBlank()) {
            path += "&clOrdId=" + OkxRestClient.encode(clOrdId);
        }
        return okxRestClient.privateGet(path);
    }

    @Override
    public JsonNode queryOrderHistory(Map<String, String> payload) {
        String instType = payload.getOrDefault("instType", "SWAP");
        String instId = payload.getOrDefault("instId", "");
        String ordId = payload.getOrDefault("ordId", "");
        String clOrdId = payload.getOrDefault("clOrdId", "");
        String path = "/api/v5/trade/orders-history-archive?instType="
                + OkxRestClient.encode(instType);
        if (!instId.isBlank()) path += "&instId=" + OkxRestClient.encode(instId);
        if (!ordId.isBlank()) path += "&ordId=" + OkxRestClient.encode(ordId);
        else if (!clOrdId.isBlank()) path += "&clOrdId=" + OkxRestClient.encode(clOrdId);
        log.info("GET OKX /api/v5/trade/orders-history-archive instId={} ordId={} clOrdId={}",
                instId, ordId, clOrdId);
        return okxRestClient.privateGet(path);
    }

    @Override
    public JsonNode currentOrders(Map<String, String> payload) {
        String instType = payload.getOrDefault("instType", "SWAP");
        log.info("GET OKX /api/v5/trade/orders-pending instType={}", instType);
        return okxRestClient.privateGet("/api/v5/trade/orders-pending?instType=" + OkxRestClient.encode(instType));
    }

    @Override
    public JsonNode fills(Map<String, String> payload) {
        String instType = payload.getOrDefault("instType", "SWAP");
        String instId = payload.getOrDefault("instId", "");
        String ordId = payload.getOrDefault("ordId", "");
        String query = "?instType=" + OkxRestClient.encode(instType);
        if (!instId.isBlank()) {
            query += "&instId=" + OkxRestClient.encode(instId);
        }
        if (!ordId.isBlank()) {
            query += "&ordId=" + OkxRestClient.encode(ordId);
        }
        JsonNode recent = okxRestClient.privateGet("/api/v5/trade/fills" + query);
        if (recent != null && recent.path("data").isArray() && !recent.path("data").isEmpty()) {
            return recent;
        }
        log.info("GET OKX historical fills fallback instId={} ordId={}", instId, ordId);
        return okxRestClient.privateGet("/api/v5/trade/fills-history" + query);
    }

    @Override
    public JsonNode currentAlgoOrders(Map<String, String> payload) {
        String instType = payload.getOrDefault("instType", "SWAP");
        String ordType = payload.getOrDefault("ordType", "conditional");
        log.info("GET OKX /api/v5/trade/orders-algo-pending instType={} ordType={}", instType, ordType);
        return okxRestClient.privateGet("/api/v5/trade/orders-algo-pending?instType="
                + OkxRestClient.encode(instType)
                + "&ordType="
                + OkxRestClient.encode(ordType));
    }

    @Override
    public JsonNode queryAlgoOrder(Map<String, String> payload) {
        String algoId = payload.getOrDefault("algoId", "");
        String algoClOrdId = payload.getOrDefault("algoClOrdId", "");
        String path = "/api/v5/trade/order-algo?";
        if (!algoId.isBlank()) {
            path += "algoId=" + OkxRestClient.encode(algoId);
        } else if (!algoClOrdId.isBlank()) {
            path += "algoClOrdId=" + OkxRestClient.encode(algoClOrdId);
        } else {
            throw new IllegalArgumentException("algoId或algoClOrdId不能为空");
        }
        log.info("GET OKX /api/v5/trade/order-algo algoId={} algoClOrdId={}", algoId, algoClOrdId);
        return okxRestClient.privateGet(path);
    }

    @Override
    public JsonNode placeAlgoOrder(Map<String, String> payload) {
        return placeAlgoOrder(payload, context(algoOperation(payload)));
    }

    @Override
    public JsonNode placeAlgoOrder(Map<String, String> payload, TradingOperationContext context) {
        requireTradingEnabled(context, payload);
        log.info("POST OKX /api/v5/trade/order-algo instId={} side={} posSide={} ordType={} sz={} clOrdId={}",
                payload.get("instId"), payload.get("side"), payload.get("posSide"), payload.get("ordType"),
                payload.get("sz"), payload.getOrDefault("algoClOrdId", payload.getOrDefault("clOrdId", "")));
        return okxRestClient.privatePost("/api/v5/trade/order-algo", payload);
    }

    @Override
    public JsonNode cancelOrder(Map<String, String> payload) {
        requireTradingEnabled(context(TradingOperationType.CANCEL_ORDER), payload);
        log.warn("POST OKX /api/v5/trade/cancel-order instId={} ordId={} clOrdId={}",
                payload.get("instId"), payload.getOrDefault("ordId", ""), payload.getOrDefault("clOrdId", ""));
        return okxRestClient.privatePost("/api/v5/trade/cancel-order", payload);
    }

    @Override
    public JsonNode cancelAlgoOrder(Map<String, String> payload) {
        requireTradingEnabled(context(TradingOperationType.CANCEL_PROTECTION), payload);
        log.warn("POST OKX /api/v5/trade/cancel-algos instId={} algoId={} algoClOrdId={}",
                payload.get("instId"), payload.getOrDefault("algoId", ""), payload.getOrDefault("algoClOrdId", ""));
        return okxRestClient.privatePost("/api/v5/trade/cancel-algos", List.of(payload));
    }

    @Override
    public JsonNode amendAlgoOrder(Map<String, String> payload) {
        return amendAlgoOrder(payload, context(TradingOperationType.AMEND_PROTECTION));
    }

    @Override
    public JsonNode amendAlgoOrder(Map<String, String> payload, TradingOperationContext context) {
        requireTradingEnabled(context, payload);
        log.info("POST OKX /api/v5/trade/amend-algos instId={} algoId={} algoClOrdId={} newSlTriggerPx={} reqId={}",
                payload.get("instId"), payload.getOrDefault("algoId", ""),
                payload.getOrDefault("algoClOrdId", ""), payload.getOrDefault("newSlTriggerPx", ""),
                payload.getOrDefault("reqId", ""));
        return okxRestClient.privatePost("/api/v5/trade/amend-algos", payload);
    }

    @Override
    public JsonNode closePosition(Map<String, String> payload) {
        return closePosition(payload, context(TradingOperationType.CLOSE_POSITION));
    }

    @Override
    public JsonNode closePosition(Map<String, String> payload, TradingOperationContext context) {
        requireTradingEnabled(context, payload);
        log.warn("POST OKX /api/v5/trade/close-position instId={} posSide={} mgnMode={} autoCxl={}",
                payload.get("instId"), payload.getOrDefault("posSide", ""), payload.get("mgnMode"),
                payload.getOrDefault("autoCxl", ""));
        return okxRestClient.privatePost("/api/v5/trade/close-position", payload);
    }

    private void requireTradingEnabled(TradingOperationContext context, Map<String, String> payload) {
        if (!tradingMode.writesEnabled()) {
            audit(context, payload, false, "TRADING_MODE_DISABLED");
            throw new IllegalStateException("交易模式为DISABLED（实盘交易已禁用）；OKX写请求未发送");
        }
        if (context != null && context.operationType().reducesExposure()) {
            if (exposureReductionGuard == null) {
                audit(context, payload, false, "REDUCTION_GUARD_UNAVAILABLE");
                throw new IllegalStateException("降风险验证器不可用；OKX写请求未发送");
            }
            exposureReductionGuard.verify(context, payload);
            audit(context, payload, true, "VERIFIED_RISK_REDUCTION");
            return;
        }
        if (tradingReadinessService == null || tradingReadinessService.ready()) {
            audit(context, payload, true, "READY");
            return;
        }
        audit(context, payload, false, "INCREASE_EXPOSURE_BLOCKED");
        throw new IllegalStateException("交易引擎尚未完成启动对账；新增风险操作已阻断；OKX写请求未发送");
    }

    private void audit(TradingOperationContext context, Map<String, String> payload,
                       boolean requestSent, String decisionReason) {
        TradingReadinessService.Snapshot readiness = tradingReadinessService == null
                ? null : tradingReadinessService.snapshot();
        log.info("trading_write_decision pendingOrderId={} tradePlanId={} instId={} owner={} tradingMode={} "
                        + "operationType={} readinessState={} readinessReason={} decisionReason={} requestSent={}",
                context == null ? null : context.pendingOrderId(),
                context == null ? null : context.tradePlanId(),
                payload == null ? null : payload.get("instId"),
                context == null ? null : context.owner(),
                tradingMode,
                context == null ? TradingOperationType.OPEN_POSITION : context.operationType(),
                readiness == null ? "NOT_CONFIGURED" : readiness.state(),
                readiness == null ? "NOT_CONFIGURED" : readiness.reason(),
                decisionReason,
                requestSent);
    }

    private static TradingOperationContext context(TradingOperationType operationType) {
        return TradingOperationContext.of(operationType, null, null, null);
    }

    private static TradingOperationType algoOperation(Map<String, String> payload) {
        if (hasText(payload == null ? null : payload.get("slTriggerPx"))) {
            return TradingOperationType.PLACE_STOP_LOSS;
        }
        if (hasText(payload == null ? null : payload.get("tpTriggerPx"))) {
            return TradingOperationType.PLACE_TAKE_PROFIT;
        }
        return "true".equalsIgnoreCase(payload == null ? null : payload.get("reduceOnly"))
                ? TradingOperationType.REDUCE_POSITION : TradingOperationType.OPEN_POSITION;
    }

    private static boolean isReduceOnly(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("reduceOnly");
        return value instanceof Boolean bool ? bool : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static Map<String, String> stringPayload(Map<String, Object> payload) {
        java.util.LinkedHashMap<String, String> converted = new java.util.LinkedHashMap<>();
        if (payload != null) {
            payload.forEach((key, value) -> converted.put(key, value == null ? "" : String.valueOf(value)));
        }
        return converted;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

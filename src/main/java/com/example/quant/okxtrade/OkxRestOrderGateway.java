package com.example.quant.okxtrade;

import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OkxRestOrderGateway implements OkxOrderGateway {
    private static final Logger log = LoggerFactory.getLogger(OkxRestOrderGateway.class);

    private final OkxRestClient okxRestClient;

    public OkxRestOrderGateway(OkxRestClient okxRestClient) {
        this.okxRestClient = okxRestClient;
    }

    @Override
    public JsonNode setLeverage(Map<String, String> payload) {
        log.info("POST OKX /api/v5/account/set-leverage instId={} mgnMode={} posSide={} lever={}",
                payload.get("instId"), payload.get("mgnMode"), payload.getOrDefault("posSide", ""), payload.get("lever"));
        return okxRestClient.privatePost("/api/v5/account/set-leverage", payload);
    }

    @Override
    public JsonNode placeOrder(Map<String, String> payload) {
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
    public JsonNode currentOrders(Map<String, String> payload) {
        String instType = payload.getOrDefault("instType", "SWAP");
        log.info("GET OKX /api/v5/trade/orders-pending instType={}", instType);
        return okxRestClient.privateGet("/api/v5/trade/orders-pending?instType=" + OkxRestClient.encode(instType));
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
    public JsonNode placeAlgoOrder(Map<String, String> payload) {
        log.info("POST OKX /api/v5/trade/order-algo instId={} side={} posSide={} ordType={} sz={} clOrdId={}",
                payload.get("instId"), payload.get("side"), payload.get("posSide"), payload.get("ordType"),
                payload.get("sz"), payload.getOrDefault("algoClOrdId", payload.getOrDefault("clOrdId", "")));
        return okxRestClient.privatePost("/api/v5/trade/order-algo", payload);
    }

    @Override
    public JsonNode cancelOrder(Map<String, String> payload) {
        log.warn("POST OKX /api/v5/trade/cancel-order instId={} ordId={} clOrdId={}",
                payload.get("instId"), payload.getOrDefault("ordId", ""), payload.getOrDefault("clOrdId", ""));
        return okxRestClient.privatePost("/api/v5/trade/cancel-order", payload);
    }

    @Override
    public JsonNode cancelAlgoOrder(Map<String, String> payload) {
        log.warn("POST OKX /api/v5/trade/cancel-algos instId={} algoId={} algoClOrdId={}",
                payload.get("instId"), payload.getOrDefault("algoId", ""), payload.getOrDefault("algoClOrdId", ""));
        return okxRestClient.privatePost("/api/v5/trade/cancel-algos", payload);
    }

    @Override
    public JsonNode closePosition(Map<String, String> payload) {
        log.warn("POST OKX /api/v5/trade/close-position instId={} posSide={} mgnMode={} autoCxl={}",
                payload.get("instId"), payload.getOrDefault("posSide", ""), payload.get("mgnMode"),
                payload.getOrDefault("autoCxl", ""));
        return okxRestClient.privatePost("/api/v5/trade/close-position", payload);
    }
}

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
        log.info("GET OKX /api/v5/trade/order instId={} ordId={}", instId, ordId);
        return okxRestClient.privateGet("/api/v5/trade/order?instId="
                + OkxRestClient.encode(instId)
                + "&ordId="
                + OkxRestClient.encode(ordId));
    }

    @Override
    public JsonNode placeAlgoOrder(Map<String, String> payload) {
        log.info("POST OKX /api/v5/trade/order-algo instId={} side={} posSide={} ordType={} sz={} clOrdId={}",
                payload.get("instId"), payload.get("side"), payload.get("posSide"), payload.get("ordType"),
                payload.get("sz"), payload.getOrDefault("algoClOrdId", payload.getOrDefault("clOrdId", "")));
        return okxRestClient.privatePost("/api/v5/trade/order-algo", payload);
    }

    @Override
    public JsonNode closePosition(Map<String, String> payload) {
        log.warn("POST OKX /api/v5/trade/close-position instId={} posSide={} mgnMode={} autoCxl={}",
                payload.get("instId"), payload.getOrDefault("posSide", ""), payload.get("mgnMode"),
                payload.getOrDefault("autoCxl", ""));
        return okxRestClient.privatePost("/api/v5/trade/close-position", payload);
    }
}

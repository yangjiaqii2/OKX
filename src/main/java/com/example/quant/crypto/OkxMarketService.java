package com.example.quant.crypto;

import com.example.quant.crypto.dto.ContractCandle;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OkxMarketService {
    private final OkxRestClient okxRestClient;

    public OkxMarketService(OkxRestClient okxRestClient) {
        this.okxRestClient = okxRestClient;
    }

    public String status() {
        return "okx-rest-market-adapter-ready";
    }

    public List<ContractCandle> candles(String instId, String bar) {
        return candles(instId, bar, 120);
    }

    public List<ContractCandle> candles(String instId, String bar, int limit) {
        String normalizedBar = normalizeBar(bar);
        JsonNode root = okxRestClient.publicGet("/api/v5/market/candles?instId="
                + OkxRestClient.encode(instId)
                + "&bar=" + OkxRestClient.encode(normalizedBar)
                + "&limit=" + Math.max(1, Math.min(300, limit)));
        List<ContractCandle> candles = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            if (!item.isArray() || item.size() < 6) {
                continue;
            }
            candles.add(new ContractCandle(
                    Instant.ofEpochMilli(item.get(0).asLong()),
                    decimal(item.get(1)),
                    decimal(item.get(2)),
                    decimal(item.get(3)),
                    decimal(item.get(4)),
                    decimal(item.get(5))
            ));
        }
        Collections.reverse(candles);
        return candles;
    }

    private static String normalizeBar(String bar) {
        return switch (bar == null ? "" : bar) {
            case "1m", "5m", "15m", "30m", "1H", "4H", "1D", "1W", "1M" -> bar;
            default -> "15m";
        };
    }

    private static BigDecimal decimal(JsonNode node) {
        String value = node.asText("0");
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }
}

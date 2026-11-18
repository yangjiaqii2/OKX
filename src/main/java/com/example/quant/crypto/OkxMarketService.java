package com.example.quant.crypto;

import com.example.quant.crypto.dto.ContractCandle;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class OkxMarketService {
    private static final long HISTORY_REQUEST_SPACING_NANOS = TimeUnit.MILLISECONDS.toNanos(150);
    private final OkxRestClient okxRestClient;
    private long lastHistoryRequestNanos = System.nanoTime() - HISTORY_REQUEST_SPACING_NANOS;

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
        return candles(instId, bar, limit, null);
    }

    public List<ContractCandle> candles(String instId, String bar, int limit, Duration timeout) {
        String normalizedBar = normalizeBar(bar);
        String path = "/api/v5/market/candles?instId="
                + OkxRestClient.encode(instId)
                + "&bar=" + OkxRestClient.encode(normalizedBar)
                + "&limit=" + Math.max(1, Math.min(300, limit));
        JsonNode root = timeout == null
                ? okxRestClient.publicGet(path)
                : okxRestClient.publicGet(path, timeout);
        return confirmedCandles(root);
    }

    public synchronized List<ContractCandle> historicalCandlesBefore(String instId, String bar, Instant before) {
        long waitNanos = HISTORY_REQUEST_SPACING_NANOS - (System.nanoTime() - lastHistoryRequestNanos);
        if (waitNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Historical candle request interrupted", ex);
            }
        }
        lastHistoryRequestNanos = System.nanoTime();
        try {
            JsonNode root = okxRestClient.publicGet("/api/v5/market/history-candles?instId="
                    + OkxRestClient.encode(instId)
                    + "&bar=" + OkxRestClient.encode(normalizeBar(bar))
                    + "&limit=100&after=" + before.toEpochMilli());
            return confirmedCandles(root);
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && (ex.getMessage().equals("OKX HTTP 429")
                    || ex.getMessage().startsWith("OKX HTTP 429:"))) {
                throw new HistoryRateLimitedException(ex);
            }
            throw ex;
        }
    }

    public static final class HistoryRateLimitedException extends IllegalStateException {
        public HistoryRateLimitedException(Throwable cause) {
            super("OKX history candles HTTP 429", cause);
        }
    }

    private static List<ContractCandle> confirmedCandles(JsonNode root) {
        List<ContractCandle> candles = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            if (!item.isArray() || item.size() < 9 || !"1".equals(item.get(8).asText())) {
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

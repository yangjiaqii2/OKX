package com.example.quant.crypto.provider.okx;

import com.example.quant.crypto.OkxMarketService;
import com.example.quant.crypto.OkxRestClient;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.dto.ContractKlineSet;
import com.example.quant.crypto.provider.AthBreakoutProvider;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderCache;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.AthBreakoutSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OkxAthBreakoutProvider {
    private static final int PAGE_SIZE = 100;
    private final OkxMarketService marketService;
    private final OkxRestClient restClient;
    private final ProviderCache<String, AthBreakoutSnapshot> cache;
    private final int maxPages;
    private final BigDecimal atAthPct;
    private final BigDecimal nearAthPct;
    private final BigDecimal midRangePct;

    public OkxAthBreakoutProvider(OkxMarketService marketService, OkxRestClient restClient,
                                  int freshTtlSeconds, int staleTtlSeconds, int maxPages,
                                  BigDecimal atAthPct, BigDecimal nearAthPct, BigDecimal midRangePct) {
        this.marketService = marketService;
        this.restClient = restClient;
        this.cache = new ProviderCache<>("AthBreakout", Duration.ofSeconds(freshTtlSeconds),
                Duration.ofSeconds(staleTtlSeconds));
        this.maxPages = Math.max(1, maxPages);
        this.atAthPct = atAthPct;
        this.nearAthPct = nearAthPct;
        this.midRangePct = midRangePct;
    }

    public ProviderResult<AthBreakoutSnapshot> fetch(String instId, BigDecimal currentPrice,
                                                      ContractKlineSet existing) {
        return fetch(instId, currentPrice, existing, null);
    }

    public ProviderResult<AthBreakoutSnapshot> fetch(String instId, BigDecimal currentPrice,
                                                      ContractKlineSet existing, Duration timeout) {
        if (instId == null || currentPrice == null || currentPrice.signum() <= 0) {
            return ProviderResult.unavailable("ATH_PROVIDER_UNAVAILABLE: missing current price");
        }
        if (timeout != null && timeout.isZero()) {
            return ProviderResult.timeout("ATH_TIMEOUT: deadline exceeded");
        }
        if (Thread.currentThread().isInterrupted()) {
            return ProviderResult.timeout("ATH_TIMEOUT: interrupted");
        }
        return cache.get(instId, key -> load(key, currentPrice, existing, timeout));
    }

    private ProviderResult<AthBreakoutSnapshot> load(String instId, BigDecimal currentPrice,
                                                     ContractKlineSet existing, Duration timeout) {
        List<ContractCandle> candles = new ArrayList<>();
        if (existing != null && existing.oneDay() != null) {
            candles.addAll(existing.oneDay());
        }
        int pages = 0;
        boolean truncated = false;
        Set<Long> seen = new HashSet<>();
        candles.forEach(c -> {
            if (c != null && c.timestamp() != null) {
                seen.add(c.timestamp().toEpochMilli());
            }
        });
        Long oldest = candles.stream().filter(c -> c != null && c.timestamp() != null)
                .map(c -> c.timestamp().toEpochMilli()).min(Long::compareTo).orElse(null);
        boolean reachedHistoryEnd = false;
        Instant deadline = timeout == null ? null : Instant.now().plus(timeout);
        try {
            while (pages < maxPages) {
                Duration pageTimeout = deadline == null
                        ? null
                        : Duration.between(Instant.now(), deadline);
                if (pageTimeout != null && (pageTimeout.isZero() || pageTimeout.isNegative())) {
                    return ProviderResult.timeout("ATH_TIMEOUT: deadline exceeded during pagination");
                }
                String path = "/api/v5/market/candles?instId=" + OkxRestClient.encode(instId)
                        + "&bar=1D&limit=" + PAGE_SIZE
                        + (oldest == null ? "" : "&after=" + oldest);
                JsonNode root = pageTimeout == null
                        ? restClient.publicGet(path)
                        : restClient.publicGet(path, pageTimeout);
                List<ContractCandle> page = parse(root);
                pages++;
                if (page.isEmpty()) {
                    reachedHistoryEnd = true;
                    break;
                }
                int before = seen.size();
                for (ContractCandle candle : page) {
                    if (candle.timestamp() != null && seen.add(candle.timestamp().toEpochMilli())) {
                        candles.add(candle);
                    }
                }
                oldest = candles.stream().filter(c -> c != null && c.timestamp() != null)
                        .map(c -> c.timestamp().toEpochMilli()).min(Long::compareTo).orElse(oldest);
                if (seen.size() == before || page.size() < PAGE_SIZE) {
                    reachedHistoryEnd = true;
                    break;
                }
            }
            if (pages >= maxPages && !reachedHistoryEnd) {
                truncated = true;
            }
            DataQuality quality = truncated ? DataQuality.PARTIAL : DataQuality.FRESH;
            AthBreakoutSnapshot snapshot = AthBreakoutProvider.calculate(instId, currentPrice, candles,
                    quality, pages, truncated, atAthPct, nearAthPct, midRangePct);
            if (snapshot.dataQuality() == DataQuality.UNAVAILABLE) {
                return new ProviderResult<>(snapshot, DataQuality.UNAVAILABLE, Instant.now(), "OKX",
                        "ATH_UNAVAILABLE", "no valid 1D ATH candle");
            }
            return new ProviderResult<>(snapshot, quality, Instant.now(), "OKX", null, null);
        } catch (RuntimeException ex) {
            if (isTimeout(ex)) {
                return ProviderResult.timeout("ATH_TIMEOUT: request timed out");
            }
            return ProviderResult.unavailable("ATH_PROVIDER_UNAVAILABLE: " + ex.getMessage());
        }
    }

    private static List<ContractCandle> parse(JsonNode root) {
        List<ContractCandle> candles = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            if (!item.isArray() || item.size() < 9 || !"1".equals(item.get(8).asText())) {
                continue;
            }
            try {
                candles.add(new ContractCandle(Instant.ofEpochMilli(item.get(0).asLong()),
                        new BigDecimal(item.get(1).asText()), new BigDecimal(item.get(2).asText()),
                        new BigDecimal(item.get(3).asText()), new BigDecimal(item.get(4).asText()),
                        new BigDecimal(item.get(5).asText())));
            } catch (NumberFormatException ignored) {
                // Invalid rows do not contribute to ATH.
            }
        }
        candles.sort(Comparator.comparing(ContractCandle::timestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return candles;
    }

    private static boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (current instanceof java.util.concurrent.TimeoutException
                    || (message != null && message.toLowerCase().contains("timed out"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

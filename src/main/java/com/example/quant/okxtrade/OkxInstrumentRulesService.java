package com.example.quant.okxtrade;

import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OkxInstrumentRulesService implements OkxInstrumentRulesProvider {
    private static final Logger log = LoggerFactory.getLogger(OkxInstrumentRulesService.class);
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofHours(1);
    private static final String BULK_SWAP_PATH = "/api/v5/public/instruments?instType=SWAP";

    private final OkxRestClient okxRestClient;
    private final Duration cacheTtl;
    private final ConcurrentMap<String, CachedRules> cache = new ConcurrentHashMap<>();
    private final Object bulkLoadLock = new Object();
    private volatile Instant bulkLoadedAt;
    private volatile boolean bulkLoadFailed;
    private volatile Set<String> liveCryptoUsdtSwapIds = Set.of();

    @Autowired
    public OkxInstrumentRulesService(OkxRestClient okxRestClient) {
        this(okxRestClient, DEFAULT_CACHE_TTL);
    }

    OkxInstrumentRulesService(OkxRestClient okxRestClient, Duration cacheTtl) {
        this.okxRestClient = okxRestClient;
        this.cacheTtl = cacheTtl;
    }

    @Override
    public OkxInstrumentRules rules(String instId) {
        if (instId == null || instId.isBlank()) {
            throw new IllegalArgumentException("OKX合约不能为空");
        }
        Instant now = Instant.now();
        ensureBulkCache(now, null);
        CachedRules cached = cache.get(instId);
        if (cached != null && isFreshFromBulk(cached)) {
            return cached.rules();
        }
        if (cached != null && isFreshByTtl(cached, now)) {
            return cached.rules();
        }
        return cache.compute(instId, (key, existing) -> {
            Instant loadTime = Instant.now();
            if (existing != null && isFreshByTtl(existing, loadTime)) {
                return existing;
            }
            return new CachedRules(fetchRules(key), loadTime);
        }).rules();
    }

    public Set<String> liveCryptoUsdtSwapIds(Duration timeout) {
        ensureBulkCache(Instant.now(), timeout);
        Set<String> ids = liveCryptoUsdtSwapIds;
        if (bulkLoadFailed || ids.isEmpty()) {
            throw new IllegalStateException("OKX live crypto USDT swap metadata unavailable");
        }
        return ids;
    }

    private boolean isFreshByTtl(CachedRules cached, Instant now) {
        return now.isBefore(cached.loadedAt().plus(cacheTtl));
    }

    private boolean isFreshFromBulk(CachedRules cached) {
        Instant loadedAt = bulkLoadedAt;
        return loadedAt != null && !bulkLoadFailed && !cached.loadedAt().isBefore(loadedAt);
    }

    private void ensureBulkCache(Instant now, Duration timeout) {
        if (bulkLoadedAt != null && now.isBefore(bulkLoadedAt.plus(cacheTtl)) && !bulkLoadFailed) {
            return;
        }
        synchronized (bulkLoadLock) {
            if (bulkLoadedAt != null && now.isBefore(bulkLoadedAt.plus(cacheTtl)) && !bulkLoadFailed) {
                return;
            }
            try {
                int loaded = loadAllSwapInstruments(now, timeout);
                bulkLoadedAt = now;
                bulkLoadFailed = false;
                if (loaded > 0) {
                    log.info("Bulk loaded OKX SWAP instrument rules count={}", loaded);
                }
            } catch (RuntimeException ex) {
                bulkLoadFailed = true;
                log.warn("Bulk load OKX SWAP instrument rules failed, falling back to per-instrument fetch: {}",
                        ex.getMessage());
            }
        }
    }

    private int loadAllSwapInstruments(Instant loadedAt, Duration timeout) {
        JsonNode root = timeout == null ? okxRestClient.publicGet(BULK_SWAP_PATH)
                : okxRestClient.publicGet(BULK_SWAP_PATH, timeout);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("OKX bulk instruments returned empty data");
        }
        int loaded = 0;
        Set<String> cryptoSwaps = new HashSet<>();
        for (JsonNode instrument : data) {
            OkxInstrumentRules rules = parseInstrumentRules(instrument);
            if (rules == null) {
                continue;
            }
            cache.put(rules.instId(), new CachedRules(rules, loadedAt));
            if (rules.instId().endsWith("-USDT-SWAP") && "1".equals(instrument.path("instCategory").asText())
                    && "live".equals(instrument.path("state").asText())) {
                cryptoSwaps.add(rules.instId());
            }
            loaded++;
        }
        if (loaded == 0) {
            throw new IllegalStateException("OKX bulk instruments parsed zero valid SWAP rules");
        }
        liveCryptoUsdtSwapIds = Set.copyOf(cryptoSwaps);
        return loaded;
    }

    private OkxInstrumentRules fetchRules(String instId) {
        try {
            JsonNode root = okxRestClient.publicGet("/api/v5/public/instruments?instType=SWAP&instId="
                    + OkxRestClient.encode(instId));
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                OkxInstrumentRules rules = parseInstrumentRules(data.get(0));
                if (rules == null) {
                    throw new IllegalStateException("OKX合约规则字段无效：" + instId);
                }
                if (!instId.equalsIgnoreCase(rules.instId())) {
                    throw new IllegalStateException("OKX返回的合约不匹配：expected=" + instId
                            + ", actual=" + rules.instId());
                }
                log.info("Loaded OKX instrument rules instId={} ctVal={} ctValCcy={} lotSz={} minSz={} tickSz={}",
                        instId, rules.contractValue(), rules.contractValueCurrency(), rules.lotSize(),
                        rules.minSize(), rules.tickSize());
                return rules;
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException("无法加载OKX合约规则，禁止下单：" + instId + "；" + ex.getMessage(), ex);
        }
        throw new IllegalStateException("无法加载OKX合约规则，禁止下单：" + instId + "，OKX返回空规则");
    }

    private static OkxInstrumentRules parseInstrumentRules(JsonNode instrument) {
        if (instrument == null || instrument.isMissingNode()) {
            return null;
        }
        String responseInstId = instrument.path("instId").asText("");
        String instType = instrument.path("instType").asText("");
        if (responseInstId.isBlank() || !"SWAP".equalsIgnoreCase(instType)) {
            return null;
        }
        BigDecimal contractValue = decimal(instrument, "ctVal");
        String contractValueCurrency = instrument.path("ctValCcy").asText("");
        BigDecimal lotSize = decimal(instrument, "lotSz");
        BigDecimal minSize = decimal(instrument, "minSz");
        BigDecimal tickSize = decimal(instrument, "tickSz");
        if (contractValue.signum() <= 0 || contractValueCurrency.isBlank()
                || lotSize.signum() <= 0 || minSize.signum() <= 0 || tickSize.signum() <= 0) {
            return null;
        }
        return new OkxInstrumentRules(
                responseInstId,
                contractValue,
                contractValueCurrency,
                lotSize,
                minSize,
                tickSize
        );
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = node.path(field).asText("0");
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    private record CachedRules(OkxInstrumentRules rules, Instant loadedAt) {
    }
}

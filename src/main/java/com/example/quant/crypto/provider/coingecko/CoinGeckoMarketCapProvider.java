package com.example.quant.crypto.provider.coingecko;

import com.example.quant.crypto.MarketCapSnapshotEntity;
import com.example.quant.crypto.MarketCapSnapshotRepository;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.MarketCapProvider;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.TokenSymbolMapper;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真实 MarketCap Provider —— CoinGecko 生产实现。
 *
 * <p>短 TTL 缓存由外层 {@link com.example.quant.crypto.provider.ProviderCache} 负责；本类另外持久化
 * 最后一次成功快照，用于 CoinGecko 额度耗尽或上游故障时的观察级保底。本类只做：<ol>
 *   <li>{@link CoinGeckoTokenBucket#tryAcquire()} 本地节流</li>
 *   <li>{@link CoinGeckoHttpClient#fetchBySymbol(String)} 拉数据</li>
 *   <li>{@link CoinGeckoMapper} 解析为 {@link MarketCapSnapshot}</li>
 *   <li>封装为 {@link ProviderResult}</li>
 * </ol>
 *
 * <p>失败模式统一为 {@link ProviderResult#unavailable(String)}，持久化快照只返回
 * {@link com.example.quant.crypto.provider.DataQuality#STALE}，绝不返回假值或把旧数据伪装成新鲜数据。
 */
public final class CoinGeckoMarketCapProvider implements MarketCapProvider {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoMarketCapProvider.class);
    private static final Duration EMPTY_RESPONSE_COOLDOWN = Duration.ofHours(1);

    private final String providerName;
    private final CoinGeckoHttpClient httpClient;
    private final CoinGeckoTokenBucket tokenBucket;
    private final MarketCapSnapshotRepository snapshotRepository;
    private final Duration maxSourceAge;
    private volatile Instant upstreamCooldownUntil = Instant.MIN;

    public CoinGeckoMarketCapProvider(String providerName,
                                       CoinGeckoHttpClient httpClient,
                                       CoinGeckoTokenBucket tokenBucket) {
        this(providerName, httpClient, tokenBucket, null);
    }

    public CoinGeckoMarketCapProvider(String providerName,
                                       CoinGeckoHttpClient httpClient,
                                       CoinGeckoTokenBucket tokenBucket,
                                       MarketCapSnapshotRepository snapshotRepository) {
        this(providerName, httpClient, tokenBucket, snapshotRepository, Duration.ofMinutes(10));
    }

    public CoinGeckoMarketCapProvider(String providerName, CoinGeckoHttpClient httpClient,
                                      CoinGeckoTokenBucket tokenBucket, MarketCapSnapshotRepository snapshotRepository,
                                      Duration maxSourceAge) {
        this.providerName = providerName == null || providerName.isBlank() ? "coingecko" : providerName;
        this.httpClient = httpClient;
        this.tokenBucket = tokenBucket;
        this.snapshotRepository = snapshotRepository;
        this.maxSourceAge = maxSourceAge;
    }

    @Override
    public ProviderResult<MarketCapSnapshot> fetch(String baseSymbol) {
        if (baseSymbol == null || baseSymbol.isBlank()) {
            return ProviderResult.unavailable(providerName + ": blank symbol");
        }
        String okxBase = baseSymbol.toUpperCase();
        if (isUpstreamCooldownActive()) {
            return fallback(okxBase, providerName + ": upstream cooldown after rate limit");
        }
        String key = TokenSymbolMapper.mapToCoinGeckoSymbol(baseSymbol);
        if (tokenBucket != null && !tokenBucket.tryAcquire()) {
            log.warn("CoinGecko rate-limited locally symbol={} reason=token bucket empty", okxBase);
            return fallback(okxBase, providerName + ": rate limited");
        }
        try {
            Optional<CoinGeckoMarketData> data = httpClient.fetchBySymbol(key);
            if (data.isEmpty() && !key.equals(okxBase)) {
                data = httpClient.fetchBySymbol(okxBase);
            }
            return resolveMarketCap(okxBase, data.orElse(null));
        } catch (CoinGeckoRequestException ex) {
            log.warn("CoinGecko request unavailable symbol={} kind={} message={}",
                    key, ex.kind(), ex.getMessage());
            if (ex.kind() == CoinGeckoRequestException.Kind.RATE_LIMITED) {
                enterUpstreamCooldown("rate_limited");
                return fallback(okxBase, providerName + ": upstream rate limited");
            }
            if (ex.kind() == CoinGeckoRequestException.Kind.UNAVAILABLE) {
                return fallback(okxBase, providerName + ": " + ex.getMessage());
            }
            return ProviderResult.timeout("CoinGecko " + ex.kind().name().toLowerCase());
        } catch (Exception ex) {
            log.warn("CoinGecko provider failed symbol={} : {}", key, ex.toString());
            return fallback(okxBase, providerName + ": " + ex.getClass().getSimpleName());
        }
    }

    @Override
    public Map<String, ProviderResult<MarketCapSnapshot>> fetchBatch(List<String> baseSymbols) {
        Map<String, ProviderResult<MarketCapSnapshot>> out = new LinkedHashMap<>();
        if (baseSymbols == null || baseSymbols.isEmpty()) {
            return out;
        }
        List<String> normalized = baseSymbols.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::toUpperCase)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return out;
        }
        if (isUpstreamCooldownActive()) {
            return fallbackBatch(normalized,
                    providerName + ": upstream cooldown after rate limit");
        }
        if (tokenBucket != null && !tokenBucket.tryAcquire()) {
            log.warn("CoinGecko rate-limited locally batchSize={} reason=token bucket empty", normalized.size());
            return fallbackBatch(normalized, providerName + ": rate limited");
        }
        try {
            List<String> querySymbols = normalized.stream()
                    .map(TokenSymbolMapper::mapToCoinGeckoSymbol)
                    .map(String::toLowerCase)
                    .distinct()
                    .toList();
            Map<String, CoinGeckoMarketData> byQuerySymbol = new HashMap<>();
            for (CoinGeckoMarketData item : httpClient.fetchBySymbols(querySymbols)) {
                if (item.symbol() != null && !item.symbol().isBlank()) {
                    byQuerySymbol.put(item.symbol().toLowerCase(), item);
                }
            }
            if (byQuerySymbol.isEmpty()) {
                return fallbackMissingBatch(normalized, providerName + ": no data");
            }
            for (String okxBase : normalized) {
                String query = TokenSymbolMapper.mapToCoinGeckoSymbol(okxBase).toLowerCase();
                CoinGeckoMarketData data = byQuerySymbol.get(query);
                if (data == null && !query.equals(okxBase.toLowerCase())) {
                    data = byQuerySymbol.get(okxBase.toLowerCase());
                }
                out.put(okxBase, resolveMarketCap(okxBase, data));
            }
            return out;
        } catch (CoinGeckoRequestException ex) {
            log.warn("CoinGecko batch request unavailable size={} kind={} message={}",
                    normalized.size(), ex.kind(), ex.getMessage());
            if (ex.kind() == CoinGeckoRequestException.Kind.RATE_LIMITED) {
                enterUpstreamCooldown("rate_limited");
                return fallbackBatch(normalized, providerName + ": upstream rate limited");
            }
            if (ex.kind() == CoinGeckoRequestException.Kind.UNAVAILABLE) {
                return fallbackBatch(normalized, providerName + ": " + ex.getMessage());
            }
            return timeoutBatch(normalized, "CoinGecko " + ex.kind().name().toLowerCase());
        } catch (Exception ex) {
            log.warn("CoinGecko batch provider failed size={} : {}", normalized.size(), ex.toString());
            return fallbackBatch(normalized, providerName + ": " + ex.getClass().getSimpleName());
        }
    }

    /**
     * Scanner 使用带 deadline 的批量接口。必须在此处显式覆盖接口默认实现；否则
     * {@link MarketCapProvider} 的默认方法会逐条调用 {@link #fetch(String)}，把一次
     * CoinGecko 批量查询错误地放大成每个币一次请求和一次 token 消耗。
     */
    @Override
    public Map<String, ProviderResult<MarketCapSnapshot>> fetchBatch(
            List<String> baseSymbols, Duration timeout) {
        if (timeout != null && timeout.isZero()) {
            List<String> normalized = baseSymbols == null ? List.of() : baseSymbols.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::toUpperCase)
                    .distinct()
                    .toList();
            return timeoutBatch(normalized, "deadline exceeded");
        }
        return fetchBatch(baseSymbols);
    }

    private boolean isUpstreamCooldownActive() {
        return Instant.now().isBefore(upstreamCooldownUntil);
    }

    private void enterUpstreamCooldown(String reason) {
        Instant until = Instant.now().plus(EMPTY_RESPONSE_COOLDOWN);
        if (until.isAfter(upstreamCooldownUntil)) {
            upstreamCooldownUntil = until;
            log.warn("CoinGecko upstream cooldown enabled until={} reason={}", until, reason);
        }
    }

    private ProviderResult<MarketCapSnapshot> fallback(String baseSymbol, String reason) {
        if (snapshotRepository == null) {
            return ProviderResult.unavailable(reason);
        }
        try {
            return snapshotRepository.findById(baseSymbol)
                    .map(this::toStaleResult)
                    .orElseGet(() -> ProviderResult.unavailable(reason + ": no persisted snapshot"));
        } catch (RuntimeException ex) {
            log.warn("CoinGecko persisted fallback failed symbol={} : {}", baseSymbol, ex.toString());
            return ProviderResult.unavailable(reason + ": persisted fallback failed");
        }
    }

    private ProviderResult<MarketCapSnapshot> fallbackMissing(String baseSymbol, String reason) {
        if (snapshotRepository == null) {
            return ProviderResult.notFound(reason);
        }
        try {
            return snapshotRepository.findById(baseSymbol)
                    .map(this::toStaleResult)
                    .orElseGet(() -> ProviderResult.notFound(reason + ": no persisted snapshot"));
        } catch (RuntimeException ex) {
            log.warn("CoinGecko persisted fallback failed symbol={} : {}", baseSymbol, ex.toString());
            return ProviderResult.unavailable(reason + ": persisted fallback failed");
        }
    }

    private Map<String, ProviderResult<MarketCapSnapshot>> fallbackBatch(List<String> baseSymbols, String reason) {
        Map<String, ProviderResult<MarketCapSnapshot>> out = new LinkedHashMap<>();
        if (snapshotRepository == null) {
            for (String base : baseSymbols) {
                out.put(base, ProviderResult.unavailable(reason));
            }
            return out;
        }
        try {
            Map<String, MarketCapSnapshotEntity> stored = new HashMap<>();
            for (MarketCapSnapshotEntity entity : snapshotRepository.findAllById(baseSymbols)) {
                if (entity.getBaseSymbol() != null) {
                    stored.put(entity.getBaseSymbol().toUpperCase(), entity);
                }
            }
            for (String base : baseSymbols) {
                MarketCapSnapshotEntity entity = stored.get(base.toUpperCase());
                out.put(base, entity == null
                        ? ProviderResult.unavailable(reason + ": no persisted snapshot")
                        : toStaleResult(entity));
            }
        } catch (RuntimeException ex) {
            log.warn("CoinGecko persisted batch fallback failed size={} : {}", baseSymbols.size(), ex.toString());
            for (String base : baseSymbols) {
                out.put(base, ProviderResult.unavailable(reason + ": persisted fallback failed"));
            }
        }
        return out;
    }

    private Map<String, ProviderResult<MarketCapSnapshot>> fallbackMissingBatch(
            List<String> baseSymbols, String reason) {
        Map<String, ProviderResult<MarketCapSnapshot>> out = new LinkedHashMap<>();
        if (snapshotRepository == null) {
            for (String base : baseSymbols) {
                out.put(base, ProviderResult.notFound(reason));
            }
            return out;
        }
        try {
            Map<String, MarketCapSnapshotEntity> stored = new HashMap<>();
            for (MarketCapSnapshotEntity entity : snapshotRepository.findAllById(baseSymbols)) {
                if (entity.getBaseSymbol() != null) {
                    stored.put(entity.getBaseSymbol().toUpperCase(), entity);
                }
            }
            for (String base : baseSymbols) {
                MarketCapSnapshotEntity entity = stored.get(base.toUpperCase());
                out.put(base, entity == null
                        ? ProviderResult.notFound(reason + ": no persisted snapshot")
                        : toStaleResult(entity));
            }
        } catch (RuntimeException ex) {
            log.warn("CoinGecko persisted batch fallback failed size={} : {}", baseSymbols.size(), ex.toString());
            for (String base : baseSymbols) {
                out.put(base, ProviderResult.unavailable(reason + ": persisted fallback failed"));
            }
        }
        return out;
    }

    private Map<String, ProviderResult<MarketCapSnapshot>> timeoutBatch(
            List<String> baseSymbols, String reason) {
        Map<String, ProviderResult<MarketCapSnapshot>> out = new LinkedHashMap<>();
        for (String base : baseSymbols) {
            out.put(base, ProviderResult.timeout(reason));
        }
        return out;
    }

    private ProviderResult<MarketCapSnapshot> toStaleResult(MarketCapSnapshotEntity entity) {
        MarketCapSnapshot snapshot = new MarketCapSnapshot(
                entity.getBaseSymbol(), entity.getMarketCapUsd(), entity.getFdvUsd(),
                entity.getCirculatingSupply(), entity.getMaxSupply(), entity.getTotalSupply(),
                entity.getSnapshotAt(), entity.getSource());
        return ProviderResult.stale(snapshot, providerName + "-cache", entity.getSnapshotAt());
    }

    private void persist(MarketCapSnapshot snapshot) {
        if (snapshotRepository == null || snapshot == null) {
            return;
        }
        try {
            MarketCapSnapshotEntity entity = new MarketCapSnapshotEntity();
            entity.setBaseSymbol(snapshot.baseSymbol().toUpperCase());
            entity.setMarketCapUsd(snapshot.marketCapUsd());
            entity.setFdvUsd(snapshot.fdvUsd());
            entity.setCirculatingSupply(snapshot.circulatingSupply());
            entity.setMaxSupply(snapshot.maxSupply());
            entity.setTotalSupply(snapshot.totalSupply());
            entity.setSnapshotAt(snapshot.snapshotAt() == null ? Instant.now() : snapshot.snapshotAt());
            entity.setSource(snapshot.source() == null ? providerName : snapshot.source());
            snapshotRepository.save(entity);
        } catch (RuntimeException ex) {
            log.warn("CoinGecko persisted snapshot save failed symbol={} : {}", snapshot.baseSymbol(), ex.toString());
        }
    }

    private ProviderResult<MarketCapSnapshot> resolveMarketCap(String okxBase, CoinGeckoMarketData data) {
        if (data == null) {
            return fallbackMissing(okxBase, providerName + ": no data");
        }
        CoinGeckoMapper.Result mapped = CoinGeckoMapper.map(okxBase, data);
        if (!mapped.isUsable()) {
            return fallbackMissing(okxBase, providerName + ": " + mapped.reason());
        }
        Instant updatedAt = data.lastUpdated();
        if (updatedAt == null) {
            return fallback(okxBase, providerName + ": last_updated missing");
        }
        Duration age = Duration.between(updatedAt, Instant.now());
        MarketCapSnapshot snap = mapped.snapshot();
        if (age.isNegative() || age.compareTo(maxSourceAge) > 0) {
            return ProviderResult.stale(snap, providerName, updatedAt);
        }
        persist(snap);
        return new ProviderResult<>(snap, DataQuality.FRESH, updatedAt, providerName, null, null);
    }
}

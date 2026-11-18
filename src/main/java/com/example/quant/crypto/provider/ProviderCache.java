package com.example.quant.crypto.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第三方 Provider 的 TTL 缓存。
 *
 * <p>核心规则：<ul>
 *   <li>数据在 {@code freshTtl} 之内 → {@link DataQuality#FRESH}，可参与硬资格</li>
 *   <li>数据在 {@code staleTtl} 之内但超过 {@code freshTtl} → {@link DataQuality#STALE}，
 *       仅供观察，禁止通过 Universe 硬资格</li>
 *   <li>超过 {@code staleTtl} → 重新拉取</li>
 *   <li>明确的 {@code NOT_FOUND} 在 {@code freshTtl} 内做负缓存，避免不存在的标的反复触发第三方请求</li>
 *   <li>超时、限流等临时 {@link DataQuality#UNAVAILABLE} 不缓存，后续扫描仍可重试</li>
 * </ul>
 *
 * <p>实现为线程安全 + 单飞（同一 key 同一时刻只一个 loader 调用）。loader 抛异常会向上抛，
 * 但被 {@link #get(Object, Function)} 捕获并包装为 UNAVAILABLE。
 */
public class ProviderCache<K, T> {
    private static final Logger log = LoggerFactory.getLogger(ProviderCache.class);
    private final ConcurrentHashMap<K, Entry<T>> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, ProviderResult<T>> negativeCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, Object> inflight = new ConcurrentHashMap<>();
    private final Duration freshTtl;
    private final Duration staleTtl;
    private final String name;

    public ProviderCache(String name, Duration freshTtl, Duration staleTtl) {
        this.name = name;
        this.freshTtl = freshTtl;
        this.staleTtl = staleTtl;
    }

    public ProviderResult<T> get(K key, Function<K, ProviderResult<T>> loader) {
        Instant now = Instant.now();
        ProviderResult<T> negative = freshNegative(key, now);
        if (negative != null) {
            return negative;
        }
        Entry<T> existing = cache.get(key);
        if (existing != null) {
            Duration age = Duration.between(existing.fetchedAt, now);
            if (age.compareTo(freshTtl) <= 0) {
                return new ProviderResult<>(existing.data, DataQuality.FRESH, existing.fetchedAt,
                        existing.source, null, null);
            }
            if (age.compareTo(staleTtl) <= 0) {
                return new ProviderResult<>(existing.data, DataQuality.STALE, existing.fetchedAt,
                        existing.source, null, null);
            }
        }
        // single-flight
        Object token = new Object();
        Object prev = inflight.putIfAbsent(key, token);
        if (prev != null) {
            // another thread is fetching; return stale or unavailable
            if (existing != null) {
                return new ProviderResult<>(existing.data, DataQuality.STALE, existing.fetchedAt,
                        existing.source, null, null);
            }
            return ProviderResult.unavailable("Provider " + name + " inflight");
        }
        try {
            ProviderResult<T> result = loader.apply(key);
            // cache only when data present
            if (result != null && result.data() != null) {
                cache.put(key, new Entry<>(result.data(), result.fetchedAt(), result.source()));
                negativeCache.remove(key);
            } else if (isStableMiss(result)) {
                negativeCache.put(key, result);
                cache.remove(key);
            }
            return result;
        } catch (RuntimeException ex) {
            log.warn("Provider {} loader exception for key={} message={}", name, key, ex.getMessage());
            return ProviderResult.unavailable("Provider " + name + " exception: " + ex.getMessage());
        } finally {
            inflight.remove(key, token);
        }
    }

    /**
     * 只读查看缓存，不触发上游加载。
     *
     * <p>扫描器只把 fresh TTL 内的快照视为命中；进入 stale 窗口后返回 empty，
     * 让批量调用方重新请求上游。失败时仍可由 provider 自己返回持久化的 stale fallback，
     * 但 stale 数据不会阻止下一轮继续刷新。
     */
    public Optional<ProviderResult<T>> peek(K key) {
        Instant now = Instant.now();
        ProviderResult<T> negative = freshNegative(key, now);
        if (negative != null) {
            return Optional.of(negative);
        }
        Entry<T> existing = cache.get(key);
        if (existing == null) {
            return Optional.empty();
        }
        Duration age = Duration.between(existing.fetchedAt, now);
        if (age.isNegative() || age.compareTo(staleTtl) > 0) {
            return Optional.empty();
        }
        if (age.compareTo(freshTtl) > 0) {
            return Optional.empty();
        }
        return Optional.of(new ProviderResult<>(existing.data, DataQuality.FRESH, existing.fetchedAt,
                existing.source, null, null));
    }

    public void seed(K key, ProviderResult<T> result) {
        if (result != null && result.data() != null) {
            cache.put(key, new Entry<>(result.data(), result.fetchedAt(), result.source()));
            negativeCache.remove(key);
        } else if (isStableMiss(result)) {
            negativeCache.put(key, result);
            cache.remove(key);
        }
    }

    public void invalidate(K key) {
        cache.remove(key);
        negativeCache.remove(key);
    }

    public void clear() {
        cache.clear();
        negativeCache.clear();
    }

    public int size() {
        return cache.size() + negativeCache.size();
    }

    private ProviderResult<T> freshNegative(K key, Instant now) {
        ProviderResult<T> result = negativeCache.get(key);
        if (result == null) {
            return null;
        }
        Duration age = result.age(now);
        if (age.isNegative() || age.compareTo(freshTtl) > 0) {
            negativeCache.remove(key, result);
            return null;
        }
        return result;
    }

    private static boolean isStableMiss(ProviderResult<?> result) {
        return result != null && result.data() == null && "NOT_FOUND".equals(result.errorCode());
    }

    private record Entry<T>(T data, Instant fetchedAt, String source) {
    }
}

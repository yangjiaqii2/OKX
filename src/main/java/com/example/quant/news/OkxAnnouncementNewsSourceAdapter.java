package com.example.quant.news;

import com.example.quant.crypto.OkxRestClient;
import com.example.quant.market.MarketType;
import com.example.quant.news.dto.NewsItem;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * OKX 官方公告适配器。
 *
 * <p>当前只接入下架公告，作为交易所风险源；它不是全网新闻聚合器。
 * 公告拉取失败时抛出异常，由上层新闻分析将风险标记为 UNKNOWN 并等待，
 * 只有成功且无匹配时才返回空列表。
 */
@Component
@ConditionalOnProperty(name = "news.provider", havingValue = "OKX", matchIfMissing = true)
public final class OkxAnnouncementNewsSourceAdapter implements NewsSourceAdapter {
    private static final Logger log = LoggerFactory.getLogger(OkxAnnouncementNewsSourceAdapter.class);
    private static final String DELISTINGS_PATH =
            "/api/v5/support/announcements?annType=announcements-delistings&page=1";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final OkxRestClient okxRestClient;
    private final AtomicReference<CacheSnapshot> cache = new AtomicReference<>();

    public OkxAnnouncementNewsSourceAdapter(OkxRestClient okxRestClient) {
        this.okxRestClient = okxRestClient;
    }

    @Override
    public List<NewsItem> search(MarketType marketType, String symbol, String name,
                                 List<String> keywords, int maxResults, int lookbackHours) {
        if (marketType == null || symbol == null || symbol.isBlank()) {
            return List.of();
        }
        List<String> aliases = aliases(symbol, name, keywords);
        if (aliases.isEmpty()) {
            return List.of();
        }
        CacheSnapshot snapshot = loadAnnouncements();
        List<OfficialAnnouncement> announcements = snapshot.items();
        Instant cutoff = lookbackHours > 0 ? Instant.now().minus(Duration.ofHours(lookbackHours)) : Instant.MIN;
        int limit = Math.max(1, Math.min(50, maxResults));
        List<NewsItem> matches = announcements.stream()
                .filter(item -> item.publishedAt() == null || !item.publishedAt().isBefore(cutoff))
                .filter(item -> matches(item, aliases))
                .sorted(Comparator.comparing(OfficialAnnouncement::publishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .map(item -> toNewsItem(item, marketType, symbol, aliases))
                .toList();
        log.debug("OKX announcement search status=SUCCESS source=OKX_OFFICIAL scope=delistings-page-1 "
                        + "symbol={} lookbackHours={} fetchedAt={} matches={}",
                symbol, lookbackHours, snapshot.fetchedAt(), matches.size());
        return matches;
    }

    private CacheSnapshot loadAnnouncements() {
        Instant now = Instant.now();
        CacheSnapshot current = cache.get();
        if (current != null && current.fetchedAt().plus(CACHE_TTL).isAfter(now)) {
            return current;
        }
        synchronized (cache) {
            current = cache.get();
            if (current != null && current.fetchedAt().plus(CACHE_TTL).isAfter(now)) {
                return current;
            }
            try {
                JsonNode root = okxRestClient.publicGet(DELISTINGS_PATH);
                if (!"0".equals(root.path("code").asText("1"))) {
                    throw new IllegalStateException("OKX announcements code=" + root.path("code").asText("unknown"));
                }
                validatePayload(root);
                List<OfficialAnnouncement> parsed = parse(root);
                CacheSnapshot refreshed = new CacheSnapshot(now, parsed);
                cache.set(refreshed);
                log.info("OKX announcement refresh completed type=announcements-delistings count={}", parsed.size());
                return refreshed;
            } catch (RuntimeException ex) {
                log.warn("OKX announcement refresh unavailable reason={}", compact(ex.getMessage()));
                throw ex;
            }
        }
    }

    private static void validatePayload(JsonNode root) {
        if (root == null || !root.path("data").isArray()) {
            throw new IllegalStateException("OKX announcements payload missing data array");
        }
        for (JsonNode page : root.path("data")) {
            if (!page.path("details").isArray()) {
                throw new IllegalStateException("OKX announcements payload missing details array");
            }
        }
    }

    private static List<OfficialAnnouncement> parse(JsonNode root) {
        Map<String, OfficialAnnouncement> unique = new LinkedHashMap<>();
        for (JsonNode page : root.path("data")) {
            for (JsonNode detail : page.path("details")) {
                String title = text(detail, "title");
                if (title.isBlank()) {
                    continue;
                }
                String url = text(detail, "url");
                Instant publishedAt = epochMillis(detail.path("pTime").asText(""));
                String key = title + "|" + (publishedAt == null ? "" : publishedAt) + "|" + url;
                unique.putIfAbsent(key, new OfficialAnnouncement(
                        text(detail, "annType"), title, url, publishedAt));
            }
        }
        return List.copyOf(unique.values());
    }

    private NewsItem toNewsItem(OfficialAnnouncement item, MarketType marketType,
                                String symbol, List<String> aliases) {
        String baseAlias = aliases.isEmpty() ? symbol : aliases.get(0);
        return new NewsItem(
                item.title(),
                "OKX_OFFICIAL",
                item.url(),
                item.publishedAt(),
                item.title(),
                "NEGATIVE",
                -100,
                100,
                List.of(symbol, baseAlias),
                List.of("DELISTING"),
                marketType
        );
    }

    private static boolean matches(OfficialAnnouncement item, List<String> aliases) {
        String text = (item.title() + " " + item.url()).toUpperCase(Locale.ROOT);
        return aliases.stream().anyMatch(alias -> Pattern.compile(
                "(?<![A-Z0-9])" + Pattern.quote(alias) + "(?![A-Z0-9])")
                .matcher(text).find());
    }

    private static List<String> aliases(String symbol, String name, List<String> keywords) {
        List<String> values = new ArrayList<>();
        addAlias(values, symbol);
        addAlias(values, name);
        if (keywords != null) {
            keywords.forEach(value -> addAlias(values, value));
        }
        return values.stream().distinct().toList();
    }

    private static void addAlias(List<String> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("-")) {
            normalized = normalized.substring(0, normalized.indexOf('-'));
        }
        if (normalized.length() >= 2 && normalized.matches("[A-Z0-9]+")) {
            values.add(normalized);
        }
    }

    private static Instant epochMillis(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private static String compact(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String value = message.replaceAll("\\s+", " ").trim();
        return value.length() > 180 ? value.substring(0, 180) + "..." : value;
    }

    private record OfficialAnnouncement(String type, String title, String url, Instant publishedAt) {
    }

    private record CacheSnapshot(Instant fetchedAt, List<OfficialAnnouncement> items) {
    }
}

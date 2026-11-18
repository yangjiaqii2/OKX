package com.example.quant.system;

import com.example.quant.config.SystemFxRateProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class FxRateService {
    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);
    private final SystemFxRateProperties properties;
    private final Clock clock;
    private final Supplier<EcbFxRateClient.Quote> fetcher;
    private final AtomicReference<FxRateResponse> snapshot;

    @Autowired
    public FxRateService(SystemFxRateProperties properties, EcbFxRateClient client) {
        this(properties, Clock.systemUTC(), client::fetch);
    }

    public FxRateService(SystemFxRateProperties properties) {
        this(properties, Clock.systemUTC(), unavailableFetcher());
    }

    public FxRateService(SystemFxRateProperties properties, Clock clock) {
        this(properties, clock, unavailableFetcher());
    }

    FxRateService(SystemFxRateProperties properties, Clock clock, Supplier<EcbFxRateClient.Quote> fetcher) {
        this.properties = properties == null ? new SystemFxRateProperties() : properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.fetcher = fetcher == null ? unavailableFetcher() : fetcher;
        this.snapshot = new AtomicReference<>(fallbackResponse());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshAfterStartup() {
        refresh();
    }

    @Scheduled(cron = "${quant.system.fx-rate.refresh-cron:0 5 0 * * *}",
            zone = "${quant.system.fx-rate.zone:Asia/Shanghai}")
    public boolean refresh() {
        if (!properties.enabled()) {
            return false;
        }
        try {
            EcbFxRateClient.Quote quote = fetcher.get();
            if (quote == null || quote.rate() == null || quote.rate().signum() <= 0) {
                throw new IllegalStateException("ECB_RATE_INVALID");
            }
            FxRateResponse refreshed = new FxRateResponse("USD", "CNY", quote.rate(), quote.source(),
                    quote.fetchedAt() == null ? Instant.now(clock) : quote.fetchedAt());
            snapshot.set(refreshed);
            log.info("USD/CNY refreshed source={} rate={} updatedAt={}", refreshed.source(), refreshed.rate(), refreshed.updatedAt());
            return true;
        } catch (RuntimeException ex) {
            snapshot.updateAndGet(current -> current != null && (current.source().startsWith("ECB")
                    || current.source().startsWith("FRANKFURTER"))
                    ? new FxRateResponse(current.base(), current.quote(), current.rate(),
                            current.source().endsWith("_STALE") ? current.source() : current.source() + "_STALE",
                            current.updatedAt())
                    : fallbackResponse());
            log.warn("USD/CNY refresh failed: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    public FxRateResponse rate(String base, String quote) {
        String requestedBase = normalize(base, "USD");
        String requestedQuote = normalize(quote, "CNY");
        String configuredBase = normalize(properties.base(), "USD");
        String configuredQuote = normalize(properties.quote(), "CNY");
        if (!configuredBase.equals(requestedBase) || !configuredQuote.equals(requestedQuote)) {
            throw new IllegalArgumentException("暂不支持汇率对：" + requestedBase + "/" + requestedQuote);
        }
        FxRateResponse current = snapshot.get();
        if (current == null || current.rate() == null || current.rate().signum() <= 0) {
            throw new IllegalStateException("系统汇率未配置或无效：" + configuredBase + "/" + configuredQuote);
        }
        return current;
    }

    private FxRateResponse fallbackResponse() {
        BigDecimal rate = properties.rate();
        return new FxRateResponse(normalize(properties.base(), "USD"), normalize(properties.quote(), "CNY"),
                rate, hasText(properties.source()) ? properties.source().trim() : "FALLBACK", Instant.now(clock));
    }

    private static Supplier<EcbFxRateClient.Quote> unavailableFetcher() {
        return () -> { throw new IllegalStateException("ECB_CLIENT_UNAVAILABLE"); };
    }

    private static String normalize(String value, String fallback) {
        String text = hasText(value) ? value.trim() : fallback;
        return text.toUpperCase();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

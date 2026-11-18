package com.example.quant.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.config.SystemFxRateProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FxRateServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-12T16:05:00Z");

    @Test
    void refreshesFromEcbAndKeepsLastSuccessWhenNextRefreshFails() {
        SystemFxRateProperties properties = fallbackProperties();
        AtomicInteger calls = new AtomicInteger();
        FxRateService service = new FxRateService(properties, Clock.fixed(NOW, ZoneOffset.UTC), () -> {
            if (calls.getAndIncrement() == 0) {
                return new EcbFxRateClient.Quote(new BigDecimal("6.81"), NOW);
            }
            throw new IllegalStateException("ECB_UNAVAILABLE");
        });

        assertThat(service.refresh()).isTrue();
        assertThat(service.rate("USD", "CNY").rate()).isEqualByComparingTo("6.81");
        assertThat(service.rate("USD", "CNY").source()).isEqualTo("ECB");

        assertThat(service.refresh()).isFalse();
        FxRateResponse stale = service.rate("USD", "CNY");
        assertThat(stale.rate()).isEqualByComparingTo("6.81");
        assertThat(stale.source()).isEqualTo("ECB_STALE");
        assertThat(stale.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void firstRefreshFailureUsesHonestConfiguredFallback() {
        SystemFxRateProperties properties = fallbackProperties();
        FxRateService service = new FxRateService(properties, Clock.fixed(NOW, ZoneOffset.UTC), () -> {
            throw new IllegalStateException("ECB_UNAVAILABLE");
        });

        assertThat(service.refresh()).isFalse();

        FxRateResponse fallback = service.rate("USD", "CNY");
        assertThat(fallback.rate()).isEqualByComparingTo("7.0");
        assertThat(fallback.source()).isEqualTo("FALLBACK");
    }

    private static SystemFxRateProperties fallbackProperties() {
        SystemFxRateProperties properties = new SystemFxRateProperties();
        properties.setRate(new BigDecimal("7.0"));
        properties.setSource("FALLBACK");
        return properties;
    }
}

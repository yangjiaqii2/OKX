package com.example.quant.common;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TradingDayResolverTest {
    private final TradingDayResolver resolver = new TradingDayResolver();

    @Test
    void resolvesBusinessDayAtShanghaiMidnightAndUtcBoundary() {
        assertThat(resolver.tradingDate(Instant.parse("2026-09-06T15:59:59Z")).toString()).isEqualTo("2026-09-06");
        assertThat(resolver.tradingDate(Instant.parse("2026-09-06T16:00:00Z")).toString()).isEqualTo("2026-09-07");
        assertThat(resolver.tradingDate(Instant.parse("2026-09-07T00:30:00Z")).toString()).isEqualTo("2026-09-07");
    }

    @Test
    void delayedRecoveryKeepsEventTradingDay() {
        Instant eventTime = Instant.parse("2026-09-06T15:58:00Z");
        Instant recoveryTime = Instant.parse("2026-09-06T16:03:00Z");
        assertThat(resolver.tradingDate(eventTime).toString()).isEqualTo("2026-09-06");
        assertThat(resolver.tradingDate(eventTime)).isNotEqualTo(resolver.tradingDate(recoveryTime));
    }
}

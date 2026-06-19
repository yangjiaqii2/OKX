package com.example.quant.system;

import com.example.quant.config.SystemFxRateProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FxRateService {
    private final SystemFxRateProperties properties;
    private final Clock clock;

    @Autowired
    public FxRateService(SystemFxRateProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public FxRateService(SystemFxRateProperties properties, Clock clock) {
        this.properties = properties == null ? new SystemFxRateProperties() : properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public FxRateResponse rate(String base, String quote) {
        String requestedBase = normalize(base, "USD");
        String requestedQuote = normalize(quote, "CNY");
        String configuredBase = normalize(properties.base(), "USD");
        String configuredQuote = normalize(properties.quote(), "CNY");
        if (!configuredBase.equals(requestedBase) || !configuredQuote.equals(requestedQuote)) {
            throw new IllegalArgumentException("暂不支持汇率对：" + requestedBase + "/" + requestedQuote);
        }
        BigDecimal rate = properties.rate();
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalStateException("系统汇率未配置或无效：" + configuredBase + "/" + configuredQuote);
        }
        return new FxRateResponse(
                configuredBase,
                configuredQuote,
                rate,
                hasText(properties.source()) ? properties.source().trim() : "CONFIGURED",
                Instant.now(clock)
        );
    }

    private static String normalize(String value, String fallback) {
        String text = hasText(value) ? value.trim() : fallback;
        return text.toUpperCase();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

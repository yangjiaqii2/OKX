package com.example.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "news")
public record NewsProperties(
        boolean enabled,
        int maxResultsPerSymbol,
        int lookbackHoursStock,
        int lookbackHoursCrypto,
        boolean useMockWhenNoProvider
) {
}

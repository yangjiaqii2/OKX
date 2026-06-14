package com.example.quant.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quant.stock")
public record StockProperties(
        boolean enabled,
        String dataSource,
        int scanIntervalSeconds,
        BigDecimal minTurnoverAmount,
        BigDecimal minChangePercent,
        BigDecimal maxChangePercent,
        BigDecimal volumeSpikeRatio,
        boolean excludeSt,
        boolean analyzeOnly
) {
}

package com.example.quant.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quant.contract")
public record ContractProperties(
        boolean enabled,
        String exchange,
        int scanIntervalSeconds,
        String instType,
        String quoteCurrency,
        BigDecimal minVolume24h,
        BigDecimal change5mThreshold,
        BigDecimal volumeSpikeRatio,
        BigDecimal maxAbsFundingRate,
        int maxLeverage,
        String defaultTdMode,
        BigDecimal maxSingleMarginRate,
        BigDecimal maxSingleLossRate,
        BigDecimal maxDailyLossRate,
        BigDecimal minRiskRewardRatio,
        boolean allowAddPosition
) {
}

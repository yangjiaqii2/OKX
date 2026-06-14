package com.example.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading")
public record TradingProperties(
        String mode,
        boolean requireUserConfirm,
        boolean okxLiveEnabled,
        int orderExpireSeconds,
        boolean emergencyStop
) {
}

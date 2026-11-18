package com.example.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "trading")
public record TradingProperties(
        TradingMode mode,
        boolean requireUserConfirm,
        int orderExpireSeconds,
        boolean emergencyStop
) {
    @ConstructorBinding
    public TradingProperties {
        mode = mode == null ? TradingMode.DISABLED : mode;
    }

    public TradingProperties(String ignoredLegacyMode, boolean requireUserConfirm, boolean okxLiveEnabled,
                             int orderExpireSeconds, boolean emergencyStop) {
        this(okxLiveEnabled ? TradingMode.LIVE : TradingMode.DISABLED,
                requireUserConfirm, orderExpireSeconds, emergencyStop);
    }

    public boolean okxLiveEnabled() {
        return mode == TradingMode.LIVE;
    }
}

package com.example.quant.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

public final class EvaluationTradingGuard implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("evaluation"))) {
            return;
        }
        Binder binder = Binder.get(environment);
        TradingMode mode = binder.bind("trading.mode", TradingMode.class).orElse(TradingMode.DISABLED);
        boolean autoTrade = binder.bind("quant.agent.auto-trade.enabled", Boolean.class).orElse(true);
        if (mode == TradingMode.LIVE || autoTrade
                || "LIVE".equalsIgnoreCase(environment.getProperty("TRADING_MODE", "").strip())
                || environment.getProperty("QUANT_AGENT_AUTO_TRADE_ENABLED", Boolean.class, false)) {
            throw new IllegalStateException("Evaluation profile cannot start with LIVE trading or auto-trade enabled");
        }
    }
}

package com.example.quant.system;

import com.example.quant.config.TradingProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SystemControlService {
    private static final Logger log = LoggerFactory.getLogger(SystemControlService.class);
    private static final int DEFAULT_NO_RISK_MIN_SCORE = 70;
    private static final int MIN_NO_RISK_SCORE = 60;
    private static final int MAX_NO_RISK_SCORE = 100;
    private static final int DEFAULT_AUTO_TRADE_MIN_LEVERAGE = 1;
    private static final int MIN_AUTO_TRADE_MIN_LEVERAGE = 1;
    private static final int MAX_AUTO_TRADE_MIN_LEVERAGE = 20;

    private final AtomicBoolean emergencyStop;
    private final AtomicBoolean autoTradeEnabled = new AtomicBoolean(false);
    private volatile BigDecimal autoTradeMarginUsdt = BigDecimal.ZERO;
    private volatile AutoTradeRiskMode autoTradeRiskMode = AutoTradeRiskMode.STRICT;
    private volatile int noRiskMinScore = DEFAULT_NO_RISK_MIN_SCORE;
    private volatile int autoTradeMinLeverage = DEFAULT_AUTO_TRADE_MIN_LEVERAGE;
    private volatile Instant updatedAt = Instant.now();

    public SystemControlService(TradingProperties tradingProperties) {
        this.emergencyStop = new AtomicBoolean(tradingProperties.emergencyStop());
    }

    public SystemStatus status() {
        return new SystemStatus(emergencyStop.get(), autoTradeEnabled.get(), autoTradeMarginUsdt,
                autoTradeRiskMode, noRiskMinScore, autoTradeMinLeverage, updatedAt);
    }

    public boolean emergencyStopEnabled() {
        return emergencyStop.get();
    }

    public boolean autoTradeEnabled() {
        return autoTradeEnabled.get() && !emergencyStop.get();
    }

    public BigDecimal autoTradeMarginUsdt() {
        return autoTradeMarginUsdt;
    }

    public AutoTradeRiskMode autoTradeRiskMode() {
        return autoTradeRiskMode;
    }

    public int noRiskMinScore() {
        return noRiskMinScore;
    }

    public int autoTradeMinLeverage() {
        return autoTradeMinLeverage;
    }

    public SystemStatus emergencyStop() {
        emergencyStop.set(true);
        autoTradeEnabled.set(false);
        updatedAt = Instant.now();
        log.warn("System emergency stop enabled; auto trade disabled");
        return status();
    }

    public SystemStatus resume() {
        emergencyStop.set(false);
        updatedAt = Instant.now();
        log.info("System emergency stop cleared");
        return status();
    }

    public SystemStatus enableAutoTrade() {
        return enableAutoTrade(null, AutoTradeRiskMode.STRICT);
    }

    public SystemStatus enableAutoTrade(BigDecimal marginUsdt) {
        return enableAutoTrade(marginUsdt, AutoTradeRiskMode.STRICT);
    }

    public SystemStatus enableAutoTrade(BigDecimal marginUsdt, AutoTradeRiskMode riskMode) {
        return enableAutoTrade(marginUsdt, riskMode, null);
    }

    public SystemStatus enableAutoTrade(BigDecimal marginUsdt, AutoTradeRiskMode riskMode, Integer requestedNoRiskMinScore) {
        return enableAutoTrade(marginUsdt, riskMode, requestedNoRiskMinScore, null);
    }

    public SystemStatus enableAutoTrade(BigDecimal marginUsdt, AutoTradeRiskMode riskMode, Integer requestedNoRiskMinScore,
                                        Integer requestedAutoTradeMinLeverage) {
        if (emergencyStop.get()) {
            throw new IllegalArgumentException("紧急停止状态下不能开启自动交易");
        }
        if (riskMode == null) {
            throw new IllegalArgumentException("自动交易风险模式不能为空");
        }
        if (marginUsdt != null) {
            if (marginUsdt.signum() <= 0) {
                throw new IllegalArgumentException("自动交易总保证金目标预算必须大于0");
            }
            autoTradeMarginUsdt = marginUsdt;
        }
        if (requestedNoRiskMinScore != null) {
            if (requestedNoRiskMinScore < MIN_NO_RISK_SCORE || requestedNoRiskMinScore > MAX_NO_RISK_SCORE) {
                throw new IllegalArgumentException("无风控最低自动交易分数必须在60到100之间");
            }
            noRiskMinScore = requestedNoRiskMinScore;
        }
        if (requestedAutoTradeMinLeverage != null) {
            if (requestedAutoTradeMinLeverage < MIN_AUTO_TRADE_MIN_LEVERAGE
                    || requestedAutoTradeMinLeverage > MAX_AUTO_TRADE_MIN_LEVERAGE) {
                throw new IllegalArgumentException("自动交易最低杠杆倍数必须在1到20之间");
            }
            autoTradeMinLeverage = requestedAutoTradeMinLeverage;
        }
        autoTradeRiskMode = riskMode;
        autoTradeEnabled.set(true);
        updatedAt = Instant.now();
        log.warn("Auto trade runtime switch enabled by user action, totalBudgetUsdt={}, budgetMode=TARGET_UTILIZATION, riskMode={}, noRiskMinScore={}, minLeverage={}",
                autoTradeMarginUsdt, autoTradeRiskMode, noRiskMinScore, autoTradeMinLeverage);
        return status();
    }

    public SystemStatus disableAutoTrade() {
        autoTradeEnabled.set(false);
        updatedAt = Instant.now();
        log.info("Auto trade runtime switch disabled by user action");
        return status();
    }

    public record SystemStatus(
            boolean emergencyStop,
            boolean autoTradeEnabled,
            BigDecimal autoTradeMarginUsdt,
            AutoTradeRiskMode autoTradeRiskMode,
            int noRiskMinScore,
            int autoTradeMinLeverage,
            Instant updatedAt
    ) {
    }
}

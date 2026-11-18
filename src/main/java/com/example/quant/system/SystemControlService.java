package com.example.quant.system;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SystemControlService {
    private static final Logger log = LoggerFactory.getLogger(SystemControlService.class);
    private static final String CONTROL_ID = "GLOBAL";
    private static final int DEFAULT_NO_RISK_MIN_SCORE = 70;
    private static final int MIN_NO_RISK_SCORE = 60;
    private static final int MAX_NO_RISK_SCORE = 100;
    private static final int DEFAULT_AUTO_TRADE_MIN_LEVERAGE = 1;
    private static final int MIN_AUTO_TRADE_MIN_LEVERAGE = 1;
    private static final int MAX_AUTO_TRADE_MIN_LEVERAGE = 20;

    private final AtomicBoolean emergencyStop;
    private final AtomicBoolean autoTradeEnabled = new AtomicBoolean(false);
    private volatile BigDecimal autoTradeMarginUsdt = BigDecimal.ZERO;
    private volatile AutoTradeBudgetMode autoTradeBudgetMode = AutoTradeBudgetMode.FIXED_USDT;
    private volatile BigDecimal autoTradeMarginPct = BigDecimal.ZERO;
    private volatile AutoTradeRiskMode autoTradeRiskMode = AutoTradeRiskMode.STRICT;
    private volatile int noRiskMinScore = DEFAULT_NO_RISK_MIN_SCORE;
    private volatile int autoTradeMinLeverage = DEFAULT_AUTO_TRADE_MIN_LEVERAGE;
    private volatile String autoTradeOwnerUsername = OkxCredentialStore.SYSTEM_USER;
    private volatile Instant updatedAt = Instant.now();
    private final SystemControlRepository repository;

    public SystemControlService(TradingProperties tradingProperties) {
        this(tradingProperties, null);
    }

    @Autowired
    public SystemControlService(TradingProperties tradingProperties, SystemControlRepository repository) {
        this.repository = repository;
        this.emergencyStop = new AtomicBoolean(tradingProperties.emergencyStop());
        loadPersistedState();
    }

    public SystemStatus status() {
        return new SystemStatus(emergencyStop.get(), autoTradeEnabled.get(), autoTradeMarginUsdt,
                autoTradeBudgetMode, autoTradeMarginPct, autoTradeRiskMode, noRiskMinScore,
                autoTradeMinLeverage, autoTradeOwnerUsername, updatedAt);
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

    public AutoTradeBudgetMode autoTradeBudgetMode() {
        return autoTradeBudgetMode;
    }

    public BigDecimal autoTradeMarginPct() {
        return autoTradeMarginPct;
    }

    public BigDecimal resolveAutoTradeTotalBudget(BigDecimal availableBalance) {
        if (autoTradeBudgetMode == AutoTradeBudgetMode.AVAILABLE_BALANCE_PERCENT) {
            if (availableBalance == null || availableBalance.signum() <= 0 || autoTradeMarginPct.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return availableBalance.multiply(autoTradeMarginPct)
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.DOWN)
                    .max(BigDecimal.ZERO)
                    .stripTrailingZeros();
        }
        return autoTradeMarginUsdt == null ? BigDecimal.ZERO : autoTradeMarginUsdt.max(BigDecimal.ZERO);
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

    public String autoTradeOwnerUsername() {
        return autoTradeOwnerUsername;
    }

    public SystemStatus selectTradingOwner(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("交易账户用户不能为空");
        }
        autoTradeOwnerUsername = username.trim();
        updatedAt = Instant.now();
        persist();
        return status();
    }

    public SystemStatus emergencyStop() {
        emergencyStop.set(true);
        autoTradeEnabled.set(false);
        updatedAt = Instant.now();
        persist();
        log.warn("System emergency stop enabled; auto trade disabled");
        return status();
    }

    public SystemStatus resume() {
        emergencyStop.set(false);
        updatedAt = Instant.now();
        persist();
        log.info("System emergency stop cleared");
        return status();
    }

    public SystemStatus enableAutoTrade() {
        return finishEnable(AutoTradeRiskMode.STRICT, null, null);
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
        if (marginUsdt == null || marginUsdt.signum() <= 0) {
            throw new IllegalArgumentException("自动交易总保证金目标预算必须大于0");
        }
        autoTradeBudgetMode = AutoTradeBudgetMode.FIXED_USDT;
        autoTradeMarginUsdt = marginUsdt;
        autoTradeMarginPct = BigDecimal.ZERO;
        return finishEnable(riskMode, requestedNoRiskMinScore, requestedAutoTradeMinLeverage);
    }

    public SystemStatus enableAutoTradePercent(BigDecimal marginPct, AutoTradeRiskMode riskMode) {
        return enableAutoTradePercent(marginPct, riskMode, null, null);
    }

    public SystemStatus enableAutoTradePercent(BigDecimal marginPct, AutoTradeRiskMode riskMode,
                                               Integer requestedNoRiskMinScore, Integer requestedAutoTradeMinLeverage) {
        if (marginPct == null || marginPct.signum() <= 0 || marginPct.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("可用余额百分比必须在0到100之间");
        }
        autoTradeBudgetMode = AutoTradeBudgetMode.AVAILABLE_BALANCE_PERCENT;
        autoTradeMarginPct = marginPct.stripTrailingZeros();
        autoTradeMarginUsdt = BigDecimal.ZERO;
        return finishEnable(riskMode, requestedNoRiskMinScore, requestedAutoTradeMinLeverage);
    }

    private SystemStatus finishEnable(AutoTradeRiskMode riskMode, Integer requestedNoRiskMinScore,
                                      Integer requestedAutoTradeMinLeverage) {
        if (emergencyStop.get()) {
            throw new IllegalArgumentException("紧急停止状态下不能开启自动交易");
        }
        if (riskMode == null) {
            throw new IllegalArgumentException("自动交易风险模式不能为空");
        }
        if (requestedNoRiskMinScore != null) {
            if (requestedNoRiskMinScore < MIN_NO_RISK_SCORE || requestedNoRiskMinScore > MAX_NO_RISK_SCORE) {
                throw new IllegalArgumentException("Shadow 最低观察分数必须在60到100之间");
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
        autoTradeOwnerUsername = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        autoTradeEnabled.set(true);
        updatedAt = Instant.now();
        persist();
        log.warn("Auto trade runtime switch enabled by user action, fixedBudgetUsdt={}, balancePercent={}, budgetMode={}, riskMode={}, noRiskMinScore={}, minLeverage={}",
                autoTradeMarginUsdt, autoTradeMarginPct, autoTradeBudgetMode, autoTradeRiskMode, noRiskMinScore, autoTradeMinLeverage);
        return status();
    }

    public SystemStatus disableAutoTrade() {
        autoTradeEnabled.set(false);
        updatedAt = Instant.now();
        persist();
        log.info("Auto trade runtime switch disabled by user action");
        return status();
    }

    private void loadPersistedState() {
        if (repository == null) {
            return;
        }
        repository.findById(CONTROL_ID).ifPresent(entity -> {
            emergencyStop.set(entity.isEmergencyStop());
            autoTradeEnabled.set(entity.isAutoTradeEnabled());
            autoTradeOwnerUsername = hasText(entity.getAutoTradeOwnerUsername())
                    ? entity.getAutoTradeOwnerUsername()
                    : OkxCredentialStore.SYSTEM_USER;
            autoTradeRiskMode = parseRiskMode(entity.getAutoTradeRiskMode());
            autoTradeMarginUsdt = entity.getAutoTradeMarginUsdt() == null
                    ? BigDecimal.ZERO
                    : entity.getAutoTradeMarginUsdt();
            autoTradeBudgetMode = parseBudgetMode(entity.getAutoTradeBudgetMode());
            autoTradeMarginPct = entity.getAutoTradeMarginPct() == null
                    ? BigDecimal.ZERO
                    : entity.getAutoTradeMarginPct();
            noRiskMinScore = entity.getNoRiskMinScore() <= 0
                    ? DEFAULT_NO_RISK_MIN_SCORE
                    : entity.getNoRiskMinScore();
            autoTradeMinLeverage = entity.getAutoTradeMinLeverage() <= 0
                    ? DEFAULT_AUTO_TRADE_MIN_LEVERAGE
                    : entity.getAutoTradeMinLeverage();
            updatedAt = entity.getUpdatedAt() == null ? Instant.now() : entity.getUpdatedAt();
        });
    }

    private void persist() {
        if (repository == null) {
            return;
        }
        SystemControlEntity entity = repository.findById(CONTROL_ID).orElseGet(SystemControlEntity::new);
        entity.setId(CONTROL_ID);
        entity.setEmergencyStop(emergencyStop.get());
        entity.setAutoTradeEnabled(autoTradeEnabled.get());
        entity.setAutoTradeOwnerUsername(autoTradeOwnerUsername);
        entity.setAutoTradeRiskMode(autoTradeRiskMode.name());
        entity.setAutoTradeMarginUsdt(autoTradeMarginUsdt);
        entity.setAutoTradeBudgetMode(autoTradeBudgetMode.name());
        entity.setAutoTradeMarginPct(autoTradeMarginPct);
        entity.setNoRiskMinScore(noRiskMinScore);
        entity.setAutoTradeMinLeverage(autoTradeMinLeverage);
        entity.setUpdatedAt(updatedAt);
        repository.save(entity);
    }

    private static AutoTradeRiskMode parseRiskMode(String value) {
        if (!hasText(value)) {
            return AutoTradeRiskMode.STRICT;
        }
        try {
            return AutoTradeRiskMode.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            return AutoTradeRiskMode.STRICT;
        }
    }

    private static AutoTradeBudgetMode parseBudgetMode(String value) {
        if (!hasText(value)) {
            return AutoTradeBudgetMode.FIXED_USDT;
        }
        try {
            return AutoTradeBudgetMode.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            return AutoTradeBudgetMode.FIXED_USDT;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record SystemStatus(
            boolean emergencyStop,
            boolean autoTradeEnabled,
            BigDecimal autoTradeMarginUsdt,
            AutoTradeBudgetMode autoTradeBudgetMode,
            BigDecimal autoTradeMarginPct,
            AutoTradeRiskMode autoTradeRiskMode,
            int noRiskMinScore,
            int autoTradeMinLeverage,
            String autoTradeOwnerUsername,
            Instant updatedAt
    ) {
    }
}

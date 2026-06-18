package com.example.quant.system;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "system_control_state")
public class SystemControlEntity {
    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "emergency_stop", nullable = false)
    private boolean emergencyStop;

    @Column(name = "auto_trade_enabled", nullable = false)
    private boolean autoTradeEnabled;

    @Column(name = "auto_trade_owner_username", nullable = false, length = 128)
    private String autoTradeOwnerUsername;

    @Column(name = "auto_trade_risk_mode", nullable = false, length = 32)
    private String autoTradeRiskMode;

    @Column(name = "auto_trade_margin_usdt", precision = 30, scale = 12)
    private BigDecimal autoTradeMarginUsdt;

    @Column(name = "no_risk_min_score", nullable = false)
    private int noRiskMinScore;

    @Column(name = "auto_trade_min_leverage", nullable = false)
    private int autoTradeMinLeverage;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean isEmergencyStop() {
        return emergencyStop;
    }

    public void setEmergencyStop(boolean emergencyStop) {
        this.emergencyStop = emergencyStop;
    }

    public boolean isAutoTradeEnabled() {
        return autoTradeEnabled;
    }

    public void setAutoTradeEnabled(boolean autoTradeEnabled) {
        this.autoTradeEnabled = autoTradeEnabled;
    }

    public String getAutoTradeOwnerUsername() {
        return autoTradeOwnerUsername;
    }

    public void setAutoTradeOwnerUsername(String autoTradeOwnerUsername) {
        this.autoTradeOwnerUsername = autoTradeOwnerUsername;
    }

    public String getAutoTradeRiskMode() {
        return autoTradeRiskMode;
    }

    public void setAutoTradeRiskMode(String autoTradeRiskMode) {
        this.autoTradeRiskMode = autoTradeRiskMode;
    }

    public BigDecimal getAutoTradeMarginUsdt() {
        return autoTradeMarginUsdt;
    }

    public void setAutoTradeMarginUsdt(BigDecimal autoTradeMarginUsdt) {
        this.autoTradeMarginUsdt = autoTradeMarginUsdt;
    }

    public int getNoRiskMinScore() {
        return noRiskMinScore;
    }

    public void setNoRiskMinScore(int noRiskMinScore) {
        this.noRiskMinScore = noRiskMinScore;
    }

    public int getAutoTradeMinLeverage() {
        return autoTradeMinLeverage;
    }

    public void setAutoTradeMinLeverage(int autoTradeMinLeverage) {
        this.autoTradeMinLeverage = autoTradeMinLeverage;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

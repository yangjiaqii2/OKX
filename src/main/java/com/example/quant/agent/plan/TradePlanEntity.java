package com.example.quant.agent.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_plan")
public class TradePlanEntity {
    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "scan_run_id")
    private Long scanRunId;

    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;

    @Column(name = "direction", nullable = false, length = 32)
    private String direction;

    @Column(name = "entry_type", nullable = false, length = 32)
    private String entryType;

    @Column(name = "entry_price", precision = 30, scale = 12)
    private BigDecimal entryPrice;

    @Column(name = "entry_zone_low", precision = 30, scale = 12)
    private BigDecimal entryZoneLow;

    @Column(name = "entry_zone_high", precision = 30, scale = 12)
    private BigDecimal entryZoneHigh;

    @Column(name = "leverage")
    private int leverage;

    @Column(name = "position_notional", precision = 30, scale = 12)
    private BigDecimal positionNotional;

    @Column(name = "margin_required", precision = 30, scale = 12)
    private BigDecimal marginRequired;

    @Column(name = "stop_loss", precision = 30, scale = 12)
    private BigDecimal stopLoss;

    @Column(name = "take_profit", precision = 30, scale = 12)
    private BigDecimal takeProfit;

    @Column(name = "risk_reward_ratio", precision = 18, scale = 8)
    private BigDecimal riskRewardRatio;

    @Column(name = "max_loss_usdt", precision = 30, scale = 12)
    private BigDecimal maxLossUsdt;

    @Column(name = "max_loss_percent", precision = 18, scale = 8)
    private BigDecimal maxLossPercent;

    @Column(name = "allow_trade", nullable = false)
    private boolean allowTrade;

    @Column(name = "deny_reason", columnDefinition = "TEXT")
    private String denyReason;

    @Column(name = "plan_json", columnDefinition = "TEXT")
    private String planJson;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expire_at")
    private Instant expireAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setScanRunId(Long scanRunId) {
        this.scanRunId = scanRunId;
    }

    public void setInstId(String instId) {
        this.instId = instId;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public void setEntryType(String entryType) {
        this.entryType = entryType;
    }

    public void setEntryPrice(BigDecimal entryPrice) {
        this.entryPrice = entryPrice;
    }

    public void setEntryZoneLow(BigDecimal entryZoneLow) {
        this.entryZoneLow = entryZoneLow;
    }

    public void setEntryZoneHigh(BigDecimal entryZoneHigh) {
        this.entryZoneHigh = entryZoneHigh;
    }

    public void setLeverage(int leverage) {
        this.leverage = leverage;
    }

    public void setPositionNotional(BigDecimal positionNotional) {
        this.positionNotional = positionNotional;
    }

    public void setMarginRequired(BigDecimal marginRequired) {
        this.marginRequired = marginRequired;
    }

    public void setStopLoss(BigDecimal stopLoss) {
        this.stopLoss = stopLoss;
    }

    public void setTakeProfit(BigDecimal takeProfit) {
        this.takeProfit = takeProfit;
    }

    public void setRiskRewardRatio(BigDecimal riskRewardRatio) {
        this.riskRewardRatio = riskRewardRatio;
    }

    public void setMaxLossUsdt(BigDecimal maxLossUsdt) {
        this.maxLossUsdt = maxLossUsdt;
    }

    public void setMaxLossPercent(BigDecimal maxLossPercent) {
        this.maxLossPercent = maxLossPercent;
    }

    public void setAllowTrade(boolean allowTrade) {
        this.allowTrade = allowTrade;
    }

    public void setDenyReason(String denyReason) {
        this.denyReason = denyReason;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setExpireAt(Instant expireAt) {
        this.expireAt = expireAt;
    }
}

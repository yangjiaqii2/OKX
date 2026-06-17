package com.example.quant.agent.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "auto_trade_record")
public class AutoTradeRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "trigger_type", nullable = false, length = 32)
    private String triggerType;

    @Column(name = "inst_id", length = 64)
    private String instId;

    @Column(name = "trade_plan_id", length = 36)
    private String tradePlanId;

    @Column(name = "pending_order_id", length = 36)
    private String pendingOrderId;

    @Column(name = "okx_order_id", length = 128)
    private String okxOrderId;

    @Column(name = "action", length = 32)
    private String action;

    @Column(name = "pos_side", length = 16)
    private String posSide;

    @Column(name = "leverage")
    private Integer leverage;

    @Column(name = "margin_amount", precision = 30, scale = 12)
    private BigDecimal marginAmount;

    @Column(name = "entry_price", precision = 30, scale = 12)
    private BigDecimal entryPrice;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "candidate_score")
    private Integer candidateScore;

    @Column(name = "trend_direction", length = 32)
    private String trendDirection;

    @Column(name = "last_price", precision = 30, scale = 12)
    private BigDecimal lastPrice;

    @Column(name = "risk_reward_ratio", precision = 18, scale = 8)
    private BigDecimal riskRewardRatio;

    @Column(name = "spread_bps", precision = 18, scale = 8)
    private BigDecimal spreadBps;

    @Column(name = "bid_depth_usdt", precision = 30, scale = 12)
    private BigDecimal bidDepthUsdt;

    @Column(name = "ask_depth_usdt", precision = 30, scale = 12)
    private BigDecimal askDepthUsdt;

    @Column(name = "market_risk_level", length = 32)
    private String marketRiskLevel;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public String getInstId() {
        return instId;
    }

    public String getTradePlanId() {
        return tradePlanId;
    }

    public String getPendingOrderId() {
        return pendingOrderId;
    }

    public String getOkxOrderId() {
        return okxOrderId;
    }

    public String getAction() {
        return action;
    }

    public String getPosSide() {
        return posSide;
    }

    public Integer getLeverage() {
        return leverage;
    }

    public BigDecimal getMarginAmount() {
        return marginAmount;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public Integer getCandidateScore() {
        return candidateScore;
    }

    public String getTrendDirection() {
        return trendDirection;
    }

    public BigDecimal getLastPrice() {
        return lastPrice;
    }

    public BigDecimal getRiskRewardRatio() {
        return riskRewardRatio;
    }

    public BigDecimal getSpreadBps() {
        return spreadBps;
    }

    public BigDecimal getBidDepthUsdt() {
        return bidDepthUsdt;
    }

    public BigDecimal getAskDepthUsdt() {
        return askDepthUsdt;
    }

    public String getMarketRiskLevel() {
        return marketRiskLevel;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public void setInstId(String instId) {
        this.instId = instId;
    }

    public void setTradePlanId(String tradePlanId) {
        this.tradePlanId = tradePlanId;
    }

    public void setPendingOrderId(String pendingOrderId) {
        this.pendingOrderId = pendingOrderId;
    }

    public void setOkxOrderId(String okxOrderId) {
        this.okxOrderId = okxOrderId;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public void setPosSide(String posSide) {
        this.posSide = posSide;
    }

    public void setLeverage(Integer leverage) {
        this.leverage = leverage;
    }

    public void setMarginAmount(BigDecimal marginAmount) {
        this.marginAmount = marginAmount;
    }

    public void setEntryPrice(BigDecimal entryPrice) {
        this.entryPrice = entryPrice;
    }

    public void setCandidateCount(int candidateCount) {
        this.candidateCount = candidateCount;
    }

    public void setCandidateScore(Integer candidateScore) {
        this.candidateScore = candidateScore;
    }

    public void setTrendDirection(String trendDirection) {
        this.trendDirection = trendDirection;
    }

    public void setLastPrice(BigDecimal lastPrice) {
        this.lastPrice = lastPrice;
    }

    public void setRiskRewardRatio(BigDecimal riskRewardRatio) {
        this.riskRewardRatio = riskRewardRatio;
    }

    public void setSpreadBps(BigDecimal spreadBps) {
        this.spreadBps = spreadBps;
    }

    public void setBidDepthUsdt(BigDecimal bidDepthUsdt) {
        this.bidDepthUsdt = bidDepthUsdt;
    }

    public void setAskDepthUsdt(BigDecimal askDepthUsdt) {
        this.askDepthUsdt = askDepthUsdt;
    }

    public void setMarketRiskLevel(String marketRiskLevel) {
        this.marketRiskLevel = marketRiskLevel;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

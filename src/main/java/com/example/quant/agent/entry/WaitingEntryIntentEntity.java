package com.example.quant.agent.entry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "waiting_entry_intent")
public class WaitingEntryIntentEntity {
    @Id
    @Column(name = "intent_id", length = 36)
    private String intentId;

    @Column(name = "decision_client_order_id", nullable = false, length = 32)
    private String decisionClientOrderId;

    @Column(name = "active_key", length = 320)
    private String activeKey;

    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;

    @Column(name = "strategy_id", nullable = false, length = 64)
    private String strategyId;

    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;

    @Column(name = "direction", nullable = false, length = 16)
    private String direction;

    @Column(name = "wait_type", nullable = false, length = 32)
    private String waitType;

    @Column(name = "reference_price", nullable = false, precision = 30, scale = 12)
    private BigDecimal referencePrice;

    @Column(name = "invalidation_price", nullable = false, precision = 30, scale = 12)
    private BigDecimal invalidationPrice;

    @Column(name = "decision_candle_ts", nullable = false)
    private Instant decisionCandleTimestamp;

    @Column(name = "expire_at", nullable = false)
    private Instant expireAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WaitingEntryIntentStatus status;

    @Column(name = "trigger_count", nullable = false)
    private int triggerCount;

    @Column(name = "reason", length = 240)
    private String reason;

    @Column(name = "pending_order_id", length = 36)
    private String pendingOrderId;

    @Column(name = "okx_order_id", length = 128)
    private String okxOrderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }
    public String getDecisionClientOrderId() { return decisionClientOrderId; }
    public void setDecisionClientOrderId(String decisionClientOrderId) { this.decisionClientOrderId = decisionClientOrderId; }
    public String getActiveKey() { return activeKey; }
    public void setActiveKey(String activeKey) { this.activeKey = activeKey; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getStrategyId() { return strategyId; }
    public void setStrategyId(String strategyId) { this.strategyId = strategyId; }
    public String getInstId() { return instId; }
    public void setInstId(String instId) { this.instId = instId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getWaitType() { return waitType; }
    public void setWaitType(String waitType) { this.waitType = waitType; }
    public BigDecimal getReferencePrice() { return referencePrice; }
    public void setReferencePrice(BigDecimal referencePrice) { this.referencePrice = referencePrice; }
    public BigDecimal getInvalidationPrice() { return invalidationPrice; }
    public void setInvalidationPrice(BigDecimal invalidationPrice) { this.invalidationPrice = invalidationPrice; }
    public Instant getDecisionCandleTimestamp() { return decisionCandleTimestamp; }
    public void setDecisionCandleTimestamp(Instant decisionCandleTimestamp) { this.decisionCandleTimestamp = decisionCandleTimestamp; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
    public WaitingEntryIntentStatus getStatus() { return status; }
    public void setStatus(WaitingEntryIntentStatus status) { this.status = status; }
    public int getTriggerCount() { return triggerCount; }
    public void setTriggerCount(int triggerCount) { this.triggerCount = triggerCount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getPendingOrderId() { return pendingOrderId; }
    public void setPendingOrderId(String pendingOrderId) { this.pendingOrderId = pendingOrderId; }
    public String getOkxOrderId() { return okxOrderId; }
    public void setOkxOrderId(String okxOrderId) { this.okxOrderId = okxOrderId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}


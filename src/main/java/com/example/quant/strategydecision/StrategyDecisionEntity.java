package com.example.quant.strategydecision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "strategy_decision")
public class StrategyDecisionEntity {
    @Id
    @Column(name = "decision_id", length = 36)
    private String decisionId;

    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;

    @Column(name = "strategy_id", nullable = false, length = 64)
    private String strategyId;

    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;

    @Column(name = "timeframe", nullable = false, length = 16)
    private String timeframe;

    @Column(name = "candle_ts", nullable = false)
    private Instant candleTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private StrategyDecisionStatus status;

    @Column(name = "client_order_id", nullable = false, length = 32)
    private String clientOrderId;

    @Column(name = "pending_order_id", length = 36)
    private String pendingOrderId;

    @Column(name = "okx_order_id", length = 128)
    private String okxOrderId;

    @Column(name = "reason", length = 240)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getDecisionId() { return decisionId; }
    public void setDecisionId(String decisionId) { this.decisionId = decisionId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getStrategyId() { return strategyId; }
    public void setStrategyId(String strategyId) { this.strategyId = strategyId; }
    public String getInstId() { return instId; }
    public void setInstId(String instId) { this.instId = instId; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
    public Instant getCandleTimestamp() { return candleTimestamp; }
    public void setCandleTimestamp(Instant candleTimestamp) { this.candleTimestamp = candleTimestamp; }
    public StrategyDecisionStatus getStatus() { return status; }
    public void setStatus(StrategyDecisionStatus status) { this.status = status; }
    public String getClientOrderId() { return clientOrderId; }
    public void setClientOrderId(String clientOrderId) { this.clientOrderId = clientOrderId; }
    public String getPendingOrderId() { return pendingOrderId; }
    public void setPendingOrderId(String pendingOrderId) { this.pendingOrderId = pendingOrderId; }
    public String getOkxOrderId() { return okxOrderId; }
    public void setOkxOrderId(String okxOrderId) { this.okxOrderId = okxOrderId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

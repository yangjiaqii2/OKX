package com.example.quant.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "close_position_record")
public class ClosePositionRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;

    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;

    @Column(name = "pos_side", length = 16)
    private String posSide;

    @Column(name = "margin_mode", length = 16)
    private String marginMode;

    @Column(name = "close_order_id", length = 128)
    private String closeOrderId;

    @Column(name = "close_cl_ord_id", length = 64)
    private String closeClOrdId;

    @Column(name = "size_value", precision = 30, scale = 12)
    private BigDecimal size;

    @Column(name = "avg_px", precision = 30, scale = 12)
    private BigDecimal avgPx;

    @Column(name = "realized_pnl", precision = 30, scale = 12)
    private BigDecimal realizedPnl;

    @Column(name = "fee", precision = 30, scale = 12)
    private BigDecimal fee;

    @Column(name = "funding_fee", precision = 30, scale = 12)
    private BigDecimal fundingFee;

    @Column(name = "pnl_completeness", nullable = false, length = 16)
    private String pnlCompleteness = "UNKNOWN";

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "auto_trade_record_id")
    private Long autoTradeRecordId;

    @Column(name = "pending_order_id", length = 36)
    private String pendingOrderId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "request_sent")
    private Boolean requestSent;

    @Column(name = "error_class", length = 32)
    private String errorClass;

    @Column(name = "recovery_result", length = 64)
    private String recoveryResult;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "trade_plan_id", length = 36)
    private String tradePlanId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getInstId() {
        return instId;
    }

    public void setInstId(String instId) {
        this.instId = instId;
    }

    public String getPosSide() {
        return posSide;
    }

    public void setPosSide(String posSide) {
        this.posSide = posSide;
    }

    public String getMarginMode() {
        return marginMode;
    }

    public void setMarginMode(String marginMode) {
        this.marginMode = marginMode;
    }

    public String getCloseOrderId() {
        return closeOrderId;
    }

    public void setCloseOrderId(String closeOrderId) {
        this.closeOrderId = closeOrderId;
    }

    public String getCloseClOrdId() {
        return closeClOrdId;
    }

    public void setCloseClOrdId(String closeClOrdId) {
        this.closeClOrdId = closeClOrdId;
    }

    public BigDecimal getSize() {
        return size;
    }

    public void setSize(BigDecimal size) {
        this.size = size;
    }

    public BigDecimal getAvgPx() {
        return avgPx;
    }

    public void setAvgPx(BigDecimal avgPx) {
        this.avgPx = avgPx;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public BigDecimal getFundingFee() {
        return fundingFee;
    }

    public void setFundingFee(BigDecimal fundingFee) {
        this.fundingFee = fundingFee;
    }

    public String getPnlCompleteness() {
        return pnlCompleteness;
    }

    public void setPnlCompleteness(String pnlCompleteness) {
        this.pnlCompleteness = pnlCompleteness;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getAutoTradeRecordId() {
        return autoTradeRecordId;
    }

    public void setAutoTradeRecordId(Long autoTradeRecordId) {
        this.autoTradeRecordId = autoTradeRecordId;
    }

    public String getPendingOrderId() {
        return pendingOrderId;
    }

    public void setPendingOrderId(String pendingOrderId) {
        this.pendingOrderId = pendingOrderId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Boolean getRequestSent() { return requestSent; }
    public void setRequestSent(Boolean requestSent) { this.requestSent = requestSent; }
    public String getErrorClass() { return errorClass; }
    public void setErrorClass(String errorClass) { this.errorClass = errorClass; }
    public String getRecoveryResult() { return recoveryResult; }
    public void setRecoveryResult(String recoveryResult) { this.recoveryResult = recoveryResult; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = Math.max(0, retryCount); }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getTradePlanId() { return tradePlanId; }
    public void setTradePlanId(String tradePlanId) { this.tradePlanId = tradePlanId; }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

package com.example.quant.agent.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "trade_review")
public class TradeReviewEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;

    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;

    @Column(name = "pending_order_id", length = 36)
    private String pendingOrderId;

    @Column(name = "auto_trade_record_id")
    private Long autoTradeRecordId;

    @Column(name = "close_position_record_id", nullable = false)
    private Long closePositionRecordId;

    @Column(name = "review_reason", nullable = false, length = 128)
    private String reviewReason;

    @Column(name = "strategy_tag", nullable = false, length = 64)
    private String strategyTag;

    @Column(name = "improvement_hint", columnDefinition = "TEXT")
    private String improvementHint;

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

    public String getPendingOrderId() {
        return pendingOrderId;
    }

    public void setPendingOrderId(String pendingOrderId) {
        this.pendingOrderId = pendingOrderId;
    }

    public Long getAutoTradeRecordId() {
        return autoTradeRecordId;
    }

    public void setAutoTradeRecordId(Long autoTradeRecordId) {
        this.autoTradeRecordId = autoTradeRecordId;
    }

    public Long getClosePositionRecordId() {
        return closePositionRecordId;
    }

    public void setClosePositionRecordId(Long closePositionRecordId) {
        this.closePositionRecordId = closePositionRecordId;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }

    public String getStrategyTag() {
        return strategyTag;
    }

    public void setStrategyTag(String strategyTag) {
        this.strategyTag = strategyTag;
    }

    public String getImprovementHint() {
        return improvementHint;
    }

    public void setImprovementHint(String improvementHint) {
        this.improvementHint = improvementHint;
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

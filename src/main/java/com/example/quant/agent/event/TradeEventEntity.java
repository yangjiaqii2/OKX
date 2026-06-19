package com.example.quant.agent.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "trade_event")
public class TradeEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;

    @Column(name = "inst_id", length = 64)
    private String instId;

    @Column(name = "pending_order_id", length = 36)
    private String pendingOrderId;

    @Column(name = "auto_trade_record_id")
    private Long autoTradeRecordId;

    @Column(name = "trade_order_id")
    private Long tradeOrderId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "old_status", length = 64)
    private String oldStatus;

    @Column(name = "new_status", length = 64)
    private String newStatus;

    @Column(name = "reason_code", length = 128)
    private String reasonCode;

    @Column(name = "reason_message", columnDefinition = "TEXT")
    private String reasonMessage;

    @Column(name = "okx_ord_id", length = 128)
    private String okxOrdId;

    @Column(name = "cl_ord_id", length = 64)
    private String clOrdId;

    @Column(name = "algo_id", length = 128)
    private String algoId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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

    public Long getTradeOrderId() {
        return tradeOrderId;
    }

    public void setTradeOrderId(Long tradeOrderId) {
        this.tradeOrderId = tradeOrderId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getOldStatus() {
        return oldStatus;
    }

    public void setOldStatus(String oldStatus) {
        this.oldStatus = oldStatus;
    }

    public String getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(String newStatus) {
        this.newStatus = newStatus;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getReasonMessage() {
        return reasonMessage;
    }

    public void setReasonMessage(String reasonMessage) {
        this.reasonMessage = reasonMessage;
    }

    public String getOkxOrdId() {
        return okxOrdId;
    }

    public void setOkxOrdId(String okxOrdId) {
        this.okxOrdId = okxOrdId;
    }

    public String getClOrdId() {
        return clOrdId;
    }

    public void setClOrdId(String clOrdId) {
        this.clOrdId = clOrdId;
    }

    public String getAlgoId() {
        return algoId;
    }

    public void setAlgoId(String algoId) {
        this.algoId = algoId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

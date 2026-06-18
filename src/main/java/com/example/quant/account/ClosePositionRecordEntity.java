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

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
@Table(name = "trade_order")
public class TradeOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_plan_id", length = 36)
    private String tradePlanId;

    @Column(name = "pending_order_id", length = 36)
    private String pendingOrderId;

    @Column(name = "order_role", nullable = false, length = 32)
    private String orderRole;

    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;

    @Column(name = "side", nullable = false, length = 16)
    private String side;

    @Column(name = "pos_side", length = 16)
    private String posSide;

    @Column(name = "ord_type", nullable = false, length = 32)
    private String ordType;

    @Column(name = "td_mode", nullable = false, length = 16)
    private String tdMode;

    @Column(name = "size_value", precision = 30, scale = 12)
    private BigDecimal size;

    @Column(name = "price", precision = 30, scale = 12)
    private BigDecimal price;

    @Column(name = "reduce_only", nullable = false)
    private boolean reduceOnly;

    @Column(name = "cl_ord_id", length = 64)
    private String clOrdId;

    @Column(name = "okx_ord_id", length = 128)
    private String okxOrdId;

    @Column(name = "okx_state", length = 64)
    private String okxState;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void setTradePlanId(String tradePlanId) {
        this.tradePlanId = tradePlanId;
    }

    public String getTradePlanId() {
        return tradePlanId;
    }

    public void setPendingOrderId(String pendingOrderId) {
        this.pendingOrderId = pendingOrderId;
    }

    public String getPendingOrderId() {
        return pendingOrderId;
    }

    public void setOrderRole(String orderRole) {
        this.orderRole = orderRole;
    }

    public String getOrderRole() {
        return orderRole;
    }

    public void setInstId(String instId) {
        this.instId = instId;
    }

    public String getInstId() {
        return instId;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public String getSide() {
        return side;
    }

    public void setPosSide(String posSide) {
        this.posSide = posSide;
    }

    public String getPosSide() {
        return posSide;
    }

    public void setOrdType(String ordType) {
        this.ordType = ordType;
    }

    public String getOrdType() {
        return ordType;
    }

    public void setTdMode(String tdMode) {
        this.tdMode = tdMode;
    }

    public String getTdMode() {
        return tdMode;
    }

    public void setSize(BigDecimal size) {
        this.size = size;
    }

    public BigDecimal getSize() {
        return size;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setReduceOnly(boolean reduceOnly) {
        this.reduceOnly = reduceOnly;
    }

    public boolean isReduceOnly() {
        return reduceOnly;
    }

    public void setClOrdId(String clOrdId) {
        this.clOrdId = clOrdId;
    }

    public String getClOrdId() {
        return clOrdId;
    }

    public void setOkxOrdId(String okxOrdId) {
        this.okxOrdId = okxOrdId;
    }

    public String getOkxOrdId() {
        return okxOrdId;
    }

    public void setOkxState(String okxState) {
        this.okxState = okxState;
    }

    public String getOkxState() {
        return okxState;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatus() {
        return status;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

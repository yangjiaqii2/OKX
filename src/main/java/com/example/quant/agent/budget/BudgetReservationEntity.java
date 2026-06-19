package com.example.quant.agent.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "auto_trade_budget_reservation")
public class BudgetReservationEntity {
    @Id
    @Column(name = "reservation_id", length = 36)
    private String reservationId;

    @Column(name = "plan_id", length = 36)
    private String planId;

    @Column(name = "pending_order_id", length = 36)
    private String pendingOrderId;

    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "amount", precision = 30, scale = 12)
    private BigDecimal amount;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "reason", length = 128)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getPendingOrderId() {
        return pendingOrderId;
    }

    public void setPendingOrderId(String pendingOrderId) {
        this.pendingOrderId = pendingOrderId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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

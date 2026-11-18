package com.example.quant.agent.exit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Durable accounting for one canonical exit leg.  It is deliberately separate
 * from exchange-order rows because a leg may be represented by more than one
 * order after cancel/replace or a restart.
 */
@Entity
@Table(name = "exit_leg_ledger", uniqueConstraints = @UniqueConstraint(
        name = "uk_exit_leg_ledger_identity",
        columnNames = {"user_name", "pending_order_id", "exit_leg_id"}))
public class ExitLegLedgerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;
    @Column(name = "pending_order_id", nullable = false, length = 36)
    private String pendingOrderId;
    @Column(name = "trade_plan_id", length = 36)
    private String tradePlanId;
    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;
    @Column(name = "exit_leg_id", nullable = false, length = 32)
    private String exitLegId;
    @Column(name = "leg_type", nullable = false, length = 24)
    private String legType = "TAKE_PROFIT";
    @Column(name = "target_price", precision = 30, scale = 12)
    private BigDecimal targetPrice;
    @Column(name = "target_r", precision = 18, scale = 8)
    private BigDecimal targetR;
    @Column(name = "planned_quantity", nullable = false, precision = 30, scale = 12)
    private BigDecimal plannedQuantity = BigDecimal.ZERO;
    @Column(name = "filled_quantity", nullable = false, precision = 30, scale = 12)
    private BigDecimal filledQuantity = BigDecimal.ZERO;
    @Column(name = "remaining_quantity", nullable = false, precision = 30, scale = 12)
    private BigDecimal remainingQuantity = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ExitLegLedgerStatus status = ExitLegLedgerStatus.PLANNED;
    @Column(name = "current_cl_ord_id", length = 64)
    private String currentClOrdId;
    @Column(name = "current_algo_id", length = 128)
    private String currentAlgoId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public Long getVersion() { return version; }
    public String getUserName() { return userName; }
    public void setUserName(String value) { userName = value; }
    public String getPendingOrderId() { return pendingOrderId; }
    public void setPendingOrderId(String value) { pendingOrderId = value; }
    public String getTradePlanId() { return tradePlanId; }
    public void setTradePlanId(String value) { tradePlanId = value; }
    public String getInstId() { return instId; }
    public void setInstId(String value) { instId = value; }
    public String getExitLegId() { return exitLegId; }
    public void setExitLegId(String value) { exitLegId = value; }
    public String getLegType() { return legType; }
    public void setLegType(String value) { legType = value; }
    public BigDecimal getTargetPrice() { return targetPrice; }
    public void setTargetPrice(BigDecimal value) { targetPrice = value; }
    public BigDecimal getTargetR() { return targetR; }
    public void setTargetR(BigDecimal value) { targetR = value; }
    public BigDecimal getPlannedQuantity() { return plannedQuantity; }
    public void setPlannedQuantity(BigDecimal value) { plannedQuantity = nonNegative(value); }
    public BigDecimal getFilledQuantity() { return filledQuantity; }
    public void setFilledQuantity(BigDecimal value) { filledQuantity = nonNegative(value); }
    public BigDecimal getRemainingQuantity() { return remainingQuantity; }
    public void setRemainingQuantity(BigDecimal value) { remainingQuantity = nonNegative(value); }
    public ExitLegLedgerStatus getStatus() { return status; }
    public void setStatus(ExitLegLedgerStatus value) { status = value == null ? ExitLegLedgerStatus.RECONCILING : value; }
    public String getCurrentClOrdId() { return currentClOrdId; }
    public void setCurrentClOrdId(String value) { currentClOrdId = value; }
    public String getCurrentAlgoId() { return currentAlgoId; }
    public void setCurrentAlgoId(String value) { currentAlgoId = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("exit leg quantity must be non-negative");
        }
        return value;
    }
}

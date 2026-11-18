package com.example.quant.agent.exit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Durable row behind {@code position_exit_state} (V36).
 *
 * <p>Everything the profit-protection engine needs after a restart lives here:
 * the immutable entry facts, the running MFE/MAE, the current stage, and the
 * protective stop that is confirmed on the exchange.
 */
@Entity
@Table(name = "position_exit_state")
public class PositionExitStateEntity {

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

    @Column(name = "pos_side", length = 16)
    private String posSide;

    @Column(name = "entry_price", nullable = false, precision = 30, scale = 12)
    private BigDecimal entryPrice;

    @Column(name = "initial_stop_loss", nullable = false, precision = 30, scale = 12)
    private BigDecimal initialStopLoss;

    @Column(name = "initial_risk", nullable = false, precision = 30, scale = 12)
    private BigDecimal initialRisk;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "highest_since_entry", precision = 30, scale = 12)
    private BigDecimal highestSinceEntry;

    @Column(name = "lowest_since_entry", precision = 30, scale = 12)
    private BigDecimal lowestSinceEntry;

    @Column(name = "mfe_price", precision = 30, scale = 12)
    private BigDecimal mfePrice;

    @Column(name = "mae_price", precision = 30, scale = 12)
    private BigDecimal maePrice;

    @Column(name = "mfe_pct", precision = 18, scale = 8)
    private BigDecimal mfePct;

    @Column(name = "mae_pct", precision = 18, scale = 8)
    private BigDecimal maePct;

    @Column(name = "mfe_r", precision = 18, scale = 8)
    private BigDecimal mfeR;

    @Column(name = "mae_r", precision = 18, scale = 8)
    private BigDecimal maeR;

    @Column(name = "mfe_at")
    private Instant mfeAt;

    @Column(name = "mae_at")
    private Instant maeAt;

    @Column(name = "exit_state", nullable = false, length = 32)
    private String exitState;

    @Column(name = "observed_stage", length = 32)
    private String observedStage;

    @Column(name = "confirmed_protection_stage", length = 32)
    private String confirmedProtectionStage;

    @Column(name = "stop_amend_state", nullable = false, length = 32)
    private String stopAmendState = "NONE";

    @Column(name = "exit_state_since")
    private Instant exitStateSince;

    @Column(name = "applied_protective_stop", precision = 30, scale = 12)
    private BigDecimal appliedProtectiveStop;

    @Column(name = "desired_protective_stop", precision = 30, scale = 12)
    private BigDecimal desiredProtectiveStop;

    @Column(name = "stop_algo_cl_ord_id", length = 32)
    private String stopAlgoClOrdId;

    @Column(name = "stop_algo_id", length = 128)
    private String stopAlgoId;

    @Column(name = "stop_applied_at")
    private Instant stopAppliedAt;

    @Column(name = "stop_amend_count", nullable = false)
    private int stopAmendCount;

    @Column(name = "stop_amend_window_start")
    private Instant stopAmendWindowStart;

    @Column(name = "pending_since")
    private Instant pendingSince;

    @Column(name = "pending_req_id", length = 32)
    private String pendingReqId;

    @Column(name = "last_mark_price", precision = 30, scale = 12)
    private BigDecimal lastMarkPrice;

    @Column(name = "last_price_at")
    private Instant lastPriceAt;

    @Column(name = "exit_reason", length = 32)
    private String exitReason;

    @Column(name = "realized_r", precision = 18, scale = 8)
    private BigDecimal realizedR;

    @Column(name = "mfe_r_final", precision = 18, scale = 8)
    private BigDecimal mfeRFinal;

    @Column(name = "giveback_r", precision = 18, scale = 8)
    private BigDecimal givebackR;

    @Column(name = "giveback_ratio", precision = 18, scale = 8)
    private BigDecimal givebackRatio;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPendingOrderId() {
        return pendingOrderId;
    }

    public void setPendingOrderId(String pendingOrderId) {
        this.pendingOrderId = pendingOrderId;
    }

    public String getTradePlanId() {
        return tradePlanId;
    }

    public void setTradePlanId(String tradePlanId) {
        this.tradePlanId = tradePlanId;
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

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public void setEntryPrice(BigDecimal entryPrice) {
        this.entryPrice = entryPrice;
    }

    public BigDecimal getInitialStopLoss() {
        return initialStopLoss;
    }

    public void setInitialStopLoss(BigDecimal initialStopLoss) {
        this.initialStopLoss = initialStopLoss;
    }

    public BigDecimal getInitialRisk() {
        return initialRisk;
    }

    public void setInitialRisk(BigDecimal initialRisk) {
        this.initialRisk = initialRisk;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public BigDecimal getHighestSinceEntry() {
        return highestSinceEntry;
    }

    public void setHighestSinceEntry(BigDecimal highestSinceEntry) {
        this.highestSinceEntry = highestSinceEntry;
    }

    public BigDecimal getLowestSinceEntry() {
        return lowestSinceEntry;
    }

    public void setLowestSinceEntry(BigDecimal lowestSinceEntry) {
        this.lowestSinceEntry = lowestSinceEntry;
    }

    public BigDecimal getMfePrice() {
        return mfePrice;
    }

    public void setMfePrice(BigDecimal mfePrice) {
        this.mfePrice = mfePrice;
    }

    public BigDecimal getMaePrice() {
        return maePrice;
    }

    public void setMaePrice(BigDecimal maePrice) {
        this.maePrice = maePrice;
    }

    public BigDecimal getMfePct() {
        return mfePct;
    }

    public void setMfePct(BigDecimal mfePct) {
        this.mfePct = mfePct;
    }

    public BigDecimal getMaePct() {
        return maePct;
    }

    public void setMaePct(BigDecimal maePct) {
        this.maePct = maePct;
    }

    public BigDecimal getMfeR() {
        return mfeR;
    }

    public void setMfeR(BigDecimal mfeR) {
        this.mfeR = mfeR;
    }

    public BigDecimal getMaeR() {
        return maeR;
    }

    public void setMaeR(BigDecimal maeR) {
        this.maeR = maeR;
    }

    public Instant getMfeAt() {
        return mfeAt;
    }

    public void setMfeAt(Instant mfeAt) {
        this.mfeAt = mfeAt;
    }

    public Instant getMaeAt() {
        return maeAt;
    }

    public void setMaeAt(Instant maeAt) {
        this.maeAt = maeAt;
    }

    public String getExitState() {
        return exitState;
    }

    public void setExitState(String exitState) {
        this.exitState = exitState;
    }

    public String getObservedStage() { return observedStage; }

    public void setObservedStage(String observedStage) { this.observedStage = observedStage; }

    public String getConfirmedProtectionStage() { return confirmedProtectionStage; }

    public void setConfirmedProtectionStage(String confirmedProtectionStage) {
        this.confirmedProtectionStage = confirmedProtectionStage;
    }

    public String getStopAmendState() { return stopAmendState; }

    public void setStopAmendState(String stopAmendState) {
        this.stopAmendState = stopAmendState == null ? "NONE" : stopAmendState;
    }

    public Instant getExitStateSince() {
        return exitStateSince;
    }

    public void setExitStateSince(Instant exitStateSince) {
        this.exitStateSince = exitStateSince;
    }

    public BigDecimal getAppliedProtectiveStop() {
        return appliedProtectiveStop;
    }

    public void setAppliedProtectiveStop(BigDecimal appliedProtectiveStop) {
        this.appliedProtectiveStop = appliedProtectiveStop;
    }

    public BigDecimal getDesiredProtectiveStop() {
        return desiredProtectiveStop;
    }

    public void setDesiredProtectiveStop(BigDecimal desiredProtectiveStop) {
        this.desiredProtectiveStop = desiredProtectiveStop;
    }

    public String getStopAlgoClOrdId() {
        return stopAlgoClOrdId;
    }

    public void setStopAlgoClOrdId(String stopAlgoClOrdId) {
        this.stopAlgoClOrdId = stopAlgoClOrdId;
    }

    public String getStopAlgoId() {
        return stopAlgoId;
    }

    public void setStopAlgoId(String stopAlgoId) {
        this.stopAlgoId = stopAlgoId;
    }

    public Instant getStopAppliedAt() {
        return stopAppliedAt;
    }

    public void setStopAppliedAt(Instant stopAppliedAt) {
        this.stopAppliedAt = stopAppliedAt;
    }

    public int getStopAmendCount() {
        return stopAmendCount;
    }

    public void setStopAmendCount(int stopAmendCount) {
        this.stopAmendCount = stopAmendCount;
    }

    public Instant getStopAmendWindowStart() {
        return stopAmendWindowStart;
    }

    public void setStopAmendWindowStart(Instant stopAmendWindowStart) {
        this.stopAmendWindowStart = stopAmendWindowStart;
    }

    public Instant getPendingSince() {
        return pendingSince;
    }

    public void setPendingSince(Instant pendingSince) {
        this.pendingSince = pendingSince;
    }

    public String getPendingReqId() {
        return pendingReqId;
    }

    public void setPendingReqId(String pendingReqId) {
        this.pendingReqId = pendingReqId;
    }

    public BigDecimal getLastMarkPrice() {
        return lastMarkPrice;
    }

    public void setLastMarkPrice(BigDecimal lastMarkPrice) {
        this.lastMarkPrice = lastMarkPrice;
    }

    public Instant getLastPriceAt() {
        return lastPriceAt;
    }

    public void setLastPriceAt(Instant lastPriceAt) {
        this.lastPriceAt = lastPriceAt;
    }

    public String getExitReason() {
        return exitReason;
    }

    public void setExitReason(String exitReason) {
        this.exitReason = exitReason;
    }

    public BigDecimal getRealizedR() {
        return realizedR;
    }

    public void setRealizedR(BigDecimal realizedR) {
        this.realizedR = realizedR;
    }

    public BigDecimal getMfeRFinal() {
        return mfeRFinal;
    }

    public void setMfeRFinal(BigDecimal mfeRFinal) {
        this.mfeRFinal = mfeRFinal;
    }

    public BigDecimal getGivebackR() {
        return givebackR;
    }

    public void setGivebackR(BigDecimal givebackR) {
        this.givebackR = givebackR;
    }

    public BigDecimal getGivebackRatio() {
        return givebackRatio;
    }

    public void setGivebackRatio(BigDecimal givebackRatio) {
        this.givebackRatio = givebackRatio;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
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

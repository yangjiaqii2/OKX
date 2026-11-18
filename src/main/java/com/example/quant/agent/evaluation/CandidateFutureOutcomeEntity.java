package com.example.quant.agent.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "candidate_future_outcome")
public class CandidateFutureOutcomeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Column(name = "horizon_minutes", nullable = false)
    private Integer horizonMinutes;

    @Column(name = "maturity_at", nullable = false)
    private Instant maturityAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "future_timestamp")
    private Instant futureTimestamp;

    @Column(name = "future_price", precision = 18, scale = 12)
    private BigDecimal futurePrice;

    @Column(name = "raw_return", precision = 18, scale = 12)
    private BigDecimal rawReturn;

    @Column(name = "directional_return", precision = 18, scale = 12)
    private BigDecimal directionalReturn;

    @Column(name = "max_upside", precision = 18, scale = 12)
    private BigDecimal maxUpside;

    @Column(name = "max_downside", precision = 18, scale = 12)
    private BigDecimal maxDownside;

    @Column(name = "directional_mfe", precision = 18, scale = 12)
    private BigDecimal directionalMfe;

    @Column(name = "directional_mae", precision = 18, scale = 12)
    private BigDecimal directionalMae;

    @Column(name = "directional_max_drawdown", precision = 18, scale = 12)
    private BigDecimal directionalMaxDrawdown;

    @Column(name = "first_barrier_touch", length = 24)
    private String firstBarrierTouch;

    @Column(name = "error_reason", columnDefinition = "TEXT")
    private String errorReason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "observed_at")
    private Instant observedAt;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    public Long getId() { return id; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long value) { snapshotId = value; }
    public Integer getHorizonMinutes() { return horizonMinutes; }
    public void setHorizonMinutes(Integer value) {
        if (value != null && value != 60 && value != 240 && value != 1440) {
            throw new IllegalArgumentException("Unsupported outcome horizon: " + value);
        }
        horizonMinutes = value;
    }
    public Instant getMaturityAt() { return maturityAt; }
    public void setMaturityAt(Instant value) { maturityAt = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Instant getFutureTimestamp() { return futureTimestamp; }
    public void setFutureTimestamp(Instant value) { futureTimestamp = value; }
    public BigDecimal getFuturePrice() { return futurePrice; }
    public void setFuturePrice(BigDecimal value) { futurePrice = value; }
    public BigDecimal getRawReturn() { return rawReturn; }
    public void setRawReturn(BigDecimal value) { rawReturn = value; }
    public BigDecimal getDirectionalReturn() { return directionalReturn; }
    public void setDirectionalReturn(BigDecimal value) { directionalReturn = value; }
    public BigDecimal getMaxUpside() { return maxUpside; }
    public void setMaxUpside(BigDecimal value) { maxUpside = value; }
    public BigDecimal getMaxDownside() { return maxDownside; }
    public void setMaxDownside(BigDecimal value) { maxDownside = value; }
    public BigDecimal getDirectionalMfe() { return directionalMfe; }
    public void setDirectionalMfe(BigDecimal value) { directionalMfe = value; }
    public BigDecimal getDirectionalMae() { return directionalMae; }
    public void setDirectionalMae(BigDecimal value) { directionalMae = value; }
    public BigDecimal getDirectionalMaxDrawdown() { return directionalMaxDrawdown; }
    public void setDirectionalMaxDrawdown(BigDecimal value) { directionalMaxDrawdown = value; }
    public String getFirstBarrierTouch() { return firstBarrierTouch; }
    public void setFirstBarrierTouch(String value) { firstBarrierTouch = value; }
    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String value) { errorReason = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer value) { retryCount = value; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant value) { lastAttemptAt = value; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant value) { nextRetryAt = value; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant value) { observedAt = value; }
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant value) { windowStart = value; }
    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant value) { windowEnd = value; }
}

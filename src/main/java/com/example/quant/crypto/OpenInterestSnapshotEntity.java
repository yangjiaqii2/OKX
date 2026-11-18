package com.example.quant.crypto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "open_interest_snapshot")
public class OpenInterestSnapshotEntity {
    @Id
    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;

    @Column(name = "open_interest", nullable = false, precision = 30, scale = 12)
    private BigDecimal openInterest;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    public String getInstId() {
        return instId;
    }

    public void setInstId(String instId) {
        this.instId = instId;
    }

    public BigDecimal getOpenInterest() {
        return openInterest;
    }

    public void setOpenInterest(BigDecimal openInterest) {
        this.openInterest = openInterest;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }
}

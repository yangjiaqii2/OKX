package com.example.quant.agent.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_scan_run")
public class AgentScanRunEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "market_risk", length = 32)
    private String marketRisk;

    @Column(name = "btc_trend", length = 32)
    private String btcTrend;

    @Column(name = "eth_trend", length = 32)
    private String ethTrend;

    @Column(name = "candidate_count")
    private int candidateCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getMarketRisk() {
        return marketRisk;
    }

    public void setMarketRisk(String marketRisk) {
        this.marketRisk = marketRisk;
    }

    public String getBtcTrend() {
        return btcTrend;
    }

    public void setBtcTrend(String btcTrend) {
        this.btcTrend = btcTrend;
    }

    public String getEthTrend() {
        return ethTrend;
    }

    public void setEthTrend(String ethTrend) {
        this.ethTrend = ethTrend;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(int candidateCount) {
        this.candidateCount = candidateCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

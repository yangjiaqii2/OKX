package com.example.quant.agent.score;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_contract_candidate_score")
public class AgentContractCandidateScoreEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_run_id")
    private Long scanRunId;

    @Column(name = "inst_id", nullable = false, length = 64)
    private String instId;

    @Column(name = "direction", length = 32)
    private String direction;

    @Column(name = "total_score", nullable = false)
    private int totalScore;

    @Column(name = "trend_score", nullable = false)
    private int trendScore;

    @Column(name = "volume_score", nullable = false)
    private int volumeScore;

    @Column(name = "volatility_score", nullable = false)
    private int volatilityScore;

    @Column(name = "liquidity_score", nullable = false)
    private int liquidityScore;

    @Column(name = "oi_funding_score", nullable = false)
    private int oiFundingScore;

    @Column(name = "market_env_score", nullable = false)
    private int marketEnvScore;

    @Column(name = "news_risk_score", nullable = false)
    private int newsRiskScore;

    @Column(name = "allow_trade", nullable = false)
    private boolean allowTrade;

    @Column(name = "deny_reason", columnDefinition = "TEXT")
    private String denyReason;

    @Column(name = "reason_json", columnDefinition = "TEXT")
    private String reasonJson;

    @Column(name = "risk_json", columnDefinition = "TEXT")
    private String riskJson;

    @Column(name = "raw_market_json", columnDefinition = "TEXT")
    private String rawMarketJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public void setScanRunId(Long scanRunId) {
        this.scanRunId = scanRunId;
    }

    public void setInstId(String instId) {
        this.instId = instId;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public void setTotalScore(int totalScore) {
        this.totalScore = totalScore;
    }

    public void setTrendScore(int trendScore) {
        this.trendScore = trendScore;
    }

    public void setVolumeScore(int volumeScore) {
        this.volumeScore = volumeScore;
    }

    public void setVolatilityScore(int volatilityScore) {
        this.volatilityScore = volatilityScore;
    }

    public void setLiquidityScore(int liquidityScore) {
        this.liquidityScore = liquidityScore;
    }

    public void setOiFundingScore(int oiFundingScore) {
        this.oiFundingScore = oiFundingScore;
    }

    public void setMarketEnvScore(int marketEnvScore) {
        this.marketEnvScore = marketEnvScore;
    }

    public void setNewsRiskScore(int newsRiskScore) {
        this.newsRiskScore = newsRiskScore;
    }

    public void setAllowTrade(boolean allowTrade) {
        this.allowTrade = allowTrade;
    }

    public void setDenyReason(String denyReason) {
        this.denyReason = denyReason;
    }

    public void setReasonJson(String reasonJson) {
        this.reasonJson = reasonJson;
    }

    public void setRiskJson(String riskJson) {
        this.riskJson = riskJson;
    }

    public void setRawMarketJson(String rawMarketJson) {
        this.rawMarketJson = rawMarketJson;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

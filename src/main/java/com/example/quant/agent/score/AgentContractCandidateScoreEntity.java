package com.example.quant.agent.score;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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

    @Column(name = "analysis_score")
    private Integer analysisScore;

    @Column(name = "analysis_action", length = 32)
    private String analysisAction;

    @Column(name = "scan_action", length = 32)
    private String scanAction;

    @Column(name = "base_oi_change_missing")
    private Boolean baseOiChangeMissing;

    public Boolean getBaseOiChangeMissing() { return baseOiChangeMissing; }
    public void setBaseOiChangeMissing(Boolean value) { baseOiChangeMissing = value; }

    public Integer getAnalysisScore() { return analysisScore; }
    public void setAnalysisScore(Integer value) { analysisScore = value; }
    public String getAnalysisAction() { return analysisAction; }
    public void setAnalysisAction(String value) { analysisAction = value; }
    public String getScanAction() { return scanAction; }
    public void setScanAction(String value) { scanAction = value; }

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

    // ---- V2 Universe / Market Quality fields ----
    @Column(name = "market_cap_usd", precision = 20, scale = 4)
    private BigDecimal marketCapUsd;

    @Column(name = "fdv_usd", precision = 20, scale = 4)
    private BigDecimal fdvUsd;

    @Column(name = "circulation_ratio", precision = 8, scale = 4)
    private BigDecimal circulationRatio;

    @Column(name = "volume_mcap_ratio", precision = 8, scale = 4)
    private BigDecimal volumeMcapRatio;

    @Column(name = "market_quality_note", length = 255)
    private String marketQualityNote;

    // ---- Wave 5 immutable V2 historical snapshot fields ----
    @Column(name = "snapshot_rank")
    private Integer snapshotRank;

    @Column(name = "reference_price", precision = 18, scale = 12)
    private BigDecimal referencePrice;

    @Column(name = "snapshot_stop_loss_price", precision = 30, scale = 12)
    private BigDecimal snapshotStopLossPrice;

    @Column(name = "snapshot_take_profit_price", precision = 30, scale = 12)
    private BigDecimal snapshotTakeProfitPrice;

    @Column(name = "scoring_version", length = 32)
    private String scoringVersion;

    @Column(name = "v2_normalized_score", precision = 18, scale = 8)
    private BigDecimal v2NormalizedScore;

    @Column(name = "v2_ranking_score", precision = 18, scale = 8)
    private BigDecimal v2RankingScore;

    @Column(name = "v2_available_weight", precision = 18, scale = 8)
    private BigDecimal v2AvailableWeight;

    @Column(name = "v2_coverage_ratio", precision = 18, scale = 8)
    private BigDecimal v2CoverageRatio;

    @Column(name = "capital_flow_score", precision = 18, scale = 8)
    private BigDecimal capitalFlowScore;

    @Column(name = "derivatives_score", precision = 18, scale = 8)
    private BigDecimal derivativesScore;

    @Column(name = "relative_strength_score", precision = 18, scale = 8)
    private BigDecimal relativeStrengthScore;

    @Column(name = "market_structure_score", precision = 18, scale = 8)
    private BigDecimal marketStructureScore;

    @Column(name = "volume_quality_score", precision = 18, scale = 8)
    private BigDecimal volumeQualityScore;

    @Column(name = "breakout_score", precision = 18, scale = 8)
    private BigDecimal breakoutScore;

    @Column(name = "v2_news_score", precision = 18, scale = 8)
    private BigDecimal v2NewsScore;

    @Column(name = "oi_data_quality", length = 24)
    private String oiDataQuality;

    @Column(name = "funding_data_quality", length = 24)
    private String fundingDataQuality;

    @Column(name = "relative_strength_data_quality", length = 24)
    private String relativeStrengthDataQuality;

    @Column(name = "breakout_data_quality", length = 24)
    private String breakoutDataQuality;

    @Column(name = "snapshot_volume_24h", precision = 18, scale = 8)
    private BigDecimal snapshotVolume24h;

    @Column(name = "snapshot_open_interest", precision = 30, scale = 12)
    private BigDecimal snapshotOpenInterest;

    @Column(name = "oi_change_1h", precision = 18, scale = 8)
    private BigDecimal oiChange1h;

    @Column(name = "oi_change_4h", precision = 18, scale = 8)
    private BigDecimal oiChange4h;

    @Column(name = "oi_change_24h", precision = 18, scale = 8)
    private BigDecimal oiChange24h;

    @Column(name = "oi_direction", length = 24)
    private String oiDirection;

    @Column(name = "snapshot_funding_rate", precision = 18, scale = 12)
    private BigDecimal snapshotFundingRate;

    @Column(name = "funding_rate_24h", precision = 18, scale = 12)
    private BigDecimal fundingRate24h;

    @Column(name = "funding_acceleration", precision = 18, scale = 12)
    private BigDecimal fundingAcceleration;

    @Column(name = "funding_trend", length = 24)
    private String fundingTrend;

    @Column(name = "funding_extreme")
    private Boolean fundingExtreme;

    @Column(name = "rs_1h", precision = 18, scale = 8)
    private BigDecimal rs1h;

    @Column(name = "rs_4h", precision = 18, scale = 8)
    private BigDecimal rs4h;

    @Column(name = "rs_24h", precision = 18, scale = 8)
    private BigDecimal rs24h;

    @Column(name = "snapshot_volume_spike_ratio", precision = 18, scale = 8)
    private BigDecimal snapshotVolumeSpikeRatio;

    @Column(name = "snapshot_volume_mcap_ratio", precision = 18, scale = 8)
    private BigDecimal snapshotVolumeMcapRatio;

    @Column(name = "snapshot_spread_bps", precision = 18, scale = 8)
    private BigDecimal snapshotSpreadBps;

    @Column(name = "ath_price", precision = 18, scale = 12)
    private BigDecimal athPrice;

    @Column(name = "distance_to_ath_pct", precision = 18, scale = 8)
    private BigDecimal distanceToAthPct;

    @Column(name = "breakout_level", length = 24)
    private String breakoutLevel;

    @Column(name = "news_risk_level", length = 24)
    private String newsRiskLevel;

    @Column(name = "signal_type", length = 32)
    private String signalType;

    @Column(name = "market_structure", length = 64)
    private String marketStructure;

    @Column(name = "snapshot_market_risk_level", length = 24)
    private String snapshotMarketRiskLevel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public void setScanRunId(Long scanRunId) {
        this.scanRunId = scanRunId;
    }

    public Long getScanRunId() {
        return scanRunId;
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

    public void setMarketCapUsd(BigDecimal marketCapUsd) {
        this.marketCapUsd = marketCapUsd;
    }

    public void setFdvUsd(BigDecimal fdvUsd) {
        this.fdvUsd = fdvUsd;
    }

    public void setCirculationRatio(BigDecimal circulationRatio) {
        this.circulationRatio = circulationRatio;
    }

    public void setVolumeMcapRatio(BigDecimal volumeMcapRatio) {
        this.volumeMcapRatio = volumeMcapRatio;
    }

    public void setMarketQualityNote(String marketQualityNote) {
        this.marketQualityNote = marketQualityNote;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    public Instant getCreatedAt() { return createdAt; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInstId() { return instId; }
    public String getDirection() { return direction; }
    public Integer getSnapshotRank() { return snapshotRank; }
    public void setSnapshotRank(Integer snapshotRank) { this.snapshotRank = snapshotRank; }
    public BigDecimal getReferencePrice() { return referencePrice; }
    public void setReferencePrice(BigDecimal referencePrice) { this.referencePrice = referencePrice; }
    public BigDecimal getSnapshotStopLossPrice() { return snapshotStopLossPrice; }
    public void setSnapshotStopLossPrice(BigDecimal value) { snapshotStopLossPrice = value; }
    public BigDecimal getSnapshotTakeProfitPrice() { return snapshotTakeProfitPrice; }
    public void setSnapshotTakeProfitPrice(BigDecimal value) { snapshotTakeProfitPrice = value; }
    public String getScoringVersion() { return scoringVersion; }
    public void setScoringVersion(String scoringVersion) { this.scoringVersion = scoringVersion; }
    public BigDecimal getV2NormalizedScore() { return v2NormalizedScore; }
    public void setV2NormalizedScore(BigDecimal v2NormalizedScore) { this.v2NormalizedScore = v2NormalizedScore; }
    public BigDecimal getV2RankingScore() { return v2RankingScore; }
    public void setV2RankingScore(BigDecimal v2RankingScore) { this.v2RankingScore = v2RankingScore; }
    public BigDecimal getV2AvailableWeight() { return v2AvailableWeight; }
    public void setV2AvailableWeight(BigDecimal v2AvailableWeight) { this.v2AvailableWeight = v2AvailableWeight; }
    public BigDecimal getV2CoverageRatio() { return v2CoverageRatio; }
    public void setV2CoverageRatio(BigDecimal v2CoverageRatio) { this.v2CoverageRatio = v2CoverageRatio; }
    public BigDecimal getCapitalFlowScore() { return capitalFlowScore; }
    public void setCapitalFlowScore(BigDecimal value) { capitalFlowScore = value; }
    public BigDecimal getDerivativesScore() { return derivativesScore; }
    public void setDerivativesScore(BigDecimal value) { derivativesScore = value; }
    public BigDecimal getRelativeStrengthScore() { return relativeStrengthScore; }
    public void setRelativeStrengthScore(BigDecimal value) { relativeStrengthScore = value; }
    public BigDecimal getMarketStructureScore() { return marketStructureScore; }
    public void setMarketStructureScore(BigDecimal value) { marketStructureScore = value; }
    public BigDecimal getVolumeQualityScore() { return volumeQualityScore; }
    public void setVolumeQualityScore(BigDecimal value) { volumeQualityScore = value; }
    public BigDecimal getBreakoutScore() { return breakoutScore; }
    public void setBreakoutScore(BigDecimal value) { breakoutScore = value; }
    public BigDecimal getV2NewsScore() { return v2NewsScore; }
    public void setV2NewsScore(BigDecimal value) { v2NewsScore = value; }
    public BigDecimal getSnapshotVolume24h() { return snapshotVolume24h; }
    public void setSnapshotVolume24h(BigDecimal value) { snapshotVolume24h = value; }
    public BigDecimal getSnapshotOpenInterest() { return snapshotOpenInterest; }
    public void setSnapshotOpenInterest(BigDecimal value) { snapshotOpenInterest = value; }
    public BigDecimal getOiChange1h() { return oiChange1h; }
    public void setOiChange1h(BigDecimal value) { oiChange1h = value; }
    public BigDecimal getOiChange4h() { return oiChange4h; }
    public void setOiChange4h(BigDecimal value) { oiChange4h = value; }
    public BigDecimal getOiChange24h() { return oiChange24h; }
    public void setOiChange24h(BigDecimal value) { oiChange24h = value; }
    public BigDecimal getSnapshotFundingRate() { return snapshotFundingRate; }
    public void setSnapshotFundingRate(BigDecimal value) { snapshotFundingRate = value; }
    public BigDecimal getFundingRate24h() { return fundingRate24h; }
    public void setFundingRate24h(BigDecimal value) { fundingRate24h = value; }
    public BigDecimal getFundingAcceleration() { return fundingAcceleration; }
    public void setFundingAcceleration(BigDecimal value) { fundingAcceleration = value; }
    public BigDecimal getRs1h() { return rs1h; }
    public void setRs1h(BigDecimal value) { rs1h = value; }
    public BigDecimal getRs4h() { return rs4h; }
    public void setRs4h(BigDecimal value) { rs4h = value; }
    public BigDecimal getRs24h() { return rs24h; }
    public void setRs24h(BigDecimal value) { rs24h = value; }
    public BigDecimal getSnapshotVolumeSpikeRatio() { return snapshotVolumeSpikeRatio; }
    public void setSnapshotVolumeSpikeRatio(BigDecimal value) { snapshotVolumeSpikeRatio = value; }
    public BigDecimal getSnapshotVolumeMcapRatio() { return snapshotVolumeMcapRatio; }
    public void setSnapshotVolumeMcapRatio(BigDecimal value) { snapshotVolumeMcapRatio = value; }
    public BigDecimal getSnapshotSpreadBps() { return snapshotSpreadBps; }
    public void setSnapshotSpreadBps(BigDecimal value) { snapshotSpreadBps = value; }
    public String getOiDataQuality() { return oiDataQuality; }
    public void setOiDataQuality(String value) { oiDataQuality = value; }
    public String getFundingDataQuality() { return fundingDataQuality; }
    public void setFundingDataQuality(String value) { fundingDataQuality = value; }
    public String getRelativeStrengthDataQuality() { return relativeStrengthDataQuality; }
    public void setRelativeStrengthDataQuality(String value) { relativeStrengthDataQuality = value; }
    public String getBreakoutDataQuality() { return breakoutDataQuality; }
    public void setBreakoutDataQuality(String value) { breakoutDataQuality = value; }
    public String getOiDirection() { return oiDirection; }
    public void setOiDirection(String value) { oiDirection = value; }
    public String getFundingTrend() { return fundingTrend; }
    public void setFundingTrend(String value) { fundingTrend = value; }
    public Boolean getFundingExtreme() { return fundingExtreme; }
    public void setFundingExtreme(Boolean value) { fundingExtreme = value; }
    public BigDecimal getAthPrice() { return athPrice; }
    public void setAthPrice(BigDecimal value) { athPrice = value; }
    public BigDecimal getDistanceToAthPct() { return distanceToAthPct; }
    public void setDistanceToAthPct(BigDecimal value) { distanceToAthPct = value; }
    public String getBreakoutLevel() { return breakoutLevel; }
    public void setBreakoutLevel(String value) { breakoutLevel = value; }
    public String getNewsRiskLevel() { return newsRiskLevel; }
    public void setNewsRiskLevel(String value) { newsRiskLevel = value; }
    public String getSignalType() { return signalType; }
    public void setSignalType(String value) { signalType = value; }
    public String getMarketStructure() { return marketStructure; }
    public void setMarketStructure(String value) { marketStructure = value; }
    public String getSnapshotMarketRiskLevel() { return snapshotMarketRiskLevel; }
    public void setSnapshotMarketRiskLevel(String value) { snapshotMarketRiskLevel = value; }
}

package com.example.quant.agent.score;

import com.example.quant.agent.gate.ContractTradeGate;
import com.example.quant.agent.audit.AgentAuditLogger;
import com.example.quant.agent.evaluation.CandidateFutureOutcomeEntity;
import com.example.quant.agent.evaluation.CandidateFutureOutcomeRepository;
import com.example.quant.agent.scan.AgentScanRunEntity;
import com.example.quant.agent.scan.AgentScanRunRepository;
import com.example.quant.agent.scan.ScanRunReport;
import com.example.quant.agent.scan.ScanRunStatus;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.provider.dto.AthBreakoutSnapshot;
import com.example.quant.crypto.provider.dto.FundingHistorySnapshot;
import com.example.quant.crypto.provider.dto.OIDirection;
import com.example.quant.crypto.provider.dto.OpenInterestHistorySnapshot;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import com.example.quant.crypto.selection.V2ScoreDimension;
import com.example.quant.crypto.selection.V2SelectionScoreCalculator;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractCandidateScoreRecorder {
    public static final String V2_SCORING_VERSION = "V2_WAVE_4_5";
    private static final Logger log = LoggerFactory.getLogger(ContractCandidateScoreRecorder.class);

    private final AgentProperties agentProperties;
    private final AgentScanRunRepository scanRunRepository;
    private final AgentContractCandidateScoreRepository scoreRepository;
    private final CandidateFutureOutcomeRepository outcomeRepository;
    private final AgentAuditLogger auditLogger;
    private final ObjectMapper objectMapper;
    private final V2SelectionScoreCalculator v2Calculator = new V2SelectionScoreCalculator();

    public ContractCandidateScoreRecorder(
            AgentProperties agentProperties,
            AgentScanRunRepository scanRunRepository,
            AgentContractCandidateScoreRepository scoreRepository,
            AgentAuditLogger auditLogger,
            ObjectMapper objectMapper
    ) {
        this(agentProperties, scanRunRepository, scoreRepository, null, auditLogger, objectMapper);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ContractCandidateScoreRecorder(
            AgentProperties agentProperties,
            AgentScanRunRepository scanRunRepository,
            AgentContractCandidateScoreRepository scoreRepository,
            CandidateFutureOutcomeRepository outcomeRepository,
            AgentAuditLogger auditLogger,
            ObjectMapper objectMapper
    ) {
        this.agentProperties = agentProperties;
        this.scanRunRepository = scanRunRepository;
        this.scoreRepository = scoreRepository;
        this.outcomeRepository = outcomeRepository;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recordLiveScan(List<ContractCandidate> candidates, Instant startedAt, Instant finishedAt) {
        recordLiveScan(candidates, startedAt, finishedAt, false);
    }

    @Transactional
    public void recordLiveScan(List<ContractCandidate> candidates, Instant startedAt, Instant finishedAt, boolean v2) {
        recordLiveScan(candidates, startedAt, finishedAt, v2, Map.of());
    }

    @Transactional
    public void recordLiveScan(List<ContractCandidate> candidates, Instant startedAt, Instant finishedAt,
                               boolean v2, Map<String, V2SelectionScoreResult> v2Scores) {
        recordScanOutcome(null, candidates, startedAt, finishedAt, v2, v2Scores, ScanRunStatus.COMPLETED);
    }

    @Transactional
    public void recordScanOutcome(ScanRunReport report, List<ContractCandidate> candidates,
                                Instant startedAt, Instant finishedAt, boolean v2,
                                Map<String, V2SelectionScoreResult> v2Scores) {
        recordScanOutcome(report, candidates, startedAt, finishedAt, v2, v2Scores, ScanRunStatus.UNKNOWN);
    }

    private void recordScanOutcome(ScanRunReport report, List<ContractCandidate> candidates,
                                   Instant startedAt, Instant finishedAt, boolean v2,
                                   Map<String, V2SelectionScoreResult> v2Scores,
                                   ScanRunStatus missingReportStatus) {
        try {
            ScanRunStatus status = report == null ? missingReportStatus : report.status();
            List<ContractCandidate> safeCandidates = candidates == null ? List.of() : candidates;
            AgentScanRunEntity scanRun = new AgentScanRunEntity();
            scanRun.setStatus(status == ScanRunStatus.COMPLETED ? "SUCCESS" : status.name());
            scanRun.setStartedAt(startedAt);
            scanRun.setFinishedAt(finishedAt);
            if (status != ScanRunStatus.COMPLETED) {
                scanRun.setCandidateCount(0);
                scanRun.setMarketRisk("UNKNOWN");
                scanRun.setBtcTrend("UNKNOWN");
                scanRun.setEthTrend("UNKNOWN");
                scanRun.setMessage(buildIncompleteMessage(report));
                scanRun.setCreatedAt(finishedAt);
                scanRunRepository.save(scanRun);
                auditLogger.warn(
                        "CONTRACT_SCAN_INCOMPLETE",
                        "agent_scan_run",
                        report == null ? null : report.scanId(),
                        scanRun.getMessage(),
                        null
                );
                return;
            }
            int persistedCandidateCount = (int) safeCandidates.stream()
                    .filter(java.util.Objects::nonNull).count();
            scanRun.setCandidateCount(persistedCandidateCount);
            safeCandidates.stream().filter(java.util.Objects::nonNull).findFirst().ifPresentOrElse(candidate -> {
                scanRun.setMarketRisk(String.valueOf(candidate.marketRiskLevel()));
                scanRun.setBtcTrend(String.valueOf(candidate.btcTrend()));
                scanRun.setEthTrend(String.valueOf(candidate.ethTrend()));
            }, () -> {
                scanRun.setMarketRisk("UNKNOWN");
                scanRun.setBtcTrend("UNKNOWN");
                scanRun.setEthTrend("UNKNOWN");
            });
            scanRun.setMessage(buildCompletedMessage(report, persistedCandidateCount));
            scanRun.setCreatedAt(finishedAt);
            AgentScanRunEntity saved = scanRunRepository.save(scanRun);

            List<AgentContractCandidateScoreEntity> rows = new ArrayList<>();
            for (int index = 0; index < safeCandidates.size(); index++) {
                ContractCandidate candidate = safeCandidates.get(index);
                if (candidate != null) {
                    rows.add(scoreRow(saved.getId(), candidate, finishedAt, v2, index + 1,
                            v2Scores == null ? null : v2Scores.get(candidate.instId())));
                }
            }
            List<AgentContractCandidateScoreEntity> persistedRows = scoreRepository.saveAll(rows);
            if (v2 && outcomeRepository != null) {
                List<AgentContractCandidateScoreEntity> actualRows = persistedRows == null || persistedRows.isEmpty()
                        ? rows : persistedRows;
                outcomeRepository.saveAll(pendingOutcomes(actualRows, finishedAt));
            }

            auditLogger.info(
                    "CONTRACT_SCAN_RECORDED",
                    "agent_scan_run",
                    String.valueOf(saved.getId()),
                    "Recorded live OKX contract score run",
                    List.copyOf(safeCandidates.stream().filter(java.util.Objects::nonNull)
                            .map(ContractCandidate::instId).toList())
            );
        } catch (RuntimeException ex) {
            log.warn("Failed to record live contract scan: {}", ex.getMessage());
            auditLogger.warn("CONTRACT_SCAN_RECORD_FAILED", "agent_scan_run", null, ex.getMessage(), null);
        }
    }

    private static String buildCompletedMessage(ScanRunReport report, int candidateCount) {
        if (report == null) {
            return "Live OKX contract candidates scored, count=" + candidateCount;
        }
        return "scanId=" + report.scanId()
                + " result=COMPLETED"
                + " deepStarted=" + report.deepAnalysisStarted()
                + " deepCompleted=" + report.deepAnalysisCompleted()
                + " skipped=" + report.skippedByDeadline()
                + " providerTimeouts=" + report.providerTimeoutCount()
                + " count=" + candidateCount;
    }

    private static String buildIncompleteMessage(ScanRunReport report) {
        if (report == null) {
            return "scan incomplete without report";
        }
        return "scanId=" + report.scanId()
                + " result=" + report.status()
                + " deepStarted=" + report.deepAnalysisStarted()
                + " deepCompleted=" + report.deepAnalysisCompleted()
                + " skipped=" + report.skippedByDeadline()
                + " providerTimeouts=" + report.providerTimeoutCount();
    }

    private AgentContractCandidateScoreEntity scoreRow(Long scanRunId, ContractCandidate candidate,
                                                        Instant createdAt, boolean v2, int rank,
                                                        V2SelectionScoreResult v2Result) {
        ContractFactorScore factor = candidate.factorScore();
        TradeGate gate = tradeGate(candidate);
        AgentContractCandidateScoreEntity row = new AgentContractCandidateScoreEntity();
        row.setScanRunId(scanRunId);
        row.setInstId(candidate.instId());
        row.setDirection(String.valueOf(candidate.trendDirection()));
        row.setTotalScore(candidate.score());
        row.setScanAction(candidate.action());
        row.setBaseOiChangeMissing(candidate.openInterestChange() == null);
        if (candidate.klineAnalysis() != null) {
            row.setAnalysisScore(candidate.klineAnalysis().analysisScore());
            row.setAnalysisAction(candidate.klineAnalysis().analysisAction());
        }
        row.setTrendScore(factor.trendScore());
        row.setVolumeScore(factor.volumeScore());
        row.setVolatilityScore(factor.volatilityScore());
        row.setLiquidityScore(factor.liquidityScore());
        row.setOiFundingScore(factor.oiFundingScore());
        row.setMarketEnvScore(factor.marketEnvScore());
        row.setNewsRiskScore(factor.newsRiskScore());
        row.setAllowTrade(gate.allowTrade());
        row.setDenyReason(gate.denyReason());
        row.setReasonJson(json(candidate.candidateReasonList()));
        row.setRiskJson(json(candidate.riskTagList()));
        row.setRawMarketJson(json(rawMarket(candidate)));
        // V2: Universe / Market Quality snapshot
        row.setMarketCapUsd(candidate.marketCapUsd());
        row.setFdvUsd(candidate.fdvUsd());
        row.setCirculationRatio(candidate.circulationRatio());
        row.setVolumeMcapRatio(candidate.volumeMcapRatio());
        row.setMarketQualityNote(candidate.marketQualityNote());
        if (v2) {
            fillV2Snapshot(row, candidate, rank, v2Result);
        }
        row.setCreatedAt(createdAt);
        return row;
    }

    private void fillV2Snapshot(AgentContractCandidateScoreEntity row, ContractCandidate candidate, int rank,
                                V2SelectionScoreResult suppliedResult) {
        V2SelectionScoreResult result = suppliedResult == null ? v2Calculator.calculate(candidate) : suppliedResult;
        row.setSnapshotRank(rank);
        row.setReferencePrice(candidate.lastPrice());
        row.setSnapshotStopLossPrice(candidate.stopLossPrice());
        row.setSnapshotTakeProfitPrice(candidate.takeProfitPrice());
        row.setScoringVersion(V2_SCORING_VERSION);
        row.setV2NormalizedScore(result.normalizedScore());
        row.setV2RankingScore(result.rankingScore());
        row.setV2AvailableWeight(result.availableWeight());
        row.setV2CoverageRatio(result.coverageRatio());
        row.setCapitalFlowScore(result.dimensionScores().get(V2ScoreDimension.CAPITAL_FLOW));
        row.setDerivativesScore(result.dimensionScores().get(V2ScoreDimension.DERIVATIVES));
        row.setRelativeStrengthScore(result.dimensionScores().get(V2ScoreDimension.RELATIVE_STRENGTH));
        row.setMarketStructureScore(result.dimensionScores().get(V2ScoreDimension.MARKET_STRUCTURE));
        row.setVolumeQualityScore(result.dimensionScores().get(V2ScoreDimension.VOLUME_QUALITY));
        row.setBreakoutScore(result.dimensionScores().get(V2ScoreDimension.BREAKOUT));
        row.setV2NewsScore(result.dimensionScores().get(V2ScoreDimension.NEWS));
        row.setSnapshotVolume24h(candidate.volume24h());
        row.setSnapshotOpenInterest(candidate.openInterest());
        row.setSnapshotVolumeSpikeRatio(candidate.volumeSpikeRatio());
        row.setSnapshotVolumeMcapRatio(candidate.volumeMcapRatio());
        row.setSnapshotSpreadBps(candidate.spreadBps());
        row.setSignalType(candidate.signalType() == null ? null : candidate.signalType().name());
        row.setMarketStructure(candidate.klineAnalysis() == null ? null : candidate.klineAnalysis().structure());
        row.setSnapshotMarketRiskLevel(candidate.marketRiskLevel() == null ? null : candidate.marketRiskLevel().name());
        OpenInterestHistorySnapshot oi = candidate.openInterestHistory();
        if (oi != null) {
            row.setOiDataQuality("AVAILABLE");
            row.setOiChange1h(oi.oiChange1h());
            row.setOiChange4h(oi.oiChange4h());
            row.setOiChange24h(oi.oiChange24h());
            if (oi.oiChange24h() != null) {
                row.setOiDirection(OIDirection.classify(candidate.changePercent24h(), oi.oiChange24h()).name());
            }
        }
        FundingHistorySnapshot funding = candidate.fundingHistory();
        if (funding != null) {
            row.setFundingDataQuality(funding.dataQuality().name());
            row.setSnapshotFundingRate(funding.currentRate());
            row.setFundingRate24h(funding.rate24h());
            row.setFundingAcceleration(BigDecimal.valueOf(funding.slope()));
            row.setFundingTrend(funding.trend().name());
            row.setFundingExtreme(funding.extreme());
        }
        RelativeStrengthSnapshot relativeStrength = candidate.relativeStrength();
        if (relativeStrength != null) {
            row.setRelativeStrengthDataQuality(relativeStrength.dataQuality().name());
            row.setRs1h(relativeStrength.rs1h());
            row.setRs4h(relativeStrength.rs4h());
            row.setRs24h(relativeStrength.rs24h());
        }
        AthBreakoutSnapshot breakout = candidate.athBreakout();
        if (breakout != null) {
            row.setBreakoutDataQuality(breakout.dataQuality().name());
            row.setAthPrice(breakout.athPrice());
            row.setDistanceToAthPct(breakout.distanceToAthPct());
            row.setBreakoutLevel(breakout.breakoutLevel());
        }
        if (candidate.newsAnalysis() != null) {
            row.setNewsRiskLevel(candidate.newsAnalysis().newsRiskLevel());
        }
    }

    private List<CandidateFutureOutcomeEntity> pendingOutcomes(
            List<AgentContractCandidateScoreEntity> rows, Instant scanTime) {
        List<CandidateFutureOutcomeEntity> pending = new ArrayList<>();
        for (AgentContractCandidateScoreEntity row : rows) {
            for (int horizon : List.of(60, 240, 1440)) {
                CandidateFutureOutcomeEntity outcome = new CandidateFutureOutcomeEntity();
                outcome.setSnapshotId(row.getId());
                outcome.setHorizonMinutes(horizon);
                outcome.setMaturityAt(scanTime.plusSeconds(horizon * 60L));
                outcome.setStatus("PENDING");
                outcome.setUpdatedAt(scanTime);
                pending.add(outcome);
            }
        }
        return pending;
    }

    private TradeGate tradeGate(ContractCandidate candidate) {
        List<String> reasons = ContractTradeGate.scoreDenyReasons(candidate, agentProperties);
        return reasons.isEmpty()
                ? new TradeGate(true, "")
                : new TradeGate(false, String.join(",", reasons));
    }

    private CandidateRawMarket rawMarket(ContractCandidate candidate) {
        return new CandidateRawMarket(
                candidate.lastPrice(),
                candidate.changePercent24h(),
                candidate.changePercent5m(),
                candidate.volume24h(),
                candidate.volumeSpikeRatio(),
                candidate.fundingRate(),
                candidate.openInterest(),
                candidate.openInterestChange(),
                candidate.volatility(),
                candidate.spreadBps(),
                candidate.bidDepthUsdt(),
                candidate.askDepthUsdt(),
                candidate.estimatedSlippageBps(),
                candidate.btcTrend(),
                candidate.ethTrend(),
                candidate.marketRiskLevel(),
                candidate.marketCapUsd(),
                candidate.fdvUsd(),
                candidate.circulationRatio(),
                candidate.volumeMcapRatio(),
                candidate.marketQualityNote(),
                candidate.createdAt()
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{\"serializationError\":\"" + ex.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private record TradeGate(boolean allowTrade, String denyReason) {
    }

    private record CandidateRawMarket(
            Object lastPrice,
            Object changePercent24h,
            Object changePercent5m,
            Object volume24h,
            Object volumeSpikeRatio,
            Object fundingRate,
            Object openInterest,
            Object openInterestChange,
            Object volatility,
            Object spreadBps,
            Object bidDepthUsdt,
            Object askDepthUsdt,
            Object estimatedSlippageBps,
            Object btcTrend,
            Object ethTrend,
            Object marketRiskLevel,
            Object marketCapUsd,
            Object fdvUsd,
            Object circulationRatio,
            Object volumeMcapRatio,
            Object marketQualityNote,
            Instant createdAt
    ) {
    }
}

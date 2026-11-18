package com.example.quant.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.agent.score.AgentContractCandidateScoreEntity;
import com.example.quant.agent.score.AgentContractCandidateScoreRepository;
import com.example.quant.agent.scan.AgentScanRunEntity;
import com.example.quant.agent.scan.AgentScanRunRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoricalCandidateEvaluationServiceTest {
    @Test
    void isolatesScoringVersionAndMarksSmallRankSamplesInsufficient() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        AgentContractCandidateScoreEntity wave42 = snapshot(1L, 1, "V2_WAVE_4_2", "0.85");
        AgentContractCandidateScoreEntity wave43 = snapshot(9L, 1, "V2_WAVE_4_3", "0.90");
        AgentContractCandidateScoreEntity old = snapshot(2L, 1, "V2_WAVE_4", "0.95");
        CandidateFutureOutcomeEntity outcome = outcome(1L, 60, "0.10", "0.10");
        when(snapshots.findByScoringVersionOrderBySnapshotRankAsc("V2_WAVE_4_2"))
                .thenReturn(List.of(wave42));
        when(outcomes.findBySnapshotIdIn(List.of(1L))).thenReturn(List.of(outcome));
        when(snapshots.findByScoringVersionOrderBySnapshotRankAsc("V2_WAVE_4_3"))
                .thenReturn(List.of(wave43));
        when(outcomes.findBySnapshotIdIn(List.of(9L))).thenReturn(List.of(outcome(9L, 60, "-0.05", "-0.05")));

        HistoricalCandidateEvaluationService service =
                new HistoricalCandidateEvaluationService(snapshots, outcomes);

        HistoricalCandidateEvaluationService.EvaluationReport report =
                service.evaluate("V2_WAVE_4_2", 20);

        assertThat(report.candidateCount()).isEqualTo(1);
        assertThat(report.horizons()).containsKey(60);
        HistoricalCandidateEvaluationService.HorizonEvaluation hour = report.horizons().get(60);
        assertThat(hour.completeOutcomeCount()).isEqualTo(1);
        assertThat(hour.missingOutcomeCount()).isZero();
        assertThat(hour.rankBuckets().get("RANK_1_5").sampleCount()).isEqualTo(1);
        assertThat(hour.rankBuckets().get("RANK_1_5").insufficientSample()).isTrue();
        assertThat(hour.rankBuckets().get("RANK_1_5").averageRawReturn())
                .isEqualByComparingTo("0.10");
        assertThat(hour.coverageBuckets().get("HIGH").sampleCount()).isEqualTo(1);
        var newReport = service.evaluate("V2_WAVE_4_3", 20);
        assertThat(newReport.candidateCount()).isEqualTo(1);
        assertThat(newReport.horizons().get(60).rankBuckets().get("RANK_1_5").averageRawReturn())
                .isEqualByComparingTo("-0.05");
    }

    @Test
    void nullDimensionIsExcludedAndScoreBoundariesAreHalfOpen() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(3L, 20, "V2_WAVE_4_2", "0.50");
        snapshot.setCapitalFlowScore(new BigDecimal("20"));
        snapshot.setDerivativesScore(null);
        CandidateFutureOutcomeEntity outcome = outcome(3L, 60, "0.01", "0.01");
        when(snapshots.findByScoringVersionOrderBySnapshotRankAsc("V2_WAVE_4_2"))
                .thenReturn(List.of(snapshot));
        when(outcomes.findBySnapshotIdIn(List.of(3L))).thenReturn(List.of(outcome));

        HistoricalCandidateEvaluationService.HorizonEvaluation hour =
                new HistoricalCandidateEvaluationService(snapshots, outcomes)
                        .evaluate("V2_WAVE_4_2", 1).horizons().get(60);

        assertThat(hour.dimensionBuckets()).containsKey("CAPITAL_FLOW:20_40");
        assertThat(hour.dimensionBuckets()).doesNotContainKey("CAPITAL_FLOW:0_20");
        assertThat(hour.dimensionBuckets().keySet()).noneMatch(key -> key.startsWith("DERIVATIVES:"));
    }

    @Test
    void rawDirectionalAndExcursionSamplesUseIndependentDenominators() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        AgentContractCandidateScoreEntity unknown = snapshot(4L, 1, "V2_WAVE_4_2", "0.85");
        AgentContractCandidateScoreEntity directional = snapshot(5L, 2, "V2_WAVE_4_2", "0.85");
        CandidateFutureOutcomeEntity unknownOutcome = outcome(4L, 60, "0.02", null);
        unknownOutcome.setDirectionalMfe(null);
        unknownOutcome.setDirectionalMae(null);
        CandidateFutureOutcomeEntity directionalOutcome = outcome(5L, 60, "0.04", "0.04");
        when(snapshots.findByScoringVersionOrderBySnapshotRankAsc("V2_WAVE_4_2"))
                .thenReturn(List.of(unknown, directional));
        when(outcomes.findBySnapshotIdIn(List.of(4L, 5L)))
                .thenReturn(List.of(unknownOutcome, directionalOutcome));

        HistoricalCandidateEvaluationService.BucketMetrics metrics =
                new HistoricalCandidateEvaluationService(snapshots, outcomes)
                        .evaluate("V2_WAVE_4_2", 1).horizons().get(60)
                        .rankBuckets().get("RANK_1_5");

        assertThat(metrics.sampleCount()).isEqualTo(2);
        assertThat(metrics.rawSampleCount()).isEqualTo(2);
        assertThat(metrics.directionalSampleCount()).isEqualTo(1);
        assertThat(metrics.excursionSampleCount()).isEqualTo(1);
        assertThat(metrics.hitRate()).isEqualByComparingTo("1");
    }

    @Test
    void incompleteScanCohortIsExcludedFromEvaluation() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        AgentScanRunRepository runs = mock(AgentScanRunRepository.class);
        AgentScanRunEntity run = org.mockito.Mockito.mock(AgentScanRunEntity.class);
        when(run.getId()).thenReturn(77L);
        when(run.getStatus()).thenReturn("SUCCESS");
        when(run.getCandidateCount()).thenReturn(3);
        AgentContractCandidateScoreEntity first = snapshot(6L, 1, "V2_WAVE_4_2", "0.85");
        first.setScanRunId(77L);
        AgentContractCandidateScoreEntity second = snapshot(7L, 2, "V2_WAVE_4_2", "0.85");
        second.setScanRunId(77L);
        when(snapshots.findByScoringVersionOrderBySnapshotRankAsc("V2_WAVE_4_2"))
                .thenReturn(List.of(first, second));
        when(runs.findById(77L)).thenReturn(java.util.Optional.of(run));
        when(snapshots.countByScanRunId(77L)).thenReturn(2L);

        HistoricalCandidateEvaluationService.EvaluationReport report =
                new HistoricalCandidateEvaluationService(snapshots, outcomes, runs)
                        .evaluate("V2_WAVE_4_2", 1);

        assertThat(report.incompleteCohortCount()).isEqualTo(1);
        assertThat(report.candidateCount()).isZero();
        assertThat(report.horizons().get(60).candidateCount()).isZero();
    }

    private static AgentContractCandidateScoreEntity snapshot(Long id, int rank, String version, String coverage) {
        AgentContractCandidateScoreEntity entity = new AgentContractCandidateScoreEntity();
        entity.setId(id);
        entity.setInstId("COIN-" + id + "-USDT-SWAP");
        entity.setSnapshotRank(rank);
        entity.setScoringVersion(version);
        entity.setV2CoverageRatio(new BigDecimal(coverage));
        entity.setV2RankingScore(new BigDecimal("80"));
        entity.setCapitalFlowScore(new BigDecimal("80"));
        entity.setCreatedAt(Instant.parse("2026-09-18T10:00:00Z"));
        return entity;
    }

    private static CandidateFutureOutcomeEntity outcome(Long snapshotId, int horizon,
                                                         String rawReturn, String directionalReturn) {
        CandidateFutureOutcomeEntity entity = new CandidateFutureOutcomeEntity();
        entity.setSnapshotId(snapshotId);
        entity.setHorizonMinutes(horizon);
        entity.setStatus("COMPLETE");
        entity.setRawReturn(new BigDecimal(rawReturn));
        if (directionalReturn != null) {
            entity.setDirectionalReturn(new BigDecimal(directionalReturn));
            entity.setDirectionalMfe(new BigDecimal("0.02"));
            entity.setDirectionalMae(new BigDecimal("0.01"));
        }
        return entity;
    }
}

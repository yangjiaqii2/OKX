package com.example.quant.agent.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.agent.audit.AgentAuditLogger;
import com.example.quant.agent.evaluation.CandidateFutureOutcomeRepository;
import com.example.quant.agent.scan.AgentScanRunEntity;
import com.example.quant.agent.scan.AgentScanRunRepository;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.crypto.selection.V2ScoreDimension;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class ContractCandidateScoreRecorderTest {
    @Test
    void recordsLiveContractScanWhileAgentExecutionIsDisabled() {
        AgentProperties properties = new AgentProperties();
        AgentScanRunRepository runs = mock(AgentScanRunRepository.class);
        AgentContractCandidateScoreRepository scores = mock(AgentContractCandidateScoreRepository.class);
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ContractCandidateScoreRecorder recorder = new ContractCandidateScoreRecorder(
                properties, runs, scores, mock(AgentAuditLogger.class), new ObjectMapper());
        Instant now = Instant.parse("2026-09-07T12:00:00Z");

        recorder.recordLiveScan(List.of(candidate(now)), now.minusSeconds(1), now);

        verify(runs).save(any(AgentScanRunEntity.class));
        verify(scores).saveAll(anyList());
    }

    @Test
    void recordsImmutableV2SnapshotFactsAndThreePendingOutcomes() {
        AgentProperties properties = new AgentProperties();
        AgentScanRunRepository runs = mock(AgentScanRunRepository.class);
        AgentContractCandidateScoreRepository scores = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(scores.saveAll(anyList())).thenAnswer(invocation -> {
            List<AgentContractCandidateScoreEntity> saved = invocation.getArgument(0);
            saved.forEach(row -> row.setId(101L));
            return saved;
        });
        when(outcomes.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        ContractCandidateScoreRecorder recorder = new ContractCandidateScoreRecorder(
                properties, runs, scores, outcomes, mock(AgentAuditLogger.class), new ObjectMapper());
        Instant now = Instant.parse("2026-09-18T10:00:00Z");

        recorder.recordLiveScan(List.of(candidate(now)), now.minusSeconds(1), now, true);

        ArgumentCaptor<List<AgentContractCandidateScoreEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(scores).saveAll(rows.capture());
        AgentContractCandidateScoreEntity row = rows.getValue().get(0);
        assertThat(row.getSnapshotRank()).isEqualTo(1);
        assertThat(row.getReferencePrice()).isEqualByComparingTo("4400");
        assertThat(row.getAnalysisScore()).isEqualTo(64);
        assertThat(row.getBaseOiChangeMissing()).isTrue();
        assertThat(row.getAnalysisAction()).isEqualTo("WAIT_CONFIRM");
        assertThat(row.getScanAction()).isEqualTo("AUTO_TRADE_ALLOWED");
        assertThat(row.getScoringVersion()).isEqualTo("V2_WAVE_4_2");
        assertThat(row.getV2NormalizedScore()).isNotNull();
        assertThat(row.getV2RankingScore()).isNotNull();
        assertThat(row.getRelativeStrengthScore()).isNull();

        ArgumentCaptor<List<com.example.quant.agent.evaluation.CandidateFutureOutcomeEntity>> pending =
                ArgumentCaptor.forClass(List.class);
        verify(outcomes).saveAll(pending.capture());
        assertThat(pending.getValue()).hasSize(3)
                .allSatisfy(outcome -> {
                    assertThat(outcome.getStatus()).isEqualTo("PENDING");
                    assertThat(outcome.getFuturePrice()).isNull();
                    assertThat(outcome.getRawReturn()).isNull();
                });
    }

    @Test
    void outcomePersistenceFailureDoesNotEscapeRecorderBoundary() {
        AgentProperties properties = new AgentProperties();
        AgentScanRunRepository runs = mock(AgentScanRunRepository.class);
        AgentContractCandidateScoreRepository scores = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(scores.saveAll(anyList())).thenAnswer(invocation -> {
            List<AgentContractCandidateScoreEntity> saved = invocation.getArgument(0);
            saved.forEach(row -> row.setId(102L));
            return saved;
        });
        when(outcomes.saveAll(anyList())).thenThrow(new IllegalStateException("outcome db unavailable"));
        ContractCandidateScoreRecorder recorder = new ContractCandidateScoreRecorder(
                properties, runs, scores, outcomes, mock(AgentAuditLogger.class), new ObjectMapper());

        assertThatCode(() -> recorder.recordLiveScan(
                List.of(candidate(Instant.parse("2026-09-18T10:00:00Z"))),
                Instant.parse("2026-09-18T09:59:59Z"),
                Instant.parse("2026-09-18T10:00:00Z"), true)).doesNotThrowAnyException();
    }

    @Test
    void assignsOneDurableCohortPerScanAndChangesItForTheNextScan() {
        AgentProperties properties = new AgentProperties();
        AgentScanRunRepository runs = mock(AgentScanRunRepository.class);
        AgentContractCandidateScoreRepository scores = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        AtomicLong scanIds = new AtomicLong(700L);
        when(runs.save(any())).thenAnswer(invocation -> {
            AgentScanRunEntity saved = mock(AgentScanRunEntity.class);
            when(saved.getId()).thenReturn(scanIds.getAndIncrement());
            return saved;
        });
        when(scores.saveAll(anyList())).thenAnswer(invocation -> {
            List<AgentContractCandidateScoreEntity> saved = invocation.getArgument(0);
            saved.forEach(row -> row.setId(row.getScanRunId() + row.getSnapshotRank()));
            return saved;
        });
        ContractCandidateScoreRecorder recorder = new ContractCandidateScoreRecorder(
                properties, runs, scores, outcomes, mock(AgentAuditLogger.class), new ObjectMapper());
        Instant first = Instant.parse("2026-09-18T10:00:00Z");

        recorder.recordLiveScan(List.of(candidate(first), candidate(first)), first, first, true);
        recorder.recordLiveScan(List.of(candidate(first)), first.plusSeconds(60), first.plusSeconds(60), true);

        ArgumentCaptor<List<AgentContractCandidateScoreEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(scores, org.mockito.Mockito.times(2)).saveAll(rows.capture());
        assertThat(rows.getAllValues().get(0)).extracting(AgentContractCandidateScoreEntity::getScanRunId)
                .containsOnly(700L);
        assertThat(rows.getAllValues().get(1)).extracting(AgentContractCandidateScoreEntity::getScanRunId)
                .containsOnly(701L);
    }

    @Test
    void persistsTheExactV2ResultUsedByScannerInsteadOfRecalculatingIt() {
        AgentProperties properties = new AgentProperties();
        AgentScanRunRepository runs = mock(AgentScanRunRepository.class);
        AgentContractCandidateScoreRepository scores = mock(AgentContractCandidateScoreRepository.class);
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(scores.saveAll(anyList())).thenAnswer(invocation -> {
            List<AgentContractCandidateScoreEntity> saved = invocation.getArgument(0);
            saved.forEach(row -> row.setId(103L));
            return saved;
        });
        ContractCandidateScoreRecorder recorder = new ContractCandidateScoreRecorder(
                properties, runs, scores, mock(CandidateFutureOutcomeRepository.class),
                mock(AgentAuditLogger.class), new ObjectMapper());
        V2SelectionScoreResult scannerResult = new V2SelectionScoreResult(
                new BigDecimal("12.34"), new BigDecimal("56.78"), new BigDecimal("25"),
                new BigDecimal("0.25"),
                Map.of(V2ScoreDimension.CAPITAL_FLOW, new BigDecimal("12.34")),
                List.of());

        recorder.recordLiveScan(List.of(candidate(Instant.parse("2026-09-18T10:00:00Z"))),
                Instant.parse("2026-09-18T09:59:59Z"), Instant.parse("2026-09-18T10:00:00Z"),
                true, Map.of("XAU-USDT-SWAP", scannerResult));

        ArgumentCaptor<List<AgentContractCandidateScoreEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(scores).saveAll(rows.capture());
        AgentContractCandidateScoreEntity row = rows.getValue().get(0);
        assertThat(row.getV2NormalizedScore()).isEqualByComparingTo("12.34");
        assertThat(row.getV2RankingScore()).isEqualByComparingTo("56.78");
        assertThat(row.getV2AvailableWeight()).isEqualByComparingTo("25");
        assertThat(row.getCapitalFlowScore()).isEqualByComparingTo("12.34");
    }

    private static ContractCandidate candidate(Instant now) {
        return new ContractCandidate(
                MarketType.OKX_SWAP, "XAU-USDT-SWAP", "XAU", "USDT", new BigDecimal("4400"),
                BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("100000000"), BigDecimal.ONE,
                new BigDecimal("0.0001"), BigDecimal.ZERO, null, DirectionBias.BULLISH,
                new BigDecimal("0.02"), 80, new ContractFactorScore(20, 15, 10, 10, 10, 10, 7),
                new BigDecimal("4400"), new BigDecimal("4356"), new BigDecimal("4488"), 1,
                BigDecimal.valueOf(2), BigDecimal.ONE, new BigDecimal("100000"), new BigDecimal("100000"),
                BigDecimal.ONE, DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW,
                List.of("QA_E2E"), List.of(), now,
                ContractSignalType.STRONG_LONG, "AUTO_TRADE_ALLOWED", "LIMIT",
                BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.ZERO, BigDecimal.valueOf(80),
                new ContractScoreBreakdown(80, 80, 80, 80, 80, 80, 100),
                ContractKlineAnalysis.unavailable("FIXTURE").withAnalysisDecision(64, "WAIT_CONFIRM"),
                ContractNewsRiskAnalysis.low());
    }
}

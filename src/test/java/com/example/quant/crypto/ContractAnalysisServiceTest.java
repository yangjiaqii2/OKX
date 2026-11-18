package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.quant.agent.scan.ScanContext;
import com.example.quant.agent.scan.ScanRunReport;
import com.example.quant.agent.scan.ScanRunStatus;
import com.example.quant.agent.score.ContractCandidateScoreRecorder;
import com.example.quant.config.ContractProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import com.example.quant.score.ContractScoreCalculator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ContractAnalysisServiceTest {

    @Test
    void readsCachedValueWithoutBlockingWhileRefreshIsInProgress() throws Exception {
        OkxContractScanner scanner = mock(OkxContractScanner.class);
        CountDownLatch scanStarted = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        when(scanner.scan(any(ScanContext.class))).thenAnswer(invocation -> {
            scanStarted.countDown();
            releaseScan.await(2, TimeUnit.SECONDS);
            return Collections.singletonList(null);
        });
        when(scanner.lastScanReport()).thenReturn(completedReport(1));
        when(scanner.lastV2ScoreResults()).thenReturn(Map.of());
        ContractAnalysisService service = new ContractAnalysisService(
                scanner, null, mock(ContractCandidateScoreRecorder.class));
        var executor = Executors.newFixedThreadPool(2);
        Future<?> refresh = executor.submit(service::refreshCandidates);
        assertThat(scanStarted.await(1, TimeUnit.SECONDS)).isTrue();

        Future<?> read = executor.submit(service::candidates);
        assertThat(read.get(200, TimeUnit.MILLISECONDS)).isEqualTo(Collections.emptyList());

        releaseScan.countDown();
        refresh.get(2, TimeUnit.SECONDS);
        executor.shutdown();
    }

    @Test
    void concurrentFirstRequestsOnlyReadSnapshotAndNeverTriggerMarketScan() throws Exception {
        OkxContractScanner scanner = mock(OkxContractScanner.class);
        ContractCandidateScoreRecorder recorder = mock(ContractCandidateScoreRecorder.class);
        ContractAnalysisService service = new ContractAnalysisService(scanner, null, recorder);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var first = executor.submit(() -> { start.await(); return service.candidates(); });
        var second = executor.submit(() -> { start.await(); return service.candidates(); });

        start.countDown();
        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
        executor.shutdown();

        verifyNoInteractions(scanner);
    }

    @Test
    void disabledContractModuleDoesNotTriggerFirstRequestScan() {
        OkxContractScanner scanner = mock(OkxContractScanner.class);
        ContractAnalysisService service = new ContractAnalysisService(scanner, null, null);
        service.configureContractProperties(new ContractProperties(
                false, null, 0, null, null, null, null, null, null, 0, null, null, null, null, null, false));

        assertThat(service.candidates()).isEmpty();
        assertThat(service.refreshCandidates()).isEmpty();
        verifyNoInteractions(scanner);
    }

    @Test
    void historicalSnapshotFailureDoesNotChangeV2ScanResult() {
        OkxContractScanner scanner = mock(OkxContractScanner.class);
        ContractCandidateScoreRecorder recorder = mock(ContractCandidateScoreRecorder.class);
        List<ContractCandidate> candidates = Collections.singletonList(null);
        when(scanner.scan(any(ScanContext.class))).thenReturn(candidates);
        when(scanner.isV2Mode()).thenReturn(true);
        when(scanner.lastScanReport()).thenReturn(completedReport(1));
        when(scanner.lastV2ScoreResults()).thenReturn(Map.of());
        doThrow(new IllegalStateException("snapshot db unavailable")).when(recorder)
                .recordScanOutcome(any(), anyList(), any(), any(), eq(true), anyMap());
        ContractAnalysisService service = new ContractAnalysisService(scanner, null, recorder);

        assertThat(service.refreshCandidates()).isEmpty();
    }

    @Test
    void partialScanDoesNotOverwriteExistingCache() {
        OkxContractScanner scanner = mock(OkxContractScanner.class);
        ContractCandidateScoreRecorder recorder = mock(ContractCandidateScoreRecorder.class);
        ContractCandidate existing = sampleCandidate("BTC-USDT-SWAP", 88);
        ContractCandidate partial = sampleCandidate("ETH-USDT-SWAP", 55);
        when(scanner.isV2Mode()).thenReturn(true);
        ScanRunReport completedReport = completedReport(1);
        ScanRunReport partialReport = partialReport();
        when(scanner.scan(any(ScanContext.class))).thenReturn(List.of(existing));
        when(scanner.lastScanReport()).thenReturn(completedReport);
        when(scanner.lastV2ScoreResults()).thenReturn(Map.of(
                "BTC-USDT-SWAP",
                new V2SelectionScoreResult(BigDecimal.valueOf(88), BigDecimal.valueOf(88),
                        BigDecimal.ONE, BigDecimal.ONE, Map.of(), List.of())
        ));
        ContractAnalysisService service = new ContractAnalysisService(scanner, null, recorder);
        service.refreshScan("completed");

        when(scanner.scan(any(ScanContext.class))).thenReturn(List.of(partial));
        when(scanner.lastScanReport()).thenReturn(partialReport);
        when(scanner.lastV2ScoreResults()).thenReturn(Map.of());

        ContractScanResult partialResult = service.refreshScan("partial");

        assertThat(partialResult.candidates()).isEmpty();
        assertThat(partialResult.status()).isEqualTo(ScanRunStatus.PARTIAL_TIMEOUT);
        assertThat(service.candidates()).extracting(ContractCandidate::instId).containsExactly("BTC-USDT-SWAP");
        var ordered = inOrder(recorder);
        ordered.verify(recorder).recordScanOutcome(
                eq(completedReport), eq(List.of(existing)), any(), any(), eq(true), anyMap());
        ordered.verify(recorder).recordScanOutcome(
                eq(partialReport), eq(List.of()), any(), any(), eq(true), eq(Map.of()));
    }

    @Test
    void completedEmptyScanDoesNotOverwriteExistingNonEmptySnapshot() {
        OkxContractScanner scanner = mock(OkxContractScanner.class);
        ContractCandidate existing = sampleCandidate("BTC-USDT-SWAP", 88);
        when(scanner.scan(any(ScanContext.class))).thenReturn(List.of(existing), List.of());
        when(scanner.lastScanReport()).thenReturn(completedReport(1), completedReport(0));
        when(scanner.lastV2ScoreResults()).thenReturn(Map.of(
                "BTC-USDT-SWAP",
                new V2SelectionScoreResult(BigDecimal.valueOf(88), BigDecimal.valueOf(88),
                        BigDecimal.ONE, BigDecimal.ONE, Map.of(), List.of())
        ), Map.of());
        ContractAnalysisService service = new ContractAnalysisService(scanner, null, null);

        service.refreshScan("non-empty");
        ContractScanResult emptyResult = service.refreshScan("empty");

        assertThat(emptyResult.candidates()).isEmpty();
        assertThat(service.candidates()).extracting(ContractCandidate::instId)
                .containsExactly("BTC-USDT-SWAP");
        assertThat(service.cachedV2Scores()).containsKey("BTC-USDT-SWAP");
    }

    @Test
    void analyzeUsesCanonicalV2ScoreFromCompletedScan() {
        OkxContractScanner scanner = mock(OkxContractScanner.class);
        ContractCandidate candidate = sampleCandidate("BTC-USDT-SWAP", 70);
        when(scanner.scan(any(ScanContext.class))).thenReturn(List.of(candidate));
        when(scanner.lastScanReport()).thenReturn(completedReport(1));
        when(scanner.lastV2ScoreResults()).thenReturn(Map.of(
                "BTC-USDT-SWAP",
                new V2SelectionScoreResult(BigDecimal.valueOf(81), BigDecimal.valueOf(91),
                        BigDecimal.ONE, BigDecimal.ONE, Map.of(), List.of())
        ));
        ContractAnalysisService service = new ContractAnalysisService(
                scanner, mock(ContractScoreCalculator.class), null);
        service.refreshScan("scan-1");

        assertThat(service.analyze("BTC-USDT-SWAP").score()).isEqualTo(91);
        assertThat(service.analyze("BTC-USDT-SWAP").summary()).contains("rankingScore=91");
    }

    @Test
    void analyzeRejectsInstIdOutsideValidCachedScan() {
        ContractAnalysisService service = new ContractAnalysisService(mock(OkxContractScanner.class), null, null);
        assertThatThrownBy(() -> service.analyze("BTC-USDT-SWAP"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不在当前有效扫描结果中");
    }

    @Test
    void missingScanReportDoesNotOverwriteCacheOrRecordSuccess() {
        OkxContractScanner scanner = mock(OkxContractScanner.class);
        ContractCandidateScoreRecorder recorder = mock(ContractCandidateScoreRecorder.class);
        ContractCandidate existing = sampleCandidate("BTC-USDT-SWAP", 88);
        when(scanner.isV2Mode()).thenReturn(true);
        ScanRunReport completedReport = completedReport(1);
        when(scanner.scan(any(ScanContext.class))).thenReturn(List.of(existing));
        when(scanner.lastScanReport()).thenReturn(completedReport);
        when(scanner.lastV2ScoreResults()).thenReturn(Map.of(
                "BTC-USDT-SWAP",
                new V2SelectionScoreResult(BigDecimal.valueOf(88), BigDecimal.valueOf(88),
                        BigDecimal.ONE, BigDecimal.ONE, Map.of(), List.of())
        ));
        ContractAnalysisService service = new ContractAnalysisService(scanner, null, recorder);
        service.refreshScan("completed");

        when(scanner.scan(any(ScanContext.class))).thenReturn(List.of(sampleCandidate("ETH-USDT-SWAP", 55)));
        when(scanner.lastScanReport()).thenReturn(null);

        ContractScanResult result = service.refreshScan("missing-report");

        assertThat(result.status()).isEqualTo(ScanRunStatus.UNKNOWN);
        assertThat(service.candidates()).extracting(ContractCandidate::instId).containsExactly("BTC-USDT-SWAP");
        verify(recorder).recordScanOutcome(isNull(), eq(List.of()), any(), any(), eq(true), eq(Map.of()));
    }

    @Test
    void analyzeRejectsMissingV2CanonicalScore() {
        OkxContractScanner scanner = mock(OkxContractScanner.class);
        ContractCandidate candidate = sampleCandidate("BTC-USDT-SWAP", 70);
        when(scanner.scan(any(ScanContext.class))).thenReturn(List.of(candidate));
        when(scanner.lastScanReport()).thenReturn(completedReport(1));
        when(scanner.lastV2ScoreResults()).thenReturn(Map.of());
        ContractAnalysisService service = new ContractAnalysisService(scanner, null, null);
        service.refreshScan("scan-1");

        assertThatThrownBy(() -> service.analyze("BTC-USDT-SWAP"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V2 canonical score 不可用");
    }

    @Test
    void liveSnapshotPublishesCandidatesAndScoresAtomically() throws Exception {
        OkxContractScanner scanner = mock(OkxContractScanner.class);
        ContractCandidate candidate = sampleCandidate("BTC-USDT-SWAP", 70);
        V2SelectionScoreResult score = new V2SelectionScoreResult(
                BigDecimal.valueOf(81), BigDecimal.valueOf(91),
                BigDecimal.ONE, BigDecimal.ONE, Map.of(), List.of());
        when(scanner.scan(any(ScanContext.class))).thenReturn(List.of(candidate));
        when(scanner.lastScanReport()).thenReturn(completedReport(1));
        when(scanner.lastV2ScoreResults()).thenReturn(Map.of("BTC-USDT-SWAP", score));
        ContractAnalysisService service = new ContractAnalysisService(scanner, null, null);
        ContractScanResult result = service.refreshScan("scan-1");

        LiveScanSnapshot snapshot = service.liveSnapshot();
        assertThat(snapshot.candidates()).extracting(ContractCandidate::instId).containsExactly("BTC-USDT-SWAP");
        assertThat(snapshot.v2Scores()).containsEntry("BTC-USDT-SWAP", score);
        assertThat(result.v2Scores()).containsEntry("BTC-USDT-SWAP", score);
        assertThat(snapshot.status()).isEqualTo(ScanRunStatus.COMPLETED);
        assertThat(snapshot.startedAt()).isNotNull();
        assertThat(service.cachedV2Scores()).isSameAs(snapshot.v2Scores());
    }

    @Test
    void concurrentReadersSeeSameScanIdForCandidatesAndScores() throws Exception {
        OkxContractScanner scanner = mock(OkxContractScanner.class);
        ContractCandidate candidate = sampleCandidate("BTC-USDT-SWAP", 70);
        V2SelectionScoreResult score = new V2SelectionScoreResult(
                BigDecimal.valueOf(81), BigDecimal.valueOf(91),
                BigDecimal.ONE, BigDecimal.ONE, Map.of(), List.of());
        ScanRunReport report = completedReport(1);
        when(scanner.scan(any(ScanContext.class))).thenReturn(List.of(candidate));
        when(scanner.lastScanReport()).thenReturn(report);
        when(scanner.lastV2ScoreResults()).thenReturn(Map.of("BTC-USDT-SWAP", score));
        ContractAnalysisService service = new ContractAnalysisService(scanner, null, null);
        service.refreshScan("scan-atomic");

        LiveScanSnapshot snapshot = service.liveSnapshot();
        assertThat(snapshot.scanId()).isEqualTo(report.scanId());
        assertThat(service.candidates()).isSameAs(snapshot.candidates());
        assertThat(service.cachedV2Scores()).isSameAs(snapshot.v2Scores());
        assertThat(service.cachedV2Scores().get("BTC-USDT-SWAP").rankingScore())
                .isEqualByComparingTo("91");
    }

    private static ScanRunReport completedReport(int completed) {
        return new ScanRunReport(
                "scan-completed",
                ScanRunStatus.COMPLETED,
                Instant.now(),
                Instant.now(),
                10,
                "completed",
                10,
                10,
                30,
                completed,
                completed,
                0,
                0,
                null
        );
    }

    private static ScanRunReport partialReport() {
        return new ScanRunReport(
                "scan-partial",
                ScanRunStatus.PARTIAL_TIMEOUT,
                Instant.now(),
                Instant.now(),
                100,
                "deep_analysis",
                10,
                10,
                30,
                3,
                1,
                1,
                2,
                null
        );
    }

    private static ContractCandidate sampleCandidate(String instId, int score) {
        ContractFactorScore factors = new ContractFactorScore(20, 11, 12, 3, 8, 10, 8);
        return new ContractCandidate(
                MarketType.OKX_SWAP, instId, instId.substring(0, instId.indexOf('-')), "USDT",
                BigDecimal.valueOf(100), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.valueOf(100_000_000), BigDecimal.ONE, new BigDecimal("0.0001"),
                BigDecimal.valueOf(1_000_000), BigDecimal.ZERO, DirectionBias.BULLISH,
                new BigDecimal("0.01"), score, factors, BigDecimal.valueOf(100),
                BigDecimal.valueOf(98), BigDecimal.valueOf(105), 2, new BigDecimal("2.5"),
                BigDecimal.ONE, BigDecimal.valueOf(50_000), BigDecimal.valueOf(50_000), BigDecimal.ONE,
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW, List.of("reason"), List.of("risk"),
                Instant.now(),
                ContractSignalType.STRONG_LONG, "AUTO_TRADE_ALLOWED", "LIMIT", BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.valueOf(score),
                new ContractScoreBreakdown(score, score, score, score, score, score, score),
                new ContractKlineAnalysis("UP", "UP", "UP", "BULLISH", "STRENGTHENING",
                        BigDecimal.valueOf(60), BigDecimal.valueOf(30), "TREND", "READY",
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, true, Instant.parse("2026-08-09T12:00:00Z")),
                ContractNewsRiskAnalysis.low()
        );
    }
}

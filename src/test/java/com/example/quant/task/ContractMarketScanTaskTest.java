package com.example.quant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.agent.execution.AutoTradeRecordService;
import com.example.quant.agent.execution.AutoTradeService;
import com.example.quant.agent.execution.AutoTradeService.AutoTradeResult;
import com.example.quant.agent.execution.PositionCapacityScanGate;
import com.example.quant.agent.execution.TradeOrderRecordService;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.ContractProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.agent.scan.ScanRunReport;
import com.example.quant.agent.scan.ScanRunStatus;
import com.example.quant.crypto.ContractAnalysisService;
import com.example.quant.crypto.ContractScanResult;
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
import com.example.quant.order.PendingOrderService;
import com.example.quant.strategydecision.StrategyDecisionService;
import com.example.quant.strategydecision.StrategyDecisionStatus;
import com.example.quant.system.SystemControlService;
import com.example.quant.tradeplan.TradePlanService;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ContractMarketScanTaskTest {

    @Test
    void passesTheCompletedScanV2ScoreSnapshotIntoAutoTrade() {
        ContractAnalysisService analysisService = mock(ContractAnalysisService.class);
        AutoTradeService autoTradeService = mock(AutoTradeService.class);
        ContractCandidate candidate = candidate(Instant.parse("2026-08-09T12:00:00Z"));
        V2SelectionScoreResult score = new V2SelectionScoreResult(
                new BigDecimal("88"), new BigDecimal("86"), new BigDecimal("90"),
                new BigDecimal("0.90"), Map.of(V2ScoreDimension.RELATIVE_STRENGTH, new BigDecimal("92")),
                List.of(V2ScoreDimension.BREAKOUT));
        ContractScanResult scan = completedScan(List.of(candidate), Map.of(candidate.instId(), score));
        when(analysisService.refreshScan(org.mockito.ArgumentMatchers.any())).thenReturn(scan);
        AutoTradeResult skipped = new AutoTradeResult("SKIPPED", null, null, null, null,
                null, null, 0, BigDecimal.ZERO, BigDecimal.ZERO, "test", Instant.now());
        when(autoTradeService.evaluateAndExecute(List.of(candidate), Map.of(), scan.v2Scores()))
                .thenReturn(skipped);

        new ContractMarketScanTask(analysisService, autoTradeService, new AgentProperties(), null, null).runOnce();

        verify(autoTradeService).evaluateAndExecute(List.of(candidate), Map.of(), scan.v2Scores());
    }

    @Test
    void fullPositionCapacitySkipsCandidateRefreshBeforeExternalMarketCalls() {
        ContractAnalysisService analysisService = mock(ContractAnalysisService.class);
        AutoTradeService autoTradeService = mock(AutoTradeService.class);
        AgentProperties properties = new AgentProperties();
        PositionCapacityScanGate gate = mock(PositionCapacityScanGate.class);
        when(gate.evaluate()).thenReturn(new PositionCapacityScanGate.Decision(
                true, "POSITION_CAPACITY_FULL", 3, 3));
        ContractMarketScanTask task = new ContractMarketScanTask(
                analysisService, autoTradeService, properties, null, null);
        task.configureScanCapacityGate(gate);

        ScanTaskLog result = task.runOnce();

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("POSITION_CAPACITY_FULL");
        verify(analysisService, never()).refreshScan(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disabledContractModuleDoesNotScanMarket() {
        ContractAnalysisService analysisService = mock(ContractAnalysisService.class);
        AutoTradeService autoTradeService = mock(AutoTradeService.class);
        ContractProperties contractProperties = new ContractProperties(
                false, null, 0, null, null, null, null, null, null, 0, null, null, null, null, null, false);
        ContractMarketScanTask task = new ContractMarketScanTask(
                analysisService, autoTradeService, new AgentProperties(), null, null);
        task.configureContractProperties(contractProperties);

        ScanTaskLog result = task.runOnce();

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("CONTRACT_MODULE_DISABLED");
        verify(analysisService, org.mockito.Mockito.never()).refreshScan(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void duplicateClosedCandleIsNotEvaluatedTwice() {
        ContractAnalysisService analysisService = mock(ContractAnalysisService.class);
        AutoTradeService autoTradeService = mock(AutoTradeService.class);
        StrategyDecisionService decisions = mock(StrategyDecisionService.class);
        SystemControlService controls = mock(SystemControlService.class);
        Instant candle = Instant.parse("2026-08-09T12:00:00Z");
        ContractCandidate candidate = candidate(candle);
        when(analysisService.refreshScan(org.mockito.ArgumentMatchers.any()))
                .thenReturn(completedScan(List.of(candidate)));
        when(controls.autoTradeOwnerUsername()).thenReturn("alice");
        when(decisions.claim(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new StrategyDecisionService.DecisionClaim(true, "decision-1", "qd123", StrategyDecisionStatus.PROCESSING))
                .thenReturn(new StrategyDecisionService.DecisionClaim(false, "decision-1", "qd123", StrategyDecisionStatus.NO_SIGNAL));
        AutoTradeResult skipped = new AutoTradeResult("SKIPPED", null, null, null, null,
                null, null, 0, BigDecimal.ZERO, BigDecimal.ZERO, "no_signal", Instant.now());
        when(autoTradeService.evaluateAndExecute(List.of(candidate), Map.of("BTC-USDT-SWAP", "qd123")))
                .thenReturn(skipped);
        ContractMarketScanTask task = new ContractMarketScanTask(
                analysisService, autoTradeService, new AgentProperties(), decisions, controls);

        task.runOnce();
        task.runOnce();

        verify(autoTradeService, times(1))
                .evaluateAndExecute(List.of(candidate), Map.of("BTC-USDT-SWAP", "qd123"));
        verify(decisions).complete(
                org.mockito.ArgumentMatchers.eq("decision-1"),
                org.mockito.ArgumentMatchers.eq(StrategyDecisionStatus.NO_SIGNAL),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("no_signal"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void duplicateIsReplacedByNextRankedCandidateWithoutClaimingBeyondTheLimit() {
        ContractAnalysisService analysis = mock(ContractAnalysisService.class);
        AutoTradeService trading = mock(AutoTradeService.class);
        StrategyDecisionService decisions = mock(StrategyDecisionService.class);
        SystemControlService controls = mock(SystemControlService.class);
        Instant candle = Instant.parse("2026-11-20T06:00:00Z");
        ContractCandidate duplicate = candidate(candle);
        ContractCandidate reserve = candidate(candle, "ETH");
        ContractCandidate extra = candidate(candle, "SOL");
        var scan = completedScan(List.of(duplicate));
        when(analysis.refreshScan(org.mockito.ArgumentMatchers.any())).thenReturn(new ContractScanResult(
                scan.candidates(), scan.report(), scan.v2Scores(), List.of(reserve, extra)));
        when(controls.autoTradeOwnerUsername()).thenReturn("alice");
        when(decisions.claim(org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.eq("AUTO_CONTRACT_V1"), org.mockito.ArgumentMatchers.eq(duplicate.instId()),
                org.mockito.ArgumentMatchers.eq("5m"), org.mockito.ArgumentMatchers.eq(candle),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new StrategyDecisionService.DecisionClaim(false, "existing", "qd-btc", StrategyDecisionStatus.NO_SIGNAL));
        when(decisions.claim(org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.eq("AUTO_CONTRACT_V1"), org.mockito.ArgumentMatchers.eq(reserve.instId()),
                org.mockito.ArgumentMatchers.eq("5m"), org.mockito.ArgumentMatchers.eq(candle),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new StrategyDecisionService.DecisionClaim(true, "new", "qd-eth", StrategyDecisionStatus.PROCESSING));
        when(trading.evaluateAndExecute(List.of(reserve), Map.of(reserve.instId(), "qd-eth")))
                .thenReturn(new AutoTradeResult("SKIPPED", null, null, null, null, null, null,
                        0, BigDecimal.ZERO, BigDecimal.ZERO, "test", Instant.now()));

        var result = new ContractMarketScanTask(analysis, trading, new AgentProperties(), decisions, controls).runOnce();

        assertThat(result.status()).isEqualTo("SUCCESS");
        verify(trading).evaluateAndExecute(List.of(reserve), Map.of(reserve.instId(), "qd-eth"));
        verify(decisions, never()).claim(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(extra.instId()),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void selectedWaitingEntryKeepsDecisionInWaitingState() {
        ContractAnalysisService analysisService = mock(ContractAnalysisService.class);
        AutoTradeService autoTradeService = mock(AutoTradeService.class);
        StrategyDecisionService decisions = mock(StrategyDecisionService.class);
        SystemControlService controls = mock(SystemControlService.class);
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        ContractCandidate candidate = candidate(candle);
        when(analysisService.refreshScan(org.mockito.ArgumentMatchers.any()))
                .thenReturn(completedScan(List.of(candidate)));
        when(controls.autoTradeOwnerUsername()).thenReturn("alice");
        when(decisions.claim(org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.eq("AUTO_CONTRACT_V1"),
                org.mockito.ArgumentMatchers.eq("BTC-USDT-SWAP"), org.mockito.ArgumentMatchers.eq("5m"),
                org.mockito.ArgumentMatchers.eq(candle), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new StrategyDecisionService.DecisionClaim(
                        true, "decision-wait", "qd-wait", StrategyDecisionStatus.PROCESSING));
        when(autoTradeService.evaluateAndExecute(List.of(candidate), Map.of("BTC-USDT-SWAP", "qd-wait")))
                .thenReturn(new AutoTradeResult("WAITING_ENTRY", "BTC-USDT-SWAP", null, null, null,
                        null, null, 0, BigDecimal.ZERO, BigDecimal.ZERO, "AI_WAITING_ENTRY", Instant.now()));

        new ContractMarketScanTask(analysisService, autoTradeService, new AgentProperties(), decisions, controls)
                .runOnce();

        verify(decisions).complete(org.mockito.ArgumentMatchers.eq("decision-wait"),
                org.mockito.ArgumentMatchers.eq(StrategyDecisionStatus.WAITING_ENTRY),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("AI_WAITING_ENTRY"), org.mockito.ArgumentMatchers.any());
    }

    private static ContractCandidate candidate(Instant candle) {
        return candidate(candle, "BTC");
    }

    private static ContractCandidate candidate(Instant candle, String base) {
        ContractFactorScore factors = new ContractFactorScore(20, 11, 12, 3, 8, 10, 8);
        return new ContractCandidate(
                MarketType.OKX_SWAP, base + "-USDT-SWAP", base, "USDT",
                BigDecimal.valueOf(100), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.valueOf(100_000_000), BigDecimal.ONE, new BigDecimal("0.0001"),
                BigDecimal.valueOf(1_000_000), BigDecimal.ZERO, DirectionBias.BULLISH,
                new BigDecimal("0.01"), 90, factors, BigDecimal.valueOf(100),
                BigDecimal.valueOf(98), BigDecimal.valueOf(105), 2, new BigDecimal("2.5"),
                BigDecimal.ONE, BigDecimal.valueOf(50_000), BigDecimal.valueOf(50_000), BigDecimal.ONE,
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW, List.of(), List.of(), Instant.now(),
                ContractSignalType.STRONG_LONG, "AUTO_TRADE_ALLOWED", "LIMIT", BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.valueOf(90),
                new ContractScoreBreakdown(90, 90, 90, 90, 90, 90, 90),
                new ContractKlineAnalysis("UP", "UP", "UP", "BULLISH", "STRENGTHENING",
                        BigDecimal.valueOf(60), BigDecimal.valueOf(30), "TREND", "READY",
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, true, candle),
                ContractNewsRiskAnalysis.low()
        );
    }

    @Test
    void successfulSlowScanStillRunsAutoTrade() {
        AgentProperties properties = new AgentProperties();
        properties.timeout().setScanRoundTimeoutSeconds(-1);
        ContractAnalysisService analysisService = mock(ContractAnalysisService.class);
        AutoTradeService autoTradeService = mock(AutoTradeService.class);
        when(analysisService.refreshScan(org.mockito.ArgumentMatchers.any()))
                .thenReturn(completedScan(List.of()));
        when(autoTradeService.evaluateAndExecute(List.of())).thenReturn(new AutoTradeResult(
                "SKIPPED", null, null, null, null, null, null, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, "no_candidates", Instant.now()
        ));

        ScanTaskLog result = new ContractMarketScanTask(
                analysisService,
                autoTradeService,
                properties
        ).runOnce();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.message()).contains("SCAN_SLOW");
        verify(autoTradeService).evaluateAndExecute(List.of());
    }

    @Test
    void nonCompletedScanStatusesSkipAutoTrade() {
        AutoTradeService autoTradeService = mock(AutoTradeService.class);
        ContractAnalysisService analysisService = mock(ContractAnalysisService.class);
        ContractMarketScanTask task = new ContractMarketScanTask(
                analysisService, autoTradeService, new AgentProperties());

        for (ScanRunStatus status : List.of(
                ScanRunStatus.PARTIAL_TIMEOUT,
                ScanRunStatus.FAILED,
                ScanRunStatus.SCAN_STUCK,
                ScanRunStatus.UNKNOWN)) {
            ScanRunReport report = new ScanRunReport(
                    "scan-" + status.name(), status, Instant.now(), Instant.now(), 1,
                    status.name(), 0, 0, 0, 0, 0, 0, 0, null);
            when(analysisService.refreshScan(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new ContractScanResult(List.of(), report));
            ScanTaskLog log = task.runOnce();
            assertThat(log.status()).isEqualTo(status.name());
        }
        verify(autoTradeService, never()).evaluateAndExecute(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void missingScanReportSkipsAutoTrade() {
        ContractAnalysisService analysisService = mock(ContractAnalysisService.class);
        AutoTradeService autoTradeService = mock(AutoTradeService.class);
        when(analysisService.refreshScan(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ContractScanResult(List.of(sampleCandidate(Instant.now())), null));
        ContractMarketScanTask task = new ContractMarketScanTask(
                analysisService, autoTradeService, new AgentProperties());

        ScanTaskLog log = task.runOnce();

        assertThat(log.status()).isEqualTo("UNKNOWN");
        assertThat(log.message()).contains("SCAN_REPORT_MISSING");
        verify(autoTradeService, never()).evaluateAndExecute(org.mockito.ArgumentMatchers.anyList());
    }

    private static ContractCandidate sampleCandidate(Instant candle) {
        return candidate(candle);
    }

    @Test
    void skipsSecondScanWhenPreviousScanIsStillRunning() throws Exception {
        BlockingContractAnalysisService analysisService = new BlockingContractAnalysisService();
        ContractMarketScanTask task = new ContractMarketScanTask(
                analysisService,
                autoTradeService(),
                new AgentProperties()
        );
        var executor = Executors.newSingleThreadExecutor();

        executor.submit(task::runOnce);
        assertThat(analysisService.entered.await(2, TimeUnit.SECONDS)).isTrue();

        ScanTaskLog skipped = task.runOnce();

        assertThat(skipped.status()).isEqualTo("SKIPPED");
        assertThat(skipped.message()).contains("SCAN_ALREADY_RUNNING");
        analysisService.release.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    private static AutoTradeService autoTradeService() {
        return new AutoTradeService(
                new AgentProperties(),
                new SystemControlService(new TradingProperties("SEMI_AUTO", true, true, 120, false)),
                new TradeOrderRecordService(null, null),
                new AutoTradeRecordService(null),
                new TradePlanService(null),
                new PendingOrderService(120),
                null,
                new AccountSnapshotService(null) {
                    @Override
                    public AccountSummary summary() {
                        return new AccountSummary(BigDecimal.ZERO, BigDecimal.ZERO, "TEST", "");
                    }
                },
                new PositionSnapshotService(null) {
                    @Override
                    public List<com.example.quant.account.dto.PositionSummary> positions() {
                        return List.of();
                    }
                }
        );
    }

    private static final class BlockingContractAnalysisService extends ContractAnalysisService {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingContractAnalysisService() {
            super(null, null, null);
        }

        @Override
        public ContractScanResult refreshScan(String scanId) {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return completedScan(List.of());
        }
    }

    private static ContractScanResult completedScan(List<ContractCandidate> candidates) {
        return completedScan(candidates, Map.of());
    }

    private static ContractScanResult completedScan(List<ContractCandidate> candidates,
                                                    Map<String, V2SelectionScoreResult> v2Scores) {
        ScanRunReport report = new ScanRunReport(
                "test-scan", ScanRunStatus.COMPLETED, Instant.now(), Instant.now(), 1,
                "completed", candidates.size(), candidates.size(), 30,
                candidates.size(), candidates.size(), 0, 0, null);
        return new ContractScanResult(candidates, report, v2Scores);
    }
}

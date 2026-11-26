package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.agent.execution.AutoTradeService;
import com.example.quant.agent.market.MarketEnvironmentService;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.scan.ScanContext;
import com.example.quant.agent.scan.ScanRunReport;
import com.example.quant.agent.scan.ScanRunStatus;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.filter.CirculationFilter;
import com.example.quant.crypto.filter.LiquidityFilter;
import com.example.quant.crypto.filter.MarketCapFilter;
import com.example.quant.crypto.filter.PriceRangeFilter;
import com.example.quant.crypto.filter.UniverseHardFilter;
import com.example.quant.crypto.filter.VolumeMcapFilter;
import com.example.quant.crypto.provider.MarketCapProvider;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import com.example.quant.market.MarketType;
import com.example.quant.task.ScanTaskLog;
import com.example.quant.news.NewsSearchService;
import com.example.quant.news.dto.NewsItem;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxInstrumentRulesService;
import com.example.quant.system.SystemControlService;
import com.example.quant.task.ContractMarketScanTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OkxContractScannerScanControlTest {

    private static final BigDecimal PRICE = new BigDecimal("1.5");

    @Test
    void hardFilterPassed100OnlyDeepAnalyzesConfiguredLimit() {
        StubProvider provider = new StubProvider();
        List<Map<String, Object>> tickers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String base = "PASS" + i;
            provider.snapshot(base, qualitySnapshot(base));
            tickers.add(ticker(base + "-USDT-SWAP", 1_000_000_000L - i));
        }
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(30);
        OkxContractScanner scanner = fixture(provider, tickers, props).scanner;

        scanner.scan(ScanContext.unbounded("limit-test"));

        ScanRunReport report = scanner.lastScanReport();
        assertThat(report.hardFilterPassed()).isEqualTo(100);
        assertThat(report.deepTargetLimit()).isEqualTo(30);
        assertThat(report.deepAnalysisStarted()).isLessThanOrEqualTo(30);
        assertThat(report.status()).isEqualTo(ScanRunStatus.COMPLETED);
    }

    @Test
    void deepTargetsAreSelectedByTurnoverDescending() {
        StubProvider provider = new StubProvider();
        provider.snapshot("LOW", qualitySnapshot("LOW"));
        provider.snapshot("HIGH", qualitySnapshot("HIGH"));
        provider.snapshot("MID", qualitySnapshot("MID"));
        List<Map<String, Object>> tickers = List.of(
                ticker("LOW-USDT-SWAP", 100_000_000L),
                ticker("HIGH-USDT-SWAP", 900_000_000L),
                ticker("MID-USDT-SWAP", 500_000_000L)
        );
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(1);
        OkxContractScanner scanner = fixture(provider, tickers, props).scanner;

        scanner.scan(ScanContext.unbounded("volume-order"));

        ScanRunReport report = scanner.lastScanReport();
        assertThat(report.deepAnalysisStarted()).isEqualTo(1);
        assertThat(scanner.lastDiagnostics().deepAnalysisTargetCount()).isEqualTo(1);
    }

    @Test
    void thinAndMissingBooksCannotClaimTheOnlyDeepAnalysisSlot() {
        var provider = new StubProvider();
        for (String base : List.of("THIN", "MISSING", "LIQUID")) provider.snapshot(base, qualitySnapshot(base));
        var rows = List.of(ticker("THIN-USDT-SWAP", 900_000_000),
                ticker("MISSING-USDT-SWAP", 800_000_000), ticker("LIQUID-USDT-SWAP", 100_000_000));
        var deepRequestsForRejected = new AtomicInteger();
        var client = new FakeTickerClient(rows, false, 0) {
            @Override
            public JsonNode publicGet(String path) {
                if (path.startsWith("/api/v5/market/books") && path.contains("MISSING-USDT")) {
                    throw new IllegalStateException("book unavailable");
                }
                if (path.startsWith("/api/v5/market/books") && path.contains("THIN-USDT")) {
                    return new ObjectMapper().valueToTree(Map.of("data", List.of(Map.of(
                            "bids", List.of(List.of("1.4999", "6")), "asks", List.of(List.of("1.5001", "9000"))))));
                }
                if (path.contains("/candles") && (path.contains("THIN-USDT") || path.contains("MISSING-USDT"))) {
                    deepRequestsForRejected.incrementAndGet();
                }
                return super.publicGet(path);
            }
        };
        var props = defaultProps();
        props.selection().setDeepAnalysisLimit(1);
        var scanner = fixtureWithClient(provider, client, props).scanner;
        var result = scanner.scan();

        assertThat(result).extracting(c -> c.instId()).containsExactly("LIQUID-USDT-SWAP");
        assertThat(scanner.lastScanReport().hardFilterPassed()).isEqualTo(1);
        assertThat(scanner.lastDiagnostics().funnel().stages())
                .containsEntry("before_order_book", 3).containsEntry("after_order_book", 1)
                .containsEntry("order_book_unavailable", 1);
        assertThat(deepRequestsForRejected.get()).isZero();
    }

    @Test
    void deadlineDuringBookScreeningDoesNotStartDeepAnalysis() {
        var provider = new StubProvider();
        provider.snapshot("SLOW", qualitySnapshot("SLOW"));
        var client = new FakeTickerClient(List.of(ticker("SLOW-USDT-SWAP", 900_000_000)), false, 0) {
            @Override
            public JsonNode publicGet(String path, Duration timeout) {
                if (path.startsWith("/api/v5/market/books")) sleepBeyondTimeout(timeout);
                return super.publicGet(path, timeout);
            }
        };
        var scanner = fixtureWithClient(provider, client, defaultProps()).scanner;
        Instant start = Instant.now();
        var ctx = ScanContext.forTest("book-deadline", start, start.plusMillis(150));
        assertThat(scanner.scan(ctx)).isEmpty();
        assertThat(ctx.status()).isEqualTo(ScanRunStatus.PARTIAL_TIMEOUT);
        assertThat(ctx.currentStage()).isEqualTo("order_book_prefilter");
        assertThat(ctx.deepAnalysisStarted()).isZero();
    }

    @Test
    void providerTimeoutDoesNotFailEntireScan() {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        AgentProperties props = defaultProps();
        props.selection().providers().derivatives().setOpenInterestEnabled(true);
        CountingOiClient client = new CountingOiClient(List.of(ticker("A-USDT-SWAP", 500_000_000L)), true);
        OkxContractScanner scanner = fixtureWithClient(provider, client, props).scanner;

        List<ContractCandidate> out = scanner.scan(ScanContext.unbounded("oi-timeout"));

        assertThat(out).isNotEmpty();
        assertThat(scanner.lastScanReport().status()).isEqualTo(ScanRunStatus.COMPLETED);
        assertThat(scanner.lastScanReport().providerTimeoutCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void deepAnalysisUsesBoundedConcurrency() {
        StubProvider provider = new StubProvider();
        List<Map<String, Object>> tickers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String base = "C" + i;
            provider.snapshot(base, qualitySnapshot(base));
            tickers.add(ticker(base + "-USDT-SWAP", 900_000_000L - i));
        }
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(8);
        props.selection().setDeepAnalysisConcurrency(4);
        ConcurrencyTrackingClient client = new ConcurrencyTrackingClient(tickers);
        OkxContractScanner scanner = fixtureWithClient(provider, client, props).scanner;

        scanner.scan(ScanContext.unbounded("concurrency-test"));

        assertThat(client.maxInFlight()).isGreaterThan(1);
        assertThat(client.maxInFlight()).isLessThanOrEqualTo(4);
    }

    @Test
    void stuckProviderReturnsPartialTimeoutWithinDeadlineGrace() {
        StubProvider provider = new StubProvider();
        List<Map<String, Object>> tickers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String base = "S" + i;
            provider.snapshot(base, qualitySnapshot(base));
            tickers.add(ticker(base + "-USDT-SWAP", 700_000_000L - i));
        }
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(4);
        props.selection().setDeepAnalysisConcurrency(4);
        BlockingCandleClient client = new BlockingCandleClient(tickers);
        OkxContractScanner scanner = fixtureWithClient(provider, client, props).scanner;
        Instant startedAt = Instant.now();
        ScanContext ctx = ScanContext.forTest("stuck-test", startedAt, startedAt.plusMillis(250));

        long before = System.currentTimeMillis();
        List<ContractCandidate> out = scanner.scan(ctx);
        long elapsed = System.currentTimeMillis() - before;

        assertThat(scanner.lastScanReport().status()).isEqualTo(ScanRunStatus.PARTIAL_TIMEOUT);
        assertThat(out).isEmpty();
        assertThat(elapsed).isLessThan(2_500L);
    }

    @Test
    void partialRoundCountsFinishedSignalBehindBlockedFirstTargetWithoutDeliveringOrders() {
        StubProvider provider = new StubProvider();
        provider.snapshot("S0", qualitySnapshot("S0"));
        provider.snapshot("A", qualitySnapshot("A"));
        var tickers = List.of(ticker("S0-USDT-SWAP", 800_000_000L), ticker("A-USDT-SWAP", 700_000_000L));
        var scanner = fixtureWithClient(provider, new BlockingCandleClient(tickers), defaultProps()).scanner;
        Instant start = Instant.now();
        assertThat(scanner.scan(ScanContext.forTest("slow-first", start, start.plusSeconds(2)))).isEmpty();
        assertThat(scanner.lastScanReport().status()).isEqualTo(ScanRunStatus.PARTIAL_TIMEOUT);
        assertThat(scanner.lastDiagnostics().funnel().stages()).containsEntry("analyzed_signals", 1);
    }

    @Test
    void deadlineStopsFurtherDeepAnalysis() {
        StubProvider provider = new StubProvider();
        List<Map<String, Object>> tickers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String base = "P" + i;
            provider.snapshot(base, qualitySnapshot(base));
            tickers.add(ticker(base + "-USDT-SWAP", 800_000_000L - i));
        }
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(10);
        OkxContractScanner scanner = fixture(provider, tickers, props).scanner;
        Instant startedAt = Instant.now().minusSeconds(30);
        ScanContext ctx = ScanContext.forTest(
                "deadline-test",
                startedAt,
                startedAt.plusSeconds(5));
        long before = System.currentTimeMillis();

        List<ContractCandidate> out = scanner.scan(ctx);
        long elapsed = System.currentTimeMillis() - before;

        ScanRunReport report = scanner.lastScanReport();
        assertThat(report.status()).isEqualTo(ScanRunStatus.PARTIAL_TIMEOUT);
        assertThat(report.deepAnalysisStarted()).isZero();
        assertThat(out).isEmpty();
        assertThat(elapsed).isLessThan(2_500L);
    }

    @Test
    void expiredScanDoesNotBlockOnMarketEnvironmentFetch() {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        List<Map<String, Object>> tickers = List.of(ticker("A-USDT-SWAP", 800_000_000L));
        BlockingEnvCandleClient client = new BlockingEnvCandleClient(tickers);
        OkxContractScanner scanner = fixtureWithClient(provider, client, defaultProps()).scanner;
        Instant startedAt = Instant.now().minusMillis(500);
        ScanContext ctx = ScanContext.forTest("env-deadline", startedAt, startedAt.plusMillis(50));
        long before = System.currentTimeMillis();

        List<ContractCandidate> out = scanner.scan(ctx);
        long elapsed = System.currentTimeMillis() - before;

        assertThat(scanner.lastScanReport().status()).isEqualTo(ScanRunStatus.PARTIAL_TIMEOUT);
        assertThat(out).isEmpty();
        assertThat(elapsed).isLessThan(2_500L);
        assertThat(client.envCandleCalls()).isZero();
    }

    @Test
    void expiredScanDoesNotWaitForBlockingTickerFetch() {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        BlockingTickerClient client = new BlockingTickerClient(List.of(ticker("A-USDT-SWAP", 800_000_000L)));
        OkxContractScanner scanner = fixtureWithClient(provider, client, defaultProps()).scanner;
        Instant startedAt = Instant.now();
        ScanContext ctx = ScanContext.forTest("ticker-deadline", startedAt, startedAt.plusMillis(100));
        long before = System.currentTimeMillis();

        List<ContractCandidate> out = scanner.scan(ctx);
        long elapsed = System.currentTimeMillis() - before;

        assertThat(scanner.lastScanReport().status()).isNotEqualTo(ScanRunStatus.COMPLETED);
        assertThat(out).isEmpty();
        assertThat(elapsed).isLessThan(2_500L);
    }

    @Test
    void expiredScanDoesNotWaitForBlockingMarketCapProvider() {
        StubProvider provider = new BlockingStubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        List<Map<String, Object>> tickers = List.of(ticker("A-USDT-SWAP", 800_000_000L));
        OkxContractScanner scanner = fixture(provider, tickers, defaultProps()).scanner;
        Instant startedAt = Instant.now().minusMillis(200);
        ScanContext ctx = ScanContext.forTest("mcap-deadline", startedAt, startedAt.plusMillis(50));
        long before = System.currentTimeMillis();

        List<ContractCandidate> out = scanner.scan(ctx);
        long elapsed = System.currentTimeMillis() - before;

        assertThat(scanner.lastScanReport().status()).isEqualTo(ScanRunStatus.PARTIAL_TIMEOUT);
        assertThat(out).isEmpty();
        assertThat(elapsed).isLessThan(2_500L);
    }

    @Test
    void expiredScanDoesNotWaitForBlockingFundingProvider() {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        List<Map<String, Object>> tickers = List.of(ticker("A-USDT-SWAP", 800_000_000L));
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(1);
        props.selection().providers().derivatives().setFundingEnabled(true);
        BlockingFundingClient client = new BlockingFundingClient(tickers);
        OkxContractScanner scanner = fixtureWithClient(provider, client, props).scanner;
        Instant startedAt = Instant.now();
        ScanContext ctx = ScanContext.forTest("funding-deadline", startedAt, startedAt.plusMillis(150));
        long before = System.currentTimeMillis();

        List<ContractCandidate> out = scanner.scan(ctx);
        long elapsed = System.currentTimeMillis() - before;

        assertThat(scanner.lastScanReport().status()).isNotEqualTo(ScanRunStatus.COMPLETED);
        assertThat(out).isEmpty();
        assertThat(elapsed).isLessThan(2_500L);
    }

    @Test
    void expiredScanDoesNotWaitForBlockingRelativeStrengthProvider() {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        List<Map<String, Object>> tickers = List.of(ticker("A-USDT-SWAP", 800_000_000L));
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(1);
        props.selection().providers().setRelativeStrengthEnabled(true);
        BlockingRelativeStrengthClient client = new BlockingRelativeStrengthClient(tickers);
        OkxContractScanner scanner = fixtureWithClient(provider, client, props).scanner;
        Instant startedAt = Instant.now();
        ScanContext ctx = ScanContext.forTest("rs-deadline", startedAt, startedAt.plusMillis(150));
        long before = System.currentTimeMillis();

        List<ContractCandidate> out = scanner.scan(ctx);
        long elapsed = System.currentTimeMillis() - before;

        assertThat(scanner.lastScanReport().status()).isNotEqualTo(ScanRunStatus.COMPLETED);
        assertThat(out).isEmpty();
        assertThat(elapsed).isLessThan(2_500L);
    }

    @Test
    void expiredScanDoesNotWaitForBlockingAthBreakoutProvider() {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        List<Map<String, Object>> tickers = List.of(ticker("A-USDT-SWAP", 800_000_000L));
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(1);
        props.selection().providers().setAthBreakoutEnabled(true);
        BlockingAthBreakoutClient client = new BlockingAthBreakoutClient(tickers);
        OkxContractScanner scanner = fixtureWithClient(provider, client, props).scanner;
        Instant startedAt = Instant.now();
        ScanContext ctx = ScanContext.forTest("ath-deadline", startedAt, startedAt.plusMillis(150));
        long before = System.currentTimeMillis();

        List<ContractCandidate> out = scanner.scan(ctx);
        long elapsed = System.currentTimeMillis() - before;

        assertThat(scanner.lastScanReport().status()).isNotEqualTo(ScanRunStatus.COMPLETED);
        assertThat(out).isEmpty();
        assertThat(elapsed).isLessThan(2_500L);
    }

    @Test
    void interruptIgnoringFundingProviderDoesNotPolluteScanAfterReturn() throws Exception {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(1);
        props.selection().providers().derivatives().setFundingEnabled(true);
        InterruptIgnoringFundingClient client = new InterruptIgnoringFundingClient(
                List.of(ticker("A-USDT-SWAP", 800_000_000L)));
        OkxContractScanner scanner = fixtureWithClient(provider, client, props).scanner;
        ScanContext ctx = ScanContext.forTest("funding-interrupt-ignore", Instant.now(), Instant.now().plusMillis(300));

        List<ContractCandidate> out = scanner.scan(ctx);

        assertThat(scanner.lastScanReport().status()).isEqualTo(ScanRunStatus.PARTIAL_TIMEOUT);
        assertThat(out).isEmpty();
        Thread.sleep(200);
        assertThat(client.lateWriteAttempted()).isFalse();
    }

    @Test
    void fundingProviderTimeoutDoesNotFailEntireScanWithoutDeadline() {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(1);
        props.selection().providers().derivatives().setFundingEnabled(true);
        CountingFundingClient client = new CountingFundingClient(List.of(ticker("A-USDT-SWAP", 500_000_000L)), true);
        OkxContractScanner scanner = fixtureWithClient(provider, client, props).scanner;

        List<ContractCandidate> out = scanner.scan(ScanContext.unbounded("funding-timeout"));

        assertThat(out).isNotEmpty();
        assertThat(scanner.lastScanReport().status()).isEqualTo(ScanRunStatus.COMPLETED);
        assertThat(scanner.lastV2ScoreResults()).isNotEmpty();
    }

    @Test
    void relativeStrengthProviderTimeoutDoesNotFailEntireScanWithoutDeadline() {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(1);
        props.selection().providers().setRelativeStrengthEnabled(true);
        CountingRelativeStrengthClient client = new CountingRelativeStrengthClient(
                List.of(ticker("A-USDT-SWAP", 500_000_000L)), true);
        OkxContractScanner scanner = fixtureWithClient(provider, client, props).scanner;

        List<ContractCandidate> out = scanner.scan(ScanContext.unbounded("rs-timeout"));

        assertThat(out).isNotEmpty();
        assertThat(scanner.lastScanReport().status()).isEqualTo(ScanRunStatus.COMPLETED);
        assertThat(scanner.lastV2ScoreResults()).containsKey("A-USDT-SWAP");
    }

    @Test
    void athBreakoutProviderTimeoutDoesNotFailEntireScanWithoutDeadline() {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(1);
        props.selection().providers().setAthBreakoutEnabled(true);
        CountingAthBreakoutClient client = new CountingAthBreakoutClient(
                List.of(ticker("A-USDT-SWAP", 500_000_000L)), true);
        OkxContractScanner scanner = fixtureWithClient(provider, client, props).scanner;

        List<ContractCandidate> out = scanner.scan(ScanContext.unbounded("ath-timeout"));

        assertThat(out).isNotEmpty();
        assertThat(scanner.lastScanReport().status()).isEqualTo(ScanRunStatus.COMPLETED);
        assertThat(scanner.lastV2ScoreResults()).containsKey("A-USDT-SWAP");
    }

    @Test
    void expiredScanDoesNotWaitForBlockingOpenInterestProvider() {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        List<Map<String, Object>> tickers = List.of(ticker("A-USDT-SWAP", 800_000_000L));
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(1);
        props.selection().providers().derivatives().setOpenInterestEnabled(true);
        BlockingOiClient client = new BlockingOiClient(tickers);
        OkxContractScanner scanner = fixtureWithClient(provider, client, props).scanner;
        Instant startedAt = Instant.now();
        ScanContext ctx = ScanContext.forTest("oi-deadline", startedAt, startedAt.plusMillis(150));
        long before = System.currentTimeMillis();

        List<ContractCandidate> out = scanner.scan(ctx);
        long elapsed = System.currentTimeMillis() - before;

        assertThat(scanner.lastScanReport().status()).isNotEqualTo(ScanRunStatus.COMPLETED);
        assertThat(out).isEmpty();
        assertThat(elapsed).isLessThan(2_500L);
    }

    @Test
    void alreadyExpiredScanDoesNotIssueNetworkRequests() {
        StubProvider provider = new StubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        RequestTrackingClient client = new RequestTrackingClient(List.of(ticker("A-USDT-SWAP", 800_000_000L)));
        OkxContractScanner scanner = fixtureWithClient(provider, client, defaultProps()).scanner;
        Instant startedAt = Instant.now().minusSeconds(10);
        ScanContext ctx = ScanContext.forTest("pre-expired", startedAt, startedAt.plusMillis(50));
        long before = System.currentTimeMillis();

        List<ContractCandidate> out = scanner.scan(ctx);
        long elapsed = System.currentTimeMillis() - before;

        assertThat(scanner.lastScanReport().status()).isEqualTo(ScanRunStatus.PARTIAL_TIMEOUT);
        assertThat(out).isEmpty();
        assertThat(elapsed).isLessThan(2_500L);
        assertThat(client.tickerCalls()).isZero();
        assertThat(client.envCandleCalls()).isZero();
    }

    @Test
    void interruptIgnoringProviderDoesNotPolluteScanAfterReturn() throws Exception {
        StubProvider provider = new InterruptIgnoringStubProvider();
        provider.snapshot("A", qualitySnapshot("A"));
        List<Map<String, Object>> tickers = List.of(ticker("A-USDT-SWAP", 800_000_000L));
        AgentProperties props = defaultProps();
        props.selection().setDeepAnalysisLimit(1);
        OkxContractScanner scanner = fixture(provider, tickers, props).scanner;
        ScanContext ctx = ScanContext.forTest(
                "interrupt-ignore",
                Instant.now(),
                Instant.now().plusMillis(300));

        List<ContractCandidate> out = scanner.scan(ctx);

        assertThat(scanner.lastScanReport().status()).isEqualTo(ScanRunStatus.PARTIAL_TIMEOUT);
        assertThat(out).isEmpty();
        Thread.sleep(200);
        assertThat(provider.lateWriteAttempted()).isFalse();
    }

    @Test
    void partialTimeoutSkipsAutoTrade() {
        ContractAnalysisService analysis = mock(ContractAnalysisService.class);
        AutoTradeService autoTrade = mock(AutoTradeService.class);
        ScanRunReport report = new ScanRunReport(
                "scan-1", ScanRunStatus.PARTIAL_TIMEOUT, Instant.now(), Instant.now(), 100,
                "deep_analysis", 100, 50, 30, 5, 5, 1, 25, null);
        when(analysis.refreshScan(any())).thenReturn(new ContractScanResult(List.of(), report));

        ContractMarketScanTask task = new ContractMarketScanTask(analysis, autoTrade, new AgentProperties());
        ScanTaskLog log = task.runOnce();

        assertThat(log.status()).isEqualTo("PARTIAL_TIMEOUT");
        verify(autoTrade, never()).evaluateAndExecute(Mockito.anyList());
    }

    @Test
    void runtimeExceptionReleasesScanLock() throws Exception {
        ContractAnalysisService analysis = mock(ContractAnalysisService.class);
        when(analysis.refreshScan(any())).thenThrow(new IllegalStateException("boom"));
        ContractMarketScanTask task = new ContractMarketScanTask(analysis, autoTradeService(), new AgentProperties());

        task.runOnce();
        ScanTaskLog second = task.runOnce();

        assertThat(second.status()).isNotEqualTo("SKIPPED");
        verify(analysis, times(2)).refreshScan(any());
    }

    @Test
    void secondScheduledTriggerSkipsWhileFirstScanStillRunning() throws Exception {
        BlockingContractAnalysisService analysis = new BlockingContractAnalysisService();
        ContractMarketScanTask task = new ContractMarketScanTask(
                analysis, autoTradeService(), new AgentProperties());
        var executor = Executors.newSingleThreadExecutor();

        executor.submit(task::runOnce);
        assertThat(analysis.entered.await(2, TimeUnit.SECONDS)).isTrue();
        ScanTaskLog skipped = task.runOnce();

        assertThat(skipped.status()).isEqualTo("SKIPPED");
        assertThat(skipped.message()).contains("SCAN_ALREADY_RUNNING");
        analysis.release.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void bulkInstrumentRulesCacheAvoidsPerContractInstrumentRequests() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OkxRestClient client = mock(OkxRestClient.class);
        when(client.publicGet("/api/v5/public/instruments?instType=SWAP"))
                .thenReturn(mapper.readTree("""
                        {"code":"0","data":[
                          {"instId":"BTC-USDT-SWAP","instType":"SWAP","ctVal":"0.01","ctValCcy":"BTC","lotSz":"1","minSz":"1","tickSz":"0.1"},
                          {"instId":"ETH-USDT-SWAP","instType":"SWAP","ctVal":"0.1","ctValCcy":"ETH","lotSz":"1","minSz":"1","tickSz":"0.01"}
                        ]}
                        """));
        OkxInstrumentRulesService service = new OkxInstrumentRulesService(client);

        service.rules("BTC-USDT-SWAP");
        service.rules("ETH-USDT-SWAP");

        verify(client, times(1)).publicGet("/api/v5/public/instruments?instType=SWAP");
        verify(client, never()).publicGet("/api/v5/public/instruments?instType=SWAP&instId=BTC-USDT-SWAP");
    }

    private static AgentProperties defaultProps() {
        AgentProperties props = new AgentProperties();
        props.setTopCandidateLimit(10);
        props.selection().setDeepAnalysisLimit(30);
        return props;
    }

    private static MarketCapSnapshot qualitySnapshot(String base) {
        return new MarketCapSnapshot(base, new BigDecimal("20000000"),
                new BigDecimal("25000000"),
                new BigDecimal("15000000"), new BigDecimal("25000000"),
                null, Instant.now(), "test");
    }

    private static Map<String, Object> ticker(String instId, long volCcy24h) {
        Map<String, Object> t = new HashMap<>();
        t.put("instId", instId);
        t.put("last", PRICE.toPlainString());
        t.put("open24h", PRICE.toPlainString());
        t.put("high24h", PRICE.add(new BigDecimal("0.1")).toPlainString());
        t.put("low24h", PRICE.subtract(new BigDecimal("0.1")).toPlainString());
        BigDecimal baseVolume = volCcy24h == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(volCcy24h).divide(PRICE, 8, java.math.RoundingMode.HALF_UP);
        t.put("volCcy24h", baseVolume.stripTrailingZeros().toPlainString());
        t.put("vol24h", "0");
        t.put("bidPx", PRICE.subtract(new BigDecimal("0.0001")).toPlainString());
        t.put("askPx", PRICE.add(new BigDecimal("0.0001")).toPlainString());
        return t;
    }

    private static OkxInstrumentRules testRules(String instId) {
        return new OkxInstrumentRules(instId, BigDecimal.ONE,
                instId.substring(0, instId.indexOf('-')),
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.01"));
    }

    private static V2Fixture fixture(StubProvider provider, List<Map<String, Object>> tickers, AgentProperties props) {
        return V2Fixture.builder().provider(provider).tickers(tickers).agentProperties(props).build();
    }

    private static V2Fixture fixtureWithClient(StubProvider provider, FakeTickerClient client, AgentProperties props) {
        return V2Fixture.builder().provider(provider).client(client).agentProperties(props).build();
    }

    private static AutoTradeService autoTradeService() {
        return new AutoTradeService(
                new AgentProperties(),
                new SystemControlService(new com.example.quant.config.TradingProperties("SEMI_AUTO", true, true, 120, false)),
                new com.example.quant.agent.execution.TradeOrderRecordService(null, null),
                new com.example.quant.agent.execution.AutoTradeRecordService(null),
                new com.example.quant.tradeplan.TradePlanService(null),
                new com.example.quant.order.PendingOrderService(120),
                null,
                new com.example.quant.account.AccountSnapshotService(null) {
                    @Override
                    public com.example.quant.account.dto.AccountSummary summary() {
                        return new com.example.quant.account.dto.AccountSummary(
                                BigDecimal.ZERO, BigDecimal.ZERO, "TEST", "");
                    }
                },
                new com.example.quant.account.PositionSnapshotService(null) {
                    @Override
                    public List<com.example.quant.account.dto.PositionSummary> positions() {
                        return List.of();
                    }
                }
        );
    }

    private static class StubProvider implements MarketCapProvider {
        private final Map<String, MarketCapSnapshot> data = new HashMap<>();

        void snapshot(String base, MarketCapSnapshot snap) {
            data.put(base, snap);
        }

        boolean lateWriteAttempted() {
            return false;
        }

        @Override
        public ProviderResult<MarketCapSnapshot> fetch(String baseSymbol) {
            MarketCapSnapshot snapshot = data.get(baseSymbol);
            if (snapshot == null) {
                return ProviderResult.unavailable("missing " + baseSymbol);
            }
            return ProviderResult.fresh(snapshot, "stub");
        }
    }

    private static final class BlockingStubProvider extends StubProvider {
        @Override
        public java.util.Map<String, ProviderResult<MarketCapSnapshot>> fetchBatch(
                java.util.List<String> baseSymbols,
                java.time.Duration timeout
        ) {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return super.fetchBatch(baseSymbols, timeout);
        }
    }

    private static final class InterruptIgnoringStubProvider extends StubProvider {
        private final AtomicInteger lateWrites = new AtomicInteger();

        @Override
        public java.util.Map<String, ProviderResult<MarketCapSnapshot>> fetchBatch(
                java.util.List<String> baseSymbols,
                java.time.Duration timeout
        ) {
            long end = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < end) {
                if (Thread.interrupted()) {
                    Thread.interrupted();
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    // keep running to simulate non-interruptible provider
                }
            }
            lateWrites.incrementAndGet();
            return super.fetchBatch(baseSymbols, timeout);
        }

        @Override
        boolean lateWriteAttempted() {
            return lateWrites.get() > 0;
        }
    }

    private static class BlockingEnvCandleClient extends FakeTickerClient {
        private final AtomicInteger envCalls = new AtomicInteger();

        BlockingEnvCandleClient(List<Map<String, Object>> tickers) {
            super(tickers, false, 0);
        }

        int envCandleCalls() {
            return envCalls.get();
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            if (shouldBlockEnvCandles(requestPath)) {
                envCalls.incrementAndGet();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
            return super.publicGet(requestPath);
        }

        private static boolean shouldBlockEnvCandles(String requestPath) {
            if (!requestPath.startsWith("/api/v5/market/candles")) {
                return false;
            }
            String instId = extractInstId(requestPath);
            return "BTC-USDT-SWAP".equals(instId) || "ETH-USDT-SWAP".equals(instId);
        }
    }

    private static void sleepBeyondTimeout(Duration timeout) {
        long sleepMs = timeout == null ? 30_000 : timeout.toMillis() + 200;
        try {
            Thread.sleep(Math.max(1, sleepMs));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OKX request interrupted", ex);
        }
    }

    private static class BlockingFundingClient extends FakeTickerClient {
        BlockingFundingClient(List<Map<String, Object>> tickers) {
            super(tickers, false, 0);
        }

        @Override
        public JsonNode publicGet(String requestPath, Duration timeout) {
            if (requestPath.contains("funding-rate-history")) {
                sleepBeyondTimeout(timeout);
                throw new IllegalStateException("OKX request timed out");
            }
            return publicGet(requestPath);
        }
    }

    private static class InterruptIgnoringFundingClient extends FakeTickerClient {
        private final AtomicInteger lateWrites = new AtomicInteger();

        InterruptIgnoringFundingClient(List<Map<String, Object>> tickers) {
            super(tickers, false, 0);
        }

        boolean lateWriteAttempted() {
            return lateWrites.get() > 0;
        }

        @Override
        public JsonNode publicGet(String requestPath, Duration timeout) {
            if (requestPath.contains("funding-rate-history")) {
                long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (System.nanoTime() < end) {
                    if (Thread.interrupted()) {
                        Thread.interrupted();
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        // simulate non-interruptible provider
                    }
                }
                lateWrites.incrementAndGet();
            }
            return publicGet(requestPath);
        }
    }

    private static class BlockingRelativeStrengthClient extends FakeTickerClient {
        BlockingRelativeStrengthClient(List<Map<String, Object>> tickers) {
            super(tickers, false, 0);
        }

        @Override
        public JsonNode publicGet(String requestPath, Duration timeout) {
            if (isRelativeStrengthBenchmarkCandles(requestPath)) {
                sleepBeyondTimeout(timeout);
                throw new IllegalStateException("OKX request timed out");
            }
            return publicGet(requestPath);
        }

        private static boolean isRelativeStrengthBenchmarkCandles(String requestPath) {
            if (!requestPath.startsWith("/api/v5/market/candles") || !requestPath.contains("bar=1H")) {
                return false;
            }
            String instId = extractInstId(requestPath);
            return "BTC-USDT-SWAP".equals(instId) || "ETH-USDT-SWAP".equals(instId);
        }
    }

    private static class BlockingAthBreakoutClient extends FakeTickerClient {
        BlockingAthBreakoutClient(List<Map<String, Object>> tickers) {
            super(tickers, false, 0);
        }

        @Override
        public JsonNode publicGet(String requestPath, Duration timeout) {
            if (isAthBreakoutDailyCandles(requestPath)) {
                sleepBeyondTimeout(timeout);
                throw new IllegalStateException("OKX request timed out");
            }
            return publicGet(requestPath);
        }

        private static boolean isAthBreakoutDailyCandles(String requestPath) {
            if (!requestPath.startsWith("/api/v5/market/candles") || !requestPath.contains("bar=1D")) {
                return false;
            }
            String instId = extractInstId(requestPath);
            return instId.endsWith("-USDT-SWAP")
                    && !"BTC-USDT-SWAP".equals(instId)
                    && !"ETH-USDT-SWAP".equals(instId);
        }
    }

    private static class CountingFundingClient extends FakeTickerClient {
        private final boolean failWithTimeout;

        CountingFundingClient(List<Map<String, Object>> tickers, boolean timeout) {
            super(tickers, timeout, 0);
            this.failWithTimeout = timeout;
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            if (requestPath.contains("funding-rate-history") && failWithTimeout) {
                throw new RuntimeException("request timed out");
            }
            return super.publicGet(requestPath);
        }
    }

    private static class CountingRelativeStrengthClient extends FakeTickerClient {
        private final boolean failWithTimeout;

        CountingRelativeStrengthClient(List<Map<String, Object>> tickers, boolean timeout) {
            super(tickers, timeout, 0);
            this.failWithTimeout = timeout;
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            if (failWithTimeout && BlockingRelativeStrengthClient.isRelativeStrengthBenchmarkCandles(requestPath)) {
                throw new RuntimeException("request timed out");
            }
            return super.publicGet(requestPath);
        }
    }

    private static class CountingAthBreakoutClient extends FakeTickerClient {
        private final boolean failWithTimeout;

        CountingAthBreakoutClient(List<Map<String, Object>> tickers, boolean timeout) {
            super(tickers, timeout, 0);
            this.failWithTimeout = timeout;
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            if (failWithTimeout && BlockingAthBreakoutClient.isAthBreakoutDailyCandles(requestPath)) {
                throw new RuntimeException("request timed out");
            }
            return super.publicGet(requestPath);
        }
    }

    private static class BlockingOiClient extends FakeTickerClient {
        BlockingOiClient(List<Map<String, Object>> tickers) {
            super(tickers, false, 0);
        }

        @Override
        public JsonNode publicGet(String requestPath, Duration timeout) {
            if (requestPath.contains("open-interest-history")) {
                long sleepMs = timeout == null ? 30_000 : timeout.toMillis() + 200;
                try {
                    Thread.sleep(Math.max(1, sleepMs));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("OKX request interrupted", ex);
                }
                throw new IllegalStateException("OKX request timed out");
            }
            return publicGet(requestPath);
        }
    }

    private static class RequestTrackingClient extends FakeTickerClient {
        private final AtomicInteger tickerCalls = new AtomicInteger();
        private final AtomicInteger envCandleCalls = new AtomicInteger();

        RequestTrackingClient(List<Map<String, Object>> tickers) {
            super(tickers, false, 0);
        }

        int tickerCalls() {
            return tickerCalls.get();
        }

        int envCandleCalls() {
            return envCandleCalls.get();
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            if (requestPath.startsWith("/api/v5/market/tickers")) {
                tickerCalls.incrementAndGet();
            }
            if (shouldBlockEnvCandles(requestPath)) {
                envCandleCalls.incrementAndGet();
            }
            return super.publicGet(requestPath);
        }

        private static boolean shouldBlockEnvCandles(String requestPath) {
            if (!requestPath.startsWith("/api/v5/market/candles")) {
                return false;
            }
            String instId = extractInstId(requestPath);
            return "BTC-USDT-SWAP".equals(instId) || "ETH-USDT-SWAP".equals(instId);
        }
    }

    private static class BlockingTickerClient extends FakeTickerClient {
        BlockingTickerClient(List<Map<String, Object>> tickers) {
            super(tickers, false, 0);
        }

        @Override
        public JsonNode publicGet(String requestPath, Duration timeout) {
            if (requestPath.startsWith("/api/v5/market/tickers")) {
                long sleepMs = timeout == null ? 30_000 : timeout.toMillis() + 200;
                try {
                    Thread.sleep(Math.max(1, sleepMs));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("OKX request interrupted", ex);
                }
                throw new IllegalStateException("OKX request timed out");
            }
            return publicGet(requestPath);
        }
    }

    private static String extractInstId(String requestPath) {
        int marker = requestPath.indexOf("instId=");
        if (marker < 0) {
            return "";
        }
        String tail = requestPath.substring(marker + "instId=".length());
        int amp = tail.indexOf('&');
        return amp >= 0 ? tail.substring(0, amp) : tail;
    }

    private static class CountingOiClient extends FakeTickerClient {
        private final AtomicInteger oiCalls = new AtomicInteger();
        private final boolean failWithTimeout;

        CountingOiClient(List<Map<String, Object>> tickers, boolean timeout) {
            super(tickers, timeout, 0);
            this.failWithTimeout = timeout;
        }

        int oiCalls() {
            return oiCalls.get();
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            if (requestPath.contains("open-interest-history")) {
                oiCalls.incrementAndGet();
                if (failWithTimeout) {
                    throw new RuntimeException("request timed out");
                }
            }
            return super.publicGet(requestPath);
        }
    }

    private static class ConcurrencyTrackingClient extends FakeTickerClient {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();

        ConcurrencyTrackingClient(List<Map<String, Object>> tickers) {
            super(tickers, false, 80);
        }

        int maxInFlight() {
            return maxInFlight.get();
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            if (requestPath.startsWith("/api/v5/market/candles")) {
                int current = inFlight.incrementAndGet();
                maxInFlight.updateAndGet(max -> Math.max(max, current));
                try {
                    return super.publicGet(requestPath);
                } finally {
                    inFlight.decrementAndGet();
                }
            }
            return super.publicGet(requestPath);
        }
    }

    private static class BlockingCandleClient extends FakeTickerClient {
        BlockingCandleClient(List<Map<String, Object>> tickers) {
            super(tickers, false, 0);
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            if (shouldBlockDeepAnalysisCandles(requestPath)) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
            return super.publicGet(requestPath);
        }

        private static boolean shouldBlockDeepAnalysisCandles(String requestPath) {
            if (!requestPath.startsWith("/api/v5/market/candles")) {
                return false;
            }
            int marker = requestPath.indexOf("instId=");
            if (marker < 0) {
                return false;
            }
            String tail = requestPath.substring(marker + "instId=".length());
            int amp = tail.indexOf('&');
            String instId = amp >= 0 ? tail.substring(0, amp) : tail;
            return instId.startsWith("S") && instId.endsWith("-USDT-SWAP");
        }
    }

    private static class SlowKlineClient extends FakeTickerClient {
        private final long sleepMs;

        SlowKlineClient(List<Map<String, Object>> tickers, long sleepMs) {
            super(tickers, false, sleepMs);
            this.sleepMs = sleepMs;
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            if (requestPath.startsWith("/api/v5/market/candles")) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.publicGet(requestPath);
        }
    }

    private static class FakeTickerClient extends OkxRestClient {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final List<Map<String, Object>> tickers;
        private final boolean timeout;
        private final long candleSleepMs;

        FakeTickerClient(List<Map<String, Object>> tickers, boolean timeout, long candleSleepMs) {
            super(null, null, null, null, null);
            this.tickers = tickers;
            this.timeout = timeout;
            this.candleSleepMs = candleSleepMs;
        }

        @Override
        public JsonNode publicGet(String requestPath, Duration timeout) {
            return publicGet(requestPath);
        }

        @Override
        protected JsonNode publicGetWithoutHttpClient(String requestPath) {
            return publicGet(requestPath);
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            try {
                if (requestPath.startsWith("/api/v5/market/tickers")) {
                    return objectMapper.readTree(toJson(tickers));
                }
                if (requestPath.startsWith("/api/v5/market/candles")) {
                    if (candleSleepMs > 0) {
                        Thread.sleep(candleSleepMs);
                    }
                    return objectMapper.readTree(candlesJson());
                }
                if (requestPath.startsWith("/api/v5/market/books")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"bids":[["1.4999","10000"],["1.48","8000"]],"asks":[["1.5001","10000"],["1.52","8000"]]}]}
                            """);
                }
                if (requestPath.startsWith("/api/v5/public/funding-rate")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"fundingRate":"0.0001"}]}
                            """);
                }
                if (requestPath.startsWith("/api/v5/public/open-interest")) {
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"oiCcy":"500000000"}]}
                            """);
                }
                if (requestPath.contains("open-interest-history")) {
                    if (timeout) {
                        throw new RuntimeException("request timed out");
                    }
                    return objectMapper.readTree("""
                            {"code":"0","data":[{"ts":"1","oi":"100"},{"ts":"2","oi":"110"}]}
                            """);
                }
                return objectMapper.readTree("{\"code\":\"0\",\"data\":[]}");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private static String toJson(List<Map<String, Object>> rows) {
            StringBuilder sb = new StringBuilder("{\"code\":\"0\",\"data\":[");
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                Map<String, Object> row = rows.get(i);
                sb.append("{");
                boolean first = true;
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (!first) {
                        sb.append(",");
                    }
                    sb.append("\"").append(entry.getKey()).append("\":");
                    sb.append("\"").append(entry.getValue()).append("\"");
                    first = false;
                }
                sb.append("}");
            }
            sb.append("]}");
            return sb.toString();
        }

        private static String candlesJson() {
            List<ContractCandle> candles = new ArrayList<>();
            BigDecimal close = BigDecimal.valueOf(1.5);
            for (int i = 0; i < 240; i++) {
                BigDecimal open = close;
                close = close.add(i % 2 == 0 ? new BigDecimal("0.005") : new BigDecimal("-0.005"));
                candles.add(new ContractCandle(
                        Instant.parse("2026-08-09T00:00:00Z").plusSeconds(i * 300L),
                        open,
                        close.max(open).multiply(BigDecimal.valueOf(1.004)),
                        close.min(open).multiply(BigDecimal.valueOf(0.996)),
                        close,
                        BigDecimal.valueOf(1000 + i)));
            }
            List<String> rows = candles.stream()
                    .map(c -> "[\"%d\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"0\",\"0\",\"1\"]".formatted(
                            c.timestamp().toEpochMilli(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                    .toList();
            List<String> reversed = new ArrayList<>(rows);
            Collections.reverse(reversed);
            return "{\"code\":\"0\",\"data\":[" + String.join(",", reversed) + "]}";
        }
    }

    private static final class V2Fixture {
        final OkxContractScanner scanner;

        private V2Fixture(OkxContractScanner scanner) {
            this.scanner = scanner;
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private MarketCapProvider provider;
            private List<Map<String, Object>> tickers = List.of();
            private FakeTickerClient client;
            private AgentProperties agentProperties = defaultProps();

            Builder provider(MarketCapProvider provider) {
                this.provider = provider;
                return this;
            }

            Builder tickers(List<Map<String, Object>> tickers) {
                this.tickers = tickers;
                return this;
            }

            Builder client(FakeTickerClient client) {
                this.client = client;
                return this;
            }

            Builder agentProperties(AgentProperties props) {
                this.agentProperties = props;
                return this;
            }

            V2Fixture build() {
                FakeTickerClient restClient = client == null ? new FakeTickerClient(tickers, false, 0) : client;
                OkxMarketService marketService = new OkxMarketService(restClient);
                OkxContractScanner scanner = new OkxContractScanner(
                        restClient,
                        marketService,
                        new ContractSignalAnalyzer(),
                        new ContractNewsAnalysisService(new NewsSearchService(
                                (marketType, symbol, name, keywords, maxResults, lookbackHours) -> List.of(
                                        new NewsItem("neutral market update", "test", "https://example.test",
                                                Instant.now(), "", "NEUTRAL", 0, 10, List.of(symbol), List.of(),
                                                MarketType.OKX_SWAP))),
                                agentProperties),
                        new OrderBookLiquidityService(restClient, agentProperties, OkxContractScannerScanControlTest::testRules),
                        new MarketEnvironmentService(marketService),
                        agentProperties,
                        provider,
                        defaultUniverseFilter(agentProperties)
                );
                return new V2Fixture(scanner);
            }
        }

        private static UniverseHardFilter defaultUniverseFilter(AgentProperties props) {
            return new UniverseHardFilter(List.of(
                    new MarketCapFilter(props.selection().universe().minMarketCapUsd()),
                    new CirculationFilter(props.selection().universe().minCirculationRatio()),
                    new VolumeMcapFilter(props.selection().universe().minVolumeMcapRatio()),
                    new PriceRangeFilter(props.market().max24hRangePct()),
                    new LiquidityFilter(props.market().maxSpreadBps(), props.market().minDepthUsdt())
            ));
        }
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
            return new ContractScanResult(List.of(), null);
        }
    }
}

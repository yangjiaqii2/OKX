package com.example.quant.crypto;

import com.example.quant.agent.market.MarketEnvironment;
import com.example.quant.agent.market.MarketEnvironmentService;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.agent.scan.ScanContext;
import com.example.quant.agent.scan.ScanDiagnostics;
import com.example.quant.agent.scan.ScanRunReport;
import com.example.quant.agent.scan.ScanRunStatus;
import com.example.quant.agent.gate.ContractTradeGate;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.scan.ActiveEntryIntentInstIds;
import com.example.quant.crypto.scan.DeepAnalysisTargetAllocator;
import com.example.quant.crypto.scan.DeepAnalysisTargetAllocator.Visit;
import com.example.quant.crypto.scan.ScanCoverageStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineSet;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignal;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.crypto.filter.FilterContext;
import com.example.quant.crypto.filter.FilterOutcome;
import com.example.quant.crypto.filter.UniverseHardFilter;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.FundingHistoryProvider;
import com.example.quant.crypto.provider.MarketCapProvider;
import com.example.quant.crypto.provider.OpenInterestHistoryProvider;
import com.example.quant.crypto.provider.ProviderCache;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.okx.OkxRelativeStrengthProvider;
import com.example.quant.crypto.provider.okx.OkxAthBreakoutProvider;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import com.example.quant.crypto.provider.dto.AthBreakoutSnapshot;
import com.example.quant.crypto.selection.V2SelectionScoreCalculator;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.crypto.provider.TokenSymbolMapper;
import com.example.quant.crypto.provider.dto.FundingHistorySnapshot;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import com.example.quant.crypto.provider.dto.OpenInterestHistorySnapshot;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OkxContractScanner {
    private static final Logger log = LoggerFactory.getLogger(OkxContractScanner.class);

    private final ShortTermOpenInterest oiWindow = new ShortTermOpenInterest();
    private final OkxRestClient okxRestClient;
    private final OkxMarketService okxMarketService;
    private final ContractSignalAnalyzer signalAnalyzer;
    private final ContractNewsAnalysisService contractNewsAnalysisService;
    private final OrderBookLiquidityService orderBookLiquidityService;
    private final MarketEnvironmentService marketEnvironmentService;
    private final AgentProperties agentProperties;
    private final MarketCapProvider marketCapProvider;
    private final UniverseHardFilter universeHardFilter;
    private final OpenInterestHistoryProvider openInterestHistoryProvider;
    private final FundingHistoryProvider fundingHistoryProvider;
    private final ProviderCache<String, MarketCapSnapshot> marketCapCache;
    private final ProviderCache<String, OpenInterestHistorySnapshot> openInterestCache;
    private final ProviderCache<String, FundingHistorySnapshot> fundingCache;
    private final OkxRelativeStrengthProvider relativeStrengthProvider;
    private final OkxAthBreakoutProvider athBreakoutProvider;
    private final V2SelectionScoreCalculator v2SelectionScoreCalculator = new V2SelectionScoreCalculator();
    private final DeepAnalysisTargetAllocator deepAnalysisTargetAllocator = new DeepAnalysisTargetAllocator();
    @Autowired
    private ScanCoverageStore coverageStore;
    private final Map<String, Visit> localVisits = new ConcurrentHashMap<>();
    private volatile ActiveEntryIntentInstIds activeEntryIntentInstIds = Set::of;
    private final AtomicReference<ScanDiagnostics> lastDiagnostics = new AtomicReference<>();
    private final AtomicReference<ScanRunReport> lastScanReport = new AtomicReference<>();
    private final AtomicReference<ScanContext> activeScanContext = new AtomicReference<>();
    private volatile Map<String, V2SelectionScoreResult> lastV2ScoreResults = Map.of();
    private final boolean v2Mode;

    /**
     * 7 参构造器（向后兼容）：使用 NullMarketCapProvider + noop filter，行为与 V1 一致。
     */
    public OkxContractScanner(OkxRestClient okxRestClient, OkxMarketService okxMarketService,
                              ContractSignalAnalyzer signalAnalyzer,
                              ContractNewsAnalysisService contractNewsAnalysisService,
                              OrderBookLiquidityService orderBookLiquidityService,
                              MarketEnvironmentService marketEnvironmentService,
                              AgentProperties agentProperties) {
        // V1 兼容入口：传 null 给 9-arg 构造器，触发 v2Mode=false，走 scanV1 路径。
        // 这样旧测试的 USDT-SWAP / volume24h 预过滤、turnover 转换、fundingRate 跳过等 V1 行为不变。
        this(okxRestClient, marketServiceSafe(okxMarketService), signalAnalyzer, contractNewsAnalysisService,
                orderBookLiquidityService, marketEnvironmentService, agentProperties,
                null, null);
    }

    /**
     * 8 参构造器（向后兼容工作区已有 OpenInterestService 集成）：转发到 9 参 V2 构造器。
     * 同样保持 V1 路径，openInterestService 由调用方持有。
     */
    public OkxContractScanner(OkxRestClient okxRestClient, OkxMarketService okxMarketService,
                              ContractSignalAnalyzer signalAnalyzer,
                              ContractNewsAnalysisService contractNewsAnalysisService,
                              OrderBookLiquidityService orderBookLiquidityService,
                              MarketEnvironmentService marketEnvironmentService,
                              AgentProperties agentProperties,
                              com.example.quant.crypto.OpenInterestService openInterestService) {
        this(okxRestClient, okxMarketService, signalAnalyzer, contractNewsAnalysisService,
                orderBookLiquidityService, marketEnvironmentService, agentProperties,
                null, null);
    }

    /**
     * 9 参构造器（V2 生产）：MarketCap Provider + Universe Hard Filter 完整接入。
     * <p>用 {@code @Autowired} 标记为 Solon 容器首选 ctor；7 参 / 8 参兼容 ctor
     * 由测试直接调用（{@code new OkxContractScanner(...)}），不走容器。
     */
    @Autowired
    public OkxContractScanner(OkxRestClient okxRestClient, OkxMarketService okxMarketService,
                              ContractSignalAnalyzer signalAnalyzer,
                              ContractNewsAnalysisService contractNewsAnalysisService,
                              OrderBookLiquidityService orderBookLiquidityService,
                              MarketEnvironmentService marketEnvironmentService,
                              AgentProperties agentProperties,
                              MarketCapProvider marketCapProvider,
                              UniverseHardFilter universeHardFilter) {
        this.okxRestClient = okxRestClient;
        this.okxMarketService = okxMarketService;
        this.signalAnalyzer = signalAnalyzer;
        this.contractNewsAnalysisService = contractNewsAnalysisService;
        this.orderBookLiquidityService = orderBookLiquidityService;
        this.marketEnvironmentService = marketEnvironmentService;
        this.agentProperties = agentProperties;
        this.marketCapProvider = marketCapProvider == null
                ? new com.example.quant.crypto.provider.NullMarketCapProvider()
                : marketCapProvider;
        this.universeHardFilter = universeHardFilter == null
                ? UniverseHardFilter.noop()
                : universeHardFilter;
        // V2 mode: use real provider/filter (was set via 9-arg ctor)
        this.v2Mode = marketCapProvider != null || universeHardFilter != null;
        this.marketCapCache = new ProviderCache<>(
                "MarketCap",
                Duration.ofSeconds(agentProperties.selection().providers().marketCapFreshTtlSeconds()),
                Duration.ofSeconds(agentProperties.selection().providers().marketCapStaleTtlSeconds())
        );
        // Wave 2 — Derivatives providers (OI / Funding History)
        AgentProperties.Providers.Derivatives derivCfg = agentProperties
                .selection().providers().derivatives();
        this.openInterestHistoryProvider = derivCfg.openInterestEnabled()
                ? new com.example.quant.crypto.provider.okx.OkxOpenInterestHistoryProvider("okx", okxRestClient)
                : null;
        this.fundingHistoryProvider = derivCfg.fundingEnabled()
                ? new com.example.quant.crypto.provider.okx.OkxFundingHistoryProvider(
                        "okx", okxRestClient, derivCfg.extremeFundingRate())
                : null;
        this.openInterestCache = derivCfg.openInterestEnabled()
                ? new ProviderCache<>(
                        "OpenInterestHistory",
                        Duration.ofSeconds(derivCfg.openInterestFreshTtlSeconds()),
                        Duration.ofSeconds(derivCfg.openInterestStaleTtlSeconds()))
                : null;
        this.fundingCache = derivCfg.fundingEnabled()
                ? new ProviderCache<>(
                        "FundingHistory",
                        Duration.ofSeconds(derivCfg.fundingFreshTtlSeconds()),
                        Duration.ofSeconds(derivCfg.fundingStaleTtlSeconds()))
                : null;
        AgentProperties.Providers rsProviders = agentProperties.selection().providers();
        this.relativeStrengthProvider = rsProviders.relativeStrengthEnabled()
                ? new OkxRelativeStrengthProvider(okxMarketService,
                rsProviders.relativeStrengthFreshTtlSeconds(), rsProviders.relativeStrengthStaleTtlSeconds())
                : null;
        AgentProperties.Breakout breakout = agentProperties.selection().breakout();
        this.athBreakoutProvider = rsProviders.athBreakoutEnabled()
                ? new OkxAthBreakoutProvider(okxMarketService, okxRestClient,
                rsProviders.athBreakoutFreshTtlSeconds(), rsProviders.athBreakoutStaleTtlSeconds(),
                rsProviders.athBreakoutMaxPages(), breakout.atAthPct(), breakout.nearAthPct(), breakout.midRangePct())
                : null;
    }

    public ScanDiagnostics lastDiagnostics() {
        return lastDiagnostics.get();
    }

    public ScanRunReport lastScanReport() {
        return lastScanReport.get();
    }

    public ScanContext activeScanContext() {
        return activeScanContext.get();
    }

    public boolean isV2Mode() {
        return v2Mode;
    }

    /** The exact immutable score results used by the most recent V2 ordering. */
    public Map<String, V2SelectionScoreResult> lastV2ScoreResults() {
        return lastV2ScoreResults;
    }

    private static OkxMarketService marketServiceSafe(OkxMarketService s) {
        return s;
    }

    public List<ContractCandidate> scan() {
        return scan(ScanContext.unbounded(null));
    }

    /**
     * 接入活跃等待入场意图来源。未注入时默认空集合，扫描行为与改造前完全一致。
     *
     * <p>用 setter 而非构造器参数：V1 / V2 三个构造器都被大量测试直接调用，
     * 再加一个参数会把无关测试全部改一遍，收益不成比例。
     */
    @Autowired(required = false)
    public void setActiveEntryIntentInstIds(ActiveEntryIntentInstIds source) {
        this.activeEntryIntentInstIds = source == null ? Set::of : source;
    }

    /** 等待意图查询失败不得中断扫描：退回空集合，行为与未注入一致。 */
    private Set<String> pinnedInstIds() {
        try {
            Set<String> pinned = activeEntryIntentInstIds.currentInstIds();
            return pinned == null ? Set.of() : pinned;
        } catch (RuntimeException ex) {
            log.warn("active entry intent lookup failed, scanning without pinned slots message={}", ex.getMessage());
            return Set.of();
        }
    }

    public List<ContractCandidate> scan(ScanContext context) {
        ScanContext ctx = context == null ? ScanContext.unbounded(null) : context;
        activeScanContext.set(ctx);
        try {
            if (v2Mode) {
                return scanV2(ctx);
            }
            return scanV1(ctx);
        } finally {
            activeScanContext.compareAndSet(ctx, null);
        }
    }

    /**
     * V1 路径：保留原 top30 + volume 排序行为，{@link UniverseHardFilter} 不参与。
     * 仅 7 参构造器（旧测试）使用。
     */
    private List<ContractCandidate> scanV1(ScanContext ctx) {
        ctx.setStage("scan_v1");
        MarketEnvironment environment = marketEnvironmentService.current();
        JsonNode root = okxRestClient.publicGet("/api/v5/market/tickers?instType=SWAP");
        List<TickerSnapshot> snapshots = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            String instId = item.path("instId").asText();
            if (!instId.endsWith("-USDT-SWAP")) {
                continue;
            }
            BigDecimal last = decimal(item, "last");
            BigDecimal open24h = decimal(item, "open24h");
            BigDecimal high24h = decimal(item, "high24h");
            BigDecimal low24h = decimal(item, "low24h");
            BigDecimal volCcy24h = nullableDecimal(item, "volCcy24h");
            BigDecimal vol24h = nullableDecimal(item, "vol24h");
            BigDecimal bidPx = decimal(item, "bidPx");
            BigDecimal askPx = decimal(item, "askPx");
            BigDecimal change24h = BigDecimal.ZERO;
            if (open24h.signum() > 0) {
                change24h = last.subtract(open24h)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(open24h, 4, java.math.RoundingMode.HALF_UP);
            }
            BigDecimal turnover24h = quoteTurnoverUsdt(volCcy24h, vol24h, last, null);
            if (turnover24h == null) {
                continue;
            }
            BigDecimal range24h = last.signum() > 0 && high24h.signum() > 0 && low24h.signum() > 0
                    ? high24h.subtract(low24h).multiply(BigDecimal.valueOf(100)).divide(last, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal spreadPct = last.signum() > 0 && bidPx.signum() > 0 && askPx.signum() > 0
                    ? askPx.subtract(bidPx).multiply(BigDecimal.valueOf(100)).divide(last, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            snapshots.add(new TickerSnapshot(instId, baseCurrency(instId), last, open24h, turnover24h,
                    change24h, range24h, spreadPct));
        }
        List<ContractCandidate> ranked = snapshots.stream()
                .filter(snapshot -> snapshot.volume24h() != null
                        && snapshot.volume24h().compareTo(agentProperties.market().minVolume24hUsdt()) >= 0)
                .filter(snapshot -> snapshot.range24hPct().signum() == 0
                        || snapshot.range24hPct().compareTo(agentProperties.market().max24hRangePct()) <= 0)
                .sorted(Comparator.comparing(TickerSnapshot::change24h, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TickerSnapshot::volume24h, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(snapshot -> analyzeSnapshot(snapshot, environment, null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((ContractCandidate candidate) -> firstPlaceEligible(candidate) ? 1 : 0)
                        .reversed()
                        .thenComparing(ContractCandidate::finalRankScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ContractCandidate::score, Comparator.reverseOrder())
                        .thenComparing(ContractCandidate::volume24h, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(agentProperties.topCandidateLimit())
                .toList();
        lastDiagnostics.set(ScanDiagnostics.fromV1(null, snapshots.size(), Map.of(), ranked.size()));
        lastScanReport.set(buildCompletedReport(ctx, snapshots.size(), ranked.size(), ranked.size(), ranked.size(),
                lastDiagnostics.get()));
        return ranked;
    }

    private ContractCandidate analyzeSnapshot(TickerSnapshot snapshot, MarketEnvironment environment,
                                               ContractKlineSet klineSet) {
        BigDecimal fundingRate = safeFundingRate(snapshot.instId());
        if (fundingRate == null) {
            log.debug("contract analysis skipped instId={} reason=FUNDING_RATE_UNAVAILABLE", snapshot.instId());
            return null;
        }
        if (klineSet == null) {
            klineSet = safeKlines(snapshot.instId());
        }
        OpenInterestReading oiReading = fetchOpenInterestReading(snapshot.instId(), snapshot.lastPrice());
        BigDecimal openInterest = oiReading.coinAmount();
        OrderBookLiquiditySnapshot liquidity = orderBookLiquidityService.snapshot(snapshot.instId());
        ContractNewsRiskAnalysis newsRisk = contractNewsAnalysisService.analyze(snapshot.instId());
        ContractSignal signal = signalAnalyzer.analyze(
                snapshot.instId(),
                snapshot.lastPrice(),
                snapshot.open24h(),
                snapshot.volume24h(),
                fundingRate,
                openInterest,
                oiWindow.previous(snapshot.instId(), oiReading.timestamp, openInterest, System.currentTimeMillis()),
                klineSet
        );
        ContractScoreBreakdown scoreBreakdown = adjustedScoreBreakdown(signal.scoreBreakdown(), liquidity, environment,
                signal.directionBias(), newsRisk, snapshot.volume24h());
        ContractFactorScore factorScore = scoreBreakdown.weightedFactorScore();
        int adjustedScore = Math.max(0, Math.min(100, scoreBreakdown.weightedTotal()));
        BigDecimal finalRankScore = finalRankScore(adjustedScore, scoreBreakdown, signal, liquidity);
        String action = adjustedAction(signal.signalType(), signal.klineAnalysis().entryTiming5m(), adjustedScore,
                newsRisk, liquidity, finalRankScore);
        int leverage = "AUTO_TRADE_ALLOWED".equals(action) || "WAIT_CONFIRM".equals(action)
                ? signal.suggestedLeverage()
                : 0;
        return new ContractCandidate(
                    MarketType.OKX_SWAP,
                    snapshot.instId(),
                    snapshot.baseCurrency(),
                    "USDT",
                    snapshot.lastPrice(),
                    snapshot.change24h(),
                    signal.changePercent5m(),
                    snapshot.volume24h(),
                    signal.volumeSpikeRatio(),
                    signal.fundingRate(),
                    signal.openInterest(),
                    signal.openInterestChange(),
                    signal.directionBias(),
                    signal.volatility(),
                    adjustedScore,
                    factorScore,
                    signal.entryPrice(),
                    signal.stopLossPrice(),
                    signal.takeProfitPrice(),
                    leverage,
                    signal.riskRewardRatio(),
                    liquidity.spreadBps(),
                    liquidity.bidDepthUsdt(),
                    liquidity.askDepthUsdt(),
                    liquidity.estimatedSlippageBps(),
                    environment.btcTrend(),
                    environment.ethTrend(),
                    environment.riskLevel(),
                    mergedReasons(snapshot.change24h(), snapshot.volume24h(), signal.directionBias(),
                            signal.reasonList(), liquidity, environment, signal.signalType(), newsRisk),
                    mergedRisks(signal.riskTagList(), liquidity, environment, signal.directionBias(), newsRisk, action),
                    Instant.now(),
                    signal.signalType(),
                    action,
                    signal.entryType(),
                    signal.todayChangePct(),
                    signal.atrPct20m(),
                    signal.stopLossPct(),
                    finalRankScore,
                    scoreBreakdown,
                    signal.klineAnalysis().withAnalysisDecision(signal.score(), signal.action()),
                    newsRisk,
                    // V2: Universe / Market Quality, 由 analyzeSnapshotV2 注入
                     null, null, null, null, null, null, null, null, null, oiReading.usdNotional()
            );
    }

    private ContractCandidate analyzeSnapshotV2(TickerSnapshot snapshot, MarketEnvironment environment,
                                                 MarketCapSnapshot mcapSnap, String marketQualityNote,
                                                 ContractKlineSet klineSet) {
        ContractCandidate legacy = analyzeSnapshot(snapshot, environment, klineSet);
        if (legacy == null) {
            return null;
        }
        BigDecimal marketCapUsd = mcapSnap == null ? null : mcapSnap.marketCapUsd();
        BigDecimal fdvUsd = mcapSnap == null ? null : mcapSnap.fdvUsd();
        BigDecimal circulationRatio = null;
        BigDecimal volumeMcapRatio = null;
        if (mcapSnap != null && mcapSnap.circulatingSupply() != null) {
            BigDecimal denom = mcapSnap.maxSupply() != null && mcapSnap.maxSupply().signum() > 0
                    ? mcapSnap.maxSupply() : mcapSnap.totalSupply();
            if (denom != null && denom.signum() > 0) {
                circulationRatio = mcapSnap.circulatingSupply().divide(denom, 4, RoundingMode.HALF_UP);
            }
        }
        if (mcapSnap != null && mcapSnap.marketCapUsd() != null && mcapSnap.marketCapUsd().signum() > 0
                && snapshot.volume24h() != null) {
            volumeMcapRatio = snapshot.volume24h().divide(mcapSnap.marketCapUsd(), 4, RoundingMode.HALF_UP);
        }
        return legacy.withV2MarketQuality(marketCapUsd, fdvUsd, circulationRatio, volumeMcapRatio, marketQualityNote);
    }

    /**
     * V2 路径：拉取 broad universe → Universe Hard Filter → 按成交额选 top deep-analysis-limit → deep analysis。
     */
    private List<ContractCandidate> scanV2(ScanContext ctx) {
        log.info("scanV2 started scanId={} deadline={}", ctx.scanId(), ctx.deadline());
        lastV2ScoreResults = Map.of();
        if (ctx.isExpired()) {
            return finishDeadlineExceeded(ctx, "init");
        }
        ctx.setStage("market_environment");
        MarketEnvironment environment = marketEnvironmentService.current(ctx);
        if (ctx.isExpired()) {
            return finishDeadlineExceeded(ctx, ctx.currentStage());
        }
        ctx.setStage("fetch_tickers");
        List<TickerSnapshot> snapshots = new ArrayList<>();
        try {
            JsonNode root = okxRestClient.publicGet(
                    "/api/v5/market/tickers?instType=SWAP",
                    ctx.requestTimeout());
            for (JsonNode item : root.path("data")) {
                TickerSnapshot s = buildTickerSnapshot(item);
                if (s != null) {
                    snapshots.add(s);
                }
            }
        } catch (Exception ex) {
            log.warn("scanV2 tickers fetch failed scanId={} message={}", ctx.scanId(), ex.getMessage());
            if (ctx.isExpired()) {
                return finishDeadlineExceeded(ctx, ctx.currentStage());
            }
            ctx.markFailed();
            lastDiagnostics.set(emptyDiagnostics());
            lastScanReport.set(ScanRunReport.failed(ctx.scanId(), ctx.startedAt(), ctx.currentStage(),
                    lastDiagnostics.get()));
            logScanFinished(ctx, lastScanReport.get());
            return List.of();
        }
        if (ctx.isExpired()) {
            return finishDeadlineExceeded(ctx, ctx.currentStage());
        }
        if (snapshots.isEmpty()) {
            ctx.markFailed();
            lastDiagnostics.set(emptyDiagnostics());
            lastScanReport.set(ScanRunReport.failed(ctx.scanId(), ctx.startedAt(), ctx.currentStage(),
                    lastDiagnostics.get()));
            logScanFinished(ctx, lastScanReport.get());
            return List.of();
        }
        ctx.setStage("collect_market_cap");
        Map<String, ProviderResult<MarketCapSnapshot>> mcapByBase = collectMarketCaps(snapshots, ctx);
        if (ctx.status() == ScanRunStatus.PARTIAL_TIMEOUT) {
            return finishDeadlineExceeded(ctx, ctx.currentStage());
        }
        if (ctx.isExpired()) {
            return finishDeadlineExceeded(ctx, ctx.currentStage());
        }
        DataQuality providerStatus = overallMcapStatus(mcapByBase);
        ctx.setStage("universe_hard_filter");
        List<FilterContext> contexts = snapshots.stream()
                .map(s -> toFilterContext(s, mcapByBase.get(safeBaseOf(s.instId()))))
                .collect(Collectors.toList());
        FilterOutcome outcome = universeHardFilter.applyBatch(contexts);
        Map<String, TickerSnapshot> snapshotByInstId = new LinkedHashMap<>();
        for (TickerSnapshot s : snapshots) {
            snapshotByInstId.put(s.instId(), s);
        }
        int deepTargetLimit = agentProperties.deepAnalysisLimit();
        // 硬过滤已经判定资格；这里只决定有限的深度分析名额分配给谁。
        // 已有等待意图优先刷新，轮换依据按合约保存的分析记录。
        List<TickerSnapshot> passedByTurnover = outcome.passed().stream()
                .map(filterCtx -> snapshotByInstId.get(filterCtx.instId()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TickerSnapshot::volume24h, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        // 已经存在等待入场意图的合约必须每轮拿到新数据：evaluate() 找不到候选就既不触发也不失效。
        // 名额同样从 deepTargetLimit 内部扣除，不扩大请求预算。
        Set<String> pinnedInstIds = pinnedInstIds();
        Map<String, Visit> history = coverageStore == null ? localVisits : coverageStore.load();
        long roundTime = ctx.startedAt().toEpochMilli();
        DeepAnalysisTargetAllocator.Allocation allocation = deepAnalysisTargetAllocator.allocate(
                passedByTurnover.stream().map(TickerSnapshot::instId).toList(),
                deepTargetLimit,
                agentProperties.selection().deepAnalysisRotationQuota(),
                pinnedInstIds, history, roundTime);
        List<TickerSnapshot> deepTargets = allocation.instIds().stream()
                .map(snapshotByInstId::get)
                .filter(Objects::nonNull)
                .toList();
        ctx.setStage("deep_analysis");
        if (ctx.isExpired()) {
            ctx.markPartialTimeout();
            ctx.addSkippedByDeadline(deepTargets.size());
            ScanDiagnostics preTimeoutDiagnostics = ScanDiagnostics.fromV2(
                    outcome, snapshots.size(),
                    buildProviderStatus(providerStatus, Map.of(), Map.of(), Map.of(), Map.of(), 0),
                    deepTargets.size(), 0, deepTargetLimit, 0, 0, 0);
            return finishPartialTimeout(ctx, snapshots.size(), outcome.passed().size(), deepTargetLimit,
                    List.of(), preTimeoutDiagnostics);
        }
        Map<String, OpenInterestHistorySnapshot> oiByInstId = new LinkedHashMap<>();
        Map<String, FundingHistorySnapshot> fundingByInstId = new LinkedHashMap<>();
        Map<String, RelativeStrengthSnapshot> relativeStrengthByInstId = new LinkedHashMap<>();
        Map<String, DataQuality> relativeStrengthQualityByInstId = new LinkedHashMap<>();
        Map<String, AthBreakoutSnapshot> athBreakoutByInstId = new LinkedHashMap<>();
        Map<String, DataQuality> athBreakoutQualityByInstId = new LinkedHashMap<>();
        Set<String> attempted = ConcurrentHashMap.newKeySet();
        List<ContractCandidate> candidates = runDeepAnalysisConcurrent(
                ctx,
                deepTargets,
                attempted,
                environment,
                mcapByBase,
                oiByInstId,
                fundingByInstId,
                relativeStrengthByInstId,
                relativeStrengthQualityByInstId,
                athBreakoutByInstId,
                athBreakoutQualityByInstId);
        Map<String, Visit> visits = new LinkedHashMap<>();
        for (String id : attempted) {
            Visit previous = history.get(id);
            visits.put(id, new Visit(roundTime, previous == null ? 0 : previous.analyzedAt()));
        }
        int analyzed = 0;
        for (ContractCandidate c : candidates) {
            if (c.klineAnalysis() != null && c.klineAnalysis().decisionCandleTimestamp() != null) {
                visits.put(c.instId(), new Visit(roundTime, roundTime));
                analyzed++;
            }
        }
        if (coverageStore == null) {
            localVisits.putAll(visits);
        } else {
            coverageStore.save(visits);
        }
        long recentCutoff = roundTime - Duration.ofMinutes(30).toMillis();
        int recentlyAnalyzed = 0;
        for (TickerSnapshot ticker : passedByTurnover) {
            Visit visit = visits.getOrDefault(ticker.instId(), history.get(ticker.instId()));
            if (visit != null && visit.analyzedAt() > 0 && visit.analyzedAt() >= recentCutoff) {
                recentlyAnalyzed++;
            }
        }
        outcome.funnel().recordStage("analyzed_last_30m", recentlyAnalyzed);
        outcome.funnel().recordStage("rotation_targets", allocation.rotationCount());
        outcome.funnel().recordStage("analyzed_signals", analyzed);
        if (ctx.status() != ScanRunStatus.COMPLETED || ctx.isExpired()) {
            ScanDiagnostics partialDiagnostics = ScanDiagnostics.fromV2(
                    outcome, snapshots.size(),
                    buildProviderStatus(providerStatus, oiByInstId, fundingByInstId,
                            relativeStrengthQualityByInstId, athBreakoutQualityByInstId, deepTargets.size()),
                    deepTargets.size(),
                    candidates.size(),
                    deepTargetLimit,
                    0,
                    0,
                    0);
            return finishPartialTimeout(ctx, snapshots.size(), outcome.passed().size(), deepTargetLimit,
                    List.of(), partialDiagnostics);
        }
        // Wave 2 — 将 OI / Funding snapshot 注入 candidate（不影响排序，仅扩 rich data）
        if (!oiByInstId.isEmpty() || !fundingByInstId.isEmpty()) {
            List<ContractCandidate> enriched = new ArrayList<>(candidates.size());
            for (ContractCandidate c : candidates) {
                OpenInterestHistorySnapshot oi = oiByInstId.get(c.instId());
                FundingHistorySnapshot fund = fundingByInstId.get(c.instId());
                if (oi == null && fund == null) {
                    enriched.add(c);
                } else {
                    enriched.add(c.withDerivatives(oi, fund));
                }
            }
            candidates = enriched;
        }
        if (!relativeStrengthByInstId.isEmpty()) {
            candidates = candidates.stream()
                    .map(c -> relativeStrengthByInstId.containsKey(c.instId())
                            ? c.withRelativeStrength(relativeStrengthByInstId.get(c.instId())) : c)
                    .collect(Collectors.toList());
        }
        if (!athBreakoutByInstId.isEmpty()) {
            candidates = candidates.stream()
                    .map(c -> athBreakoutByInstId.containsKey(c.instId())
                            ? c.withAthBreakout(athBreakoutByInstId.get(c.instId())) : c)
                    .collect(Collectors.toList());
        }
        // Wave 4 — V2 selection score is a pure ranking layer after all enrichment.
        Map<String, V2SelectionScoreResult> v2Scores = new LinkedHashMap<>();
        for (ContractCandidate candidate : candidates) {
            v2Scores.put(candidate.instId(), v2SelectionScoreCalculator.calculate(candidate));
        }
        lastV2ScoreResults = Map.copyOf(v2Scores);
        long scoreAvailable = v2Scores.values().stream().filter(result -> result.normalizedScore() != null).count();
        long scoreUnavailable = v2Scores.size() - scoreAvailable;
        log.debug("scanV2 selection scores candidates={} available={} unavailable={}",
                v2Scores.size(), scoreAvailable, scoreUnavailable);
        // 8. 交易资格优先 + V2 selection score 排序，然后才截断到 topCandidateLimit。
        //    截断发生在资格判断之后，合法交易候选不会被"仅可观察"的高分候选挤出交付列表。
        List<ContractCandidate> ranked = rankForDelivery(candidates, v2Scores, agentProperties, pinnedInstIds);
        // 5. 记录 diagnostics（含 V2 漏斗扩展）
        //    after_deep_analysis: 实际完成 deep analysis 的数量
        //    after_signal:        产生 V1 tradable signal 的数量
        //    after_trade_gate:    action != "NO_TRADE" 的数量
        //    after_final_rank:    排序后取 topCandidateLimit 的最终数量
        int signalPassed = (int) candidates.stream()
                .filter(c -> isTradableSignal(c.signalType()))
                .count();
        int tradeGatePassed = (int) candidates.stream()
                .filter(c -> !"NO_TRADE".equals(c.action()))
                .count();
        ScanDiagnostics diagnostics = ScanDiagnostics.fromV2(
                outcome, snapshots.size(),
                buildProviderStatus(providerStatus, oiByInstId, fundingByInstId,
                        relativeStrengthQualityByInstId, athBreakoutQualityByInstId, deepTargets.size()),
                deepTargets.size(),
                candidates.size(),
                deepTargetLimit,
                signalPassed,
                tradeGatePassed,
                ranked.size());
        lastDiagnostics.set(diagnostics);
        ScanRunReport report = buildScanReport(ctx, diagnostics, snapshots.size(), outcome.passed().size(),
                deepTargetLimit, ranked.size());
        lastScanReport.set(report);
        long deliveredEligible = ranked.stream().filter(c -> tradableNow(c, agentProperties)).count();
        log.info("scanV2 universe={} passed={} rejected={} deepTargetLimit={} deepTargets={} "
                        + "pinnedWaiting={} turnoverHead={} rotation={} deepCompleted={} "
                        + "signal={} tradeGate={} finalRank={} deliveredTradeReady={} "
                        + "skippedByDeadline={} providerTimeouts={} funnel={}",
                snapshots.size(), outcome.passed().size(), outcome.rejected().size(),
                deepTargetLimit, deepTargets.size(),
                allocation.pinnedCount(), allocation.headCount(), allocation.rotationCount(), candidates.size(),
                signalPassed, tradeGatePassed, ranked.size(), deliveredEligible,
                ctx.skippedByDeadline(), ctx.providerTimeoutCount(),
                outcome.funnel().stages());
        logScanFinished(ctx, report);
        if (ctx.status() != ScanRunStatus.COMPLETED) {
            return List.of();
        }
        return ranked;
    }

    /**
     * 交付候选排序与截断。
     *
     * <p>深度分析产出的候选数量（deepAnalysisLimit）远大于交付上限（topCandidateLimit），
     * 而下游 {@code AutoTradeService} 只能看到交付列表。原行为是先按 V2 rankingScore 截断，
     * 交易资格判断留到下游——结果是"信息丰富但当前不可交易"的候选会把合法交易候选挤出交付列表，
     * 被挤掉的候选<b>没有任何递补机会</b>。
     *
     * <p>这里把资格判断提到截断之前，并按<b>四个互斥档位</b>分组，组内再按 V2 排名：
     * <ol>
     *   <li>{@link #TIER_PINNED}：已存在活跃等待入场意图。不进交付列表它就既不会触发也不会失效，
     *       只能静默到过期，所以必须保证名额；</li>
     *   <li>{@link #TIER_TRADABLE_NOW}：AI 硬门控 + 计划门控 + <b>动作门控</b>全部通过，当前可以下单；</li>
     *   <li>{@link #TIER_PLAN_ELIGIBLE}：计划条件合格但动作仍是 WAIT，需要等价格触发；</li>
     *   <li>{@link #TIER_OBSERVE_ONLY}：当前不可交易，仅供展示与观察。</li>
     * </ol>
     * 档位 2 / 3 的判据分别对应 {@code AutoTradeService} 的 {@code autoGate} 与
     * {@code aiEligibleCandidates}，避免两处标准漂移。
     *
     * <p>本层<b>只影响优先级，不放行任何东西</b>：所有硬门控（news / 风控 / refresh / 最终风险检查）
     * 仍在下游原位生效，进入交付列表不代表通过风控，pin 也不例外。
     */
    static List<ContractCandidate> rankForDelivery(List<ContractCandidate> candidates,
                                                   Map<String, V2SelectionScoreResult> v2Scores,
                                                   AgentProperties agentProperties) {
        return rankForDelivery(candidates, v2Scores, agentProperties, Set.of());
    }

    static List<ContractCandidate> rankForDelivery(List<ContractCandidate> candidates,
                                                   Map<String, V2SelectionScoreResult> v2Scores,
                                                   AgentProperties agentProperties,
                                                   Set<String> pinnedInstIds) {
        Set<String> pinned = pinnedInstIds == null ? Set.of() : pinnedInstIds;
        // 档位判断每个候选只做一次：排序键在比较过程中会被反复取值。
        Map<String, Integer> tiers = new LinkedHashMap<>();
        for (ContractCandidate candidate : candidates) {
            tiers.put(candidate.instId(), deliveryTier(candidate, agentProperties, pinned));
        }
        return candidates.stream()
                .sorted(deliveryComparator(v2Scores, tiers))
                .limit(agentProperties.topCandidateLimit())
                .collect(Collectors.toList());
    }

    private static final int TIER_OBSERVE_ONLY = 0;
    private static final int TIER_PLAN_ELIGIBLE = 1;
    private static final int TIER_TRADABLE_NOW = 2;
    private static final int TIER_PINNED = 3;

    private static final Set<String> WAITING_ACTIONS = Set.of("WAIT", "WAIT_CONFIRM", "WAIT_PULLBACK", "WAIT_RETEST");

    /**
     * 候选的交付档位。任何字段缺失导致的异常一律按最低档处理（fail-closed）。
     *
     * <p>"立即可交易"与"计划合格但待触发"必须分开：{@code planDenyReasons} 不检查 {@code action}，
     * 只有 {@code autoDenyReasons} 检查。把两者混为一谈会让动作仍是 WAIT 的候选冒充立即可交易，
     * 占住优先组。反过来，只差一个动作触发的候选也不能和"信号根本不可交易"的候选混在一起，
     * 否则等待回调 / 回踩的正常生命周期会被挤掉。
     *
     * <p>这里<b>刻意不用</b> {@code planDenyReasons} 划档：它在规则回退路径下由
     * {@code ContractTradePlanBuilder} 当作 gateWarnings 处理，并不必然阻断下单，
     * 拿它排除交付会误伤真正可成交的候选。AI 选择池那一侧的 {@code planDenyReasons} 过滤
     * 仍由 {@code AutoTradeService.aiEligibleCandidates} 在交付列表上独立执行，未被削弱。
     */
    private static int deliveryTier(ContractCandidate candidate, AgentProperties agentProperties,
                                    Set<String> pinnedInstIds) {
        try {
            if (candidate != null && pinnedInstIds.contains(candidate.instId())) {
                return TIER_PINNED;
            }
            if (!ContractTradeGate.aiHardDenyReasons(candidate, agentProperties).isEmpty()) {
                return TIER_OBSERVE_ONLY;
            }
            List<String> actionReasons = ContractTradeGate.autoDenyReasons(candidate, agentProperties);
            if (actionReasons.isEmpty()) {
                return TIER_TRADABLE_NOW;
            }
            // 唯一的阻碍就是动作尚未就绪 → 这是等待价格触发的候选，不是不可交易的候选。
            return candidate.action() != null && WAITING_ACTIONS.contains(candidate.action())
                    && actionReasons.size() == 1
                    ? TIER_PLAN_ELIGIBLE : TIER_OBSERVE_ONLY;
        } catch (RuntimeException ex) {
            log.debug("delivery tier check failed instId={} message={}",
                    candidate == null ? null : candidate.instId(), ex.getMessage());
            return TIER_OBSERVE_ONLY;
        }
    }

    /** 当前档位达到"立即可交易"的候选数量，用于扫描日志观测。 */
    private static boolean tradableNow(ContractCandidate candidate, AgentProperties agentProperties) {
        return deliveryTier(candidate, agentProperties, Set.of()) == TIER_TRADABLE_NOW;
    }

    private static Comparator<ContractCandidate> deliveryComparator(Map<String, V2SelectionScoreResult> v2Scores,
                                                                    Map<String, Integer> tiers) {
        return Comparator.comparing((ContractCandidate c) -> tiers.getOrDefault(c.instId(), TIER_OBSERVE_ONLY))
                .reversed()
                .thenComparing(c -> rankingScoreOf(v2Scores, c),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(c -> normalizedScoreOf(v2Scores, c), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(c -> availableWeightOf(v2Scores, c), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ContractCandidate::finalRankScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ContractCandidate::score, Comparator.reverseOrder())
                .thenComparing(ContractCandidate::volume24h, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ContractCandidate::instId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static BigDecimal rankingScoreOf(Map<String, V2SelectionScoreResult> scores, ContractCandidate c) {
        V2SelectionScoreResult result = scores.get(c.instId());
        return result == null ? null : result.rankingScore();
    }

    private static BigDecimal normalizedScoreOf(Map<String, V2SelectionScoreResult> scores, ContractCandidate c) {
        V2SelectionScoreResult result = scores.get(c.instId());
        return result == null ? null : result.normalizedScore();
    }

    private static BigDecimal availableWeightOf(Map<String, V2SelectionScoreResult> scores, ContractCandidate c) {
        V2SelectionScoreResult result = scores.get(c.instId());
        return result == null ? null : result.availableWeight();
    }

    private List<ContractCandidate> finishDeadlineExceeded(ScanContext ctx, String stage) {
        ctx.setStage(stage);
        ctx.markPartialTimeout();
        lastDiagnostics.set(emptyDiagnostics());
        ScanRunReport report = buildScanReport(ctx, lastDiagnostics.get(), 0, 0, 0, 0);
        lastScanReport.set(report);
        logScanFinished(ctx, report);
        return List.of();
    }

    private List<ContractCandidate> runDeepAnalysisConcurrent(
            ScanContext ctx,
            List<TickerSnapshot> deepTargets,
            Set<String> attempted,
            MarketEnvironment environment,
            Map<String, ProviderResult<MarketCapSnapshot>> mcapByBase,
            Map<String, OpenInterestHistorySnapshot> oiByInstId,
            Map<String, FundingHistorySnapshot> fundingByInstId,
            Map<String, RelativeStrengthSnapshot> relativeStrengthByInstId,
            Map<String, DataQuality> relativeStrengthQualityByInstId,
            Map<String, AthBreakoutSnapshot> athBreakoutByInstId,
            Map<String, DataQuality> athBreakoutQualityByInstId) {
        if (deepTargets.isEmpty()) {
            return List.of();
        }
        int configuredConcurrency = Math.max(1, agentProperties.selection().deepAnalysisConcurrency());
        int poolSize = Math.min(configuredConcurrency, deepTargets.size());
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        var completed = new ExecutorCompletionService<DeepAnalysisPiece>(pool);
        List<Future<DeepAnalysisPiece>> futures = new ArrayList<>(deepTargets.size());
        List<ContractCandidate> candidates = new ArrayList<>();
        try {
            for (TickerSnapshot snapshot : deepTargets) {
                if (ctx.isExpired()) {
                    ctx.markPartialTimeout();
                    ctx.addSkippedByDeadline(deepTargets.size() - futures.size());
                    break;
                }
                futures.add(completed.submit(() -> analyzeDeepTarget(ctx, snapshot, attempted, environment, mcapByBase)));
            }
            Instant hardStop = ctx.hardStopAt();
            for (int index = 0; index < futures.size(); index++) {
                if (ctx.isExpired() || ctx.isPastHardStop()) {
                    cancelRemainingDeepAnalysis(futures, ctx, deepTargets.size());
                    break;
                }
                Duration waitBudget = waitBudget(ctx, hardStop);
                if (waitBudget.isZero()) {
                    cancelRemainingDeepAnalysis(futures, ctx, deepTargets.size());
                    break;
                }
                try {
                    Future<DeepAnalysisPiece> ready = completed.poll(waitBudget.toMillis(), TimeUnit.MILLISECONDS);
                    if (ready == null) {
                        cancelRemainingDeepAnalysis(futures, ctx, deepTargets.size());
                        break;
                    }
                    DeepAnalysisPiece piece = ready.get();
                    mergeDeepAnalysisPiece(ctx, piece, candidates, oiByInstId, fundingByInstId,
                            relativeStrengthByInstId, relativeStrengthQualityByInstId,
                            athBreakoutByInstId, athBreakoutQualityByInstId);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        ctx.markPartialTimeout();
                        cancelRemainingDeepAnalysis(futures, ctx, deepTargets.size());
                        break;
                    }
                    if (ctx.isExpired() || ctx.isPastHardStop() || !ctx.acceptingResults()) {
                        cancelRemainingDeepAnalysis(futures, ctx, deepTargets.size());
                        break;
                    }
                    log.warn("deep analysis task failed scanId={} message={}",
                            ctx.scanId(), cause == null ? ex.getMessage() : cause.getMessage());
                } catch (CancellationException ex) {
                    ctx.markPartialTimeout();
                    cancelRemainingDeepAnalysis(futures, ctx, deepTargets.size());
                    break;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    ctx.markPartialTimeout();
                    cancelRemainingDeepAnalysis(futures, ctx, deepTargets.size());
                    break;
                }
            }
        } finally {
            ctx.stopAcceptingResults();
            pool.shutdownNow();
            try {
                pool.awaitTermination(ScanContext.CLEANUP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        if (ctx.status() == ScanRunStatus.PARTIAL_TIMEOUT && ctx.skippedByDeadline() == 0) {
            ctx.addSkippedByDeadline(Math.max(0, deepTargets.size() - ctx.deepAnalysisCompleted()));
        }
        return candidates;
    }

    private DeepAnalysisPiece analyzeDeepTarget(
            ScanContext ctx,
            TickerSnapshot snapshot,
            Set<String> attempted,
            MarketEnvironment environment,
            Map<String, ProviderResult<MarketCapSnapshot>> mcapByBase) {
        if (!ctx.acceptingResults() || ctx.isExpired()) {
            return null;
        }
        ctx.setStage("deep_analysis:" + snapshot.instId());
        ctx.beginDeepAnalysis();
        attempted.add(snapshot.instId());
        try {
            String base = safeBaseOf(snapshot.instId());
            ProviderResult<MarketCapSnapshot> mcapResult = base == null
                    ? ProviderResult.unavailable("no base mapping")
                    : mcapByBase.getOrDefault(base, ProviderResult.unavailable("no base mapping"));
            trackProviderResult(ctx, mcapResult);
            if (!ctx.acceptingResults() || ctx.isExpired()) {
                return null;
            }
            MarketCapSnapshot mcapSnap = mcapResult == null || !mcapResult.isUsable() ? null : mcapResult.data();
            String note = mcapResult == null ? "UNAVAILABLE"
                    : mcapResult.dataQuality() == DataQuality.STALE ? "STALE"
                    : mcapResult.dataQuality() == DataQuality.UNAVAILABLE ? "UNAVAILABLE"
                    : mcapResult.dataQuality() == DataQuality.TIMEOUT ? "TIMEOUT"
                    : "FRESH";
            ContractKlineSet klineSet = safeKlines(snapshot.instId(), ctx);
            if (!ctx.acceptingResults() || ctx.isExpired()) {
                return null;
            }
            ContractCandidate candidate = analyzeSnapshotV2(snapshot, environment, mcapSnap, note, klineSet);
            if (candidate == null || !ctx.acceptingResults()) {
                return null;
            }
            ProviderResult<RelativeStrengthSnapshot> rsResult = null;
            RelativeStrengthSnapshot relativeStrength = null;
            DataQuality relativeStrengthQuality = null;
            if (relativeStrengthProvider != null && ctx.acceptingResults() && !ctx.isExpired()) {
                rsResult = relativeStrengthProvider.fetch(snapshot.instId(), klineSet, ctx.requestTimeout());
                trackProviderResult(ctx, rsResult, snapshot.instId(), "relative_strength");
                if (rsResult != null && rsResult.data() != null) {
                    relativeStrength = rsResult.data();
                    relativeStrengthQuality = rsResult.dataQuality();
                }
            }
            ProviderResult<AthBreakoutSnapshot> athResult = null;
            AthBreakoutSnapshot athBreakout = null;
            DataQuality athBreakoutQuality = null;
            if (athBreakoutProvider != null && ctx.acceptingResults() && !ctx.isExpired()) {
                athResult = athBreakoutProvider.fetch(
                        snapshot.instId(), snapshot.lastPrice(), klineSet, ctx.requestTimeout());
                trackProviderResult(ctx, athResult, snapshot.instId(), "ath_breakout");
                if (athResult != null && athResult.data() != null) {
                    athBreakout = athResult.data();
                    athBreakoutQuality = athResult.dataQuality();
                }
            }
            ProviderResult<OpenInterestHistorySnapshot> oiResult = null;
            OpenInterestHistorySnapshot openInterest = null;
            if (openInterestHistoryProvider != null && ctx.acceptingResults() && !ctx.isExpired()) {
                oiResult = openInterestCache == null
                        ? fetchOpenInterestHistory(snapshot.instId(), ctx)
                        : openInterestCache.get(snapshot.instId(), id -> fetchOpenInterestHistory(id, ctx));
                trackProviderResult(ctx, oiResult, snapshot.instId(), "open_interest");
                if (oiResult != null && oiResult.isUsable() && oiResult.data() != null) {
                    openInterest = oiResult.data();
                }
            }
            ProviderResult<FundingHistorySnapshot> fResult = null;
            FundingHistorySnapshot funding = null;
            if (fundingHistoryProvider != null && ctx.acceptingResults() && !ctx.isExpired()) {
                fResult = fundingCache == null
                        ? fetchFundingHistory(snapshot.instId(), ctx)
                        : fundingCache.get(snapshot.instId(), id -> fetchFundingHistory(id, ctx));
                trackProviderResult(ctx, fResult, snapshot.instId(), "funding");
                if (fResult != null && fResult.isUsable() && fResult.data() != null) {
                    funding = fResult.data();
                }
            }
            if (!ctx.acceptingResults() || ctx.isExpired()) {
                return null;
            }
            if (enrichmentSucceeded(ctx, rsResult, athResult, oiResult, fResult)) {
                ctx.completeDeepAnalysis();
            }
            return new DeepAnalysisPiece(
                    candidate,
                    openInterest,
                    funding,
                    relativeStrength,
                    relativeStrengthQuality,
                    athBreakout,
                    athBreakoutQuality);
        } catch (RuntimeException ex) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            log.warn("analyzeSnapshotV2 failed scanId={} instId={} message={}",
                    ctx.scanId(), snapshot.instId(), ex.getMessage());
            return null;
        }
    }

    private static void mergeDeepAnalysisPiece(
            ScanContext ctx,
            DeepAnalysisPiece piece,
            List<ContractCandidate> candidates,
            Map<String, OpenInterestHistorySnapshot> oiByInstId,
            Map<String, FundingHistorySnapshot> fundingByInstId,
            Map<String, RelativeStrengthSnapshot> relativeStrengthByInstId,
            Map<String, DataQuality> relativeStrengthQualityByInstId,
            Map<String, AthBreakoutSnapshot> athBreakoutByInstId,
            Map<String, DataQuality> athBreakoutQualityByInstId) {
        if (piece == null || piece.candidate() == null || !ctx.acceptingResults()) {
            return;
        }
        candidates.add(piece.candidate());
        String instId = piece.candidate().instId();
        if (piece.openInterest() != null) {
            oiByInstId.put(instId, piece.openInterest());
        }
        if (piece.funding() != null) {
            fundingByInstId.put(instId, piece.funding());
        }
        if (piece.relativeStrength() != null) {
            relativeStrengthByInstId.put(instId, piece.relativeStrength());
            if (piece.relativeStrengthQuality() != null) {
                relativeStrengthQualityByInstId.put(instId, piece.relativeStrengthQuality());
            }
        }
        if (piece.athBreakout() != null) {
            athBreakoutByInstId.put(instId, piece.athBreakout());
            if (piece.athBreakoutQuality() != null) {
                athBreakoutQualityByInstId.put(instId, piece.athBreakoutQuality());
            }
        }
    }

    private static Duration waitBudget(ScanContext ctx, Instant hardStop) {
        Duration remaining = ctx.remainingTime();
        if (hardStop != null) {
            Duration untilHardStop = Duration.between(Instant.now(), hardStop);
            if (untilHardStop.isNegative()) {
                return Duration.ZERO;
            }
            if (remaining.compareTo(untilHardStop) > 0) {
                remaining = untilHardStop;
            }
        }
        return remaining.isZero() ? Duration.ZERO : remaining.plus(ScanContext.CLEANUP_GRACE);
    }

    private static void cancelRemainingDeepAnalysis(
            List<Future<DeepAnalysisPiece>> futures,
            ScanContext ctx,
            int deepTargetCount) {
        ctx.markPartialTimeout();
        for (Future<DeepAnalysisPiece> future : futures) {
            future.cancel(true);
        }
        ctx.addSkippedByDeadline(Math.max(0, deepTargetCount - ctx.deepAnalysisCompleted()));
    }

    private record DeepAnalysisPiece(
            ContractCandidate candidate,
            OpenInterestHistorySnapshot openInterest,
            FundingHistorySnapshot funding,
            RelativeStrengthSnapshot relativeStrength,
            DataQuality relativeStrengthQuality,
            AthBreakoutSnapshot athBreakout,
            DataQuality athBreakoutQuality
    ) {
    }

    private List<ContractCandidate> finishPartialTimeout(ScanContext ctx, int universeCount, int hardFilterPassed,
                                                         int deepTargetLimit, List<ContractCandidate> candidates,
                                                         ScanDiagnostics diagnostics) {
        ctx.markPartialTimeout();
        lastDiagnostics.set(diagnostics);
        ScanRunReport report = buildScanReport(ctx, diagnostics, universeCount, hardFilterPassed,
                deepTargetLimit, candidates.size());
        lastScanReport.set(report);
        logScanFinished(ctx, report);
        return candidates;
    }

    private ScanRunReport buildScanReport(ScanContext ctx, ScanDiagnostics diagnostics, int universeCount,
                                          int hardFilterPassed, int deepTargetLimit, int finalRankCount) {
        Instant finishedAt = Instant.now();
        return new ScanRunReport(
                ctx.scanId(),
                ctx.status(),
                ctx.startedAt(),
                finishedAt,
                java.time.Duration.between(ctx.startedAt(), finishedAt).toMillis(),
                ctx.currentStage(),
                universeCount,
                hardFilterPassed,
                deepTargetLimit,
                ctx.deepAnalysisStarted(),
                ctx.deepAnalysisCompleted(),
                ctx.providerTimeoutCount(),
                ctx.skippedByDeadline(),
                diagnostics
        );
    }

    private ScanRunReport buildCompletedReport(ScanContext ctx, int universeCount, int hardFilterPassed,
                                               int deepTargetLimit, int deepCompleted, ScanDiagnostics diagnostics) {
        Instant finishedAt = Instant.now();
        return new ScanRunReport(
                ctx.scanId(),
                ScanRunStatus.COMPLETED,
                ctx.startedAt(),
                finishedAt,
                java.time.Duration.between(ctx.startedAt(), finishedAt).toMillis(),
                ctx.currentStage(),
                universeCount,
                hardFilterPassed,
                deepTargetLimit,
                deepCompleted,
                deepCompleted,
                ctx.providerTimeoutCount(),
                ctx.skippedByDeadline(),
                diagnostics
        );
    }

    private void logScanFinished(ScanContext ctx, ScanRunReport report) {
        String oiQuality = report.diagnostics() == null || report.diagnostics().providerStatus() == null
                ? "UNKNOWN"
                : String.valueOf(report.diagnostics().providerStatus().get("open_interest_history"));
        log.info("scanV2 finished scanId={} result={} stage={} durationMs={} universe={} hardFilterPassed={} "
                        + "deepTargetLimit={} deepAnalysisStarted={} deepAnalysisCompleted={} "
                        + "skippedByDeadline={} providerTimeoutCount={} oiDataQuality={} finalRankCount={}",
                report.scanId(),
                report.status(),
                report.currentStage(),
                report.elapsedMs(),
                report.universeCount(),
                report.hardFilterPassed(),
                report.deepTargetLimit(),
                report.deepAnalysisStarted(),
                report.deepAnalysisCompleted(),
                report.skippedByDeadline(),
                report.providerTimeoutCount(),
                oiQuality,
                report.diagnostics() == null ? 0 : report.diagnostics().finalRankPassedCount());
    }

    private ProviderResult<OpenInterestHistorySnapshot> fetchOpenInterestHistory(String instId, ScanContext ctx) {
        Duration timeout = ctx.requestTimeout();
        if (timeout.isZero()) {
            return ProviderResult.timeout("OI_TIMEOUT: deadline exceeded");
        }
        return openInterestHistoryProvider.fetch(instId, timeout);
    }

    private ProviderResult<FundingHistorySnapshot> fetchFundingHistory(String instId, ScanContext ctx) {
        Duration timeout = ctx.requestTimeout();
        if (timeout.isZero()) {
            return ProviderResult.timeout("FUNDING_TIMEOUT: deadline exceeded");
        }
        return fundingHistoryProvider.fetch(instId, timeout);
    }

    /**
     * Returns true only when every attempted enrichment provider returned usable/non-timeout data.
     * TIMEOUT/UNAVAILABLE still allow the candidate to publish, but do not count as deepAnalysisCompleted.
     */
    private static boolean enrichmentSucceeded(ScanContext ctx, ProviderResult<?>... results) {
        if (!ctx.acceptingResults() || ctx.isExpired()) {
            return false;
        }
        boolean complete = true;
        for (ProviderResult<?> result : results) {
            if (result == null) {
                continue;
            }
            if (result.dataQuality() == DataQuality.TIMEOUT) {
                ctx.recordProviderTimeout();
                complete = false;
            } else if (result.dataQuality() == DataQuality.UNAVAILABLE) {
                complete = false;
            }
        }
        return complete;
    }

    private static void trackProviderResult(ScanContext ctx, ProviderResult<?> result,
                                            String instId, String providerKind) {
        if (result == null) {
            return;
        }
        if (result.dataQuality() == DataQuality.TIMEOUT) {
            ctx.recordProviderTimeout();
        }
        if ("open_interest".equals(providerKind) && result.dataQuality() != DataQuality.FRESH) {
            log.debug("oiProviderStatus scanId={} instId={} dataQuality={} reason={}",
                    ctx.scanId(), instId, result.dataQuality(), result.errorMessage());
        }
    }

    private static void trackProviderResult(ScanContext ctx, ProviderResult<?> result) {
        if (result != null && result.dataQuality() == DataQuality.TIMEOUT) {
            ctx.recordProviderTimeout();
        }
    }

    private TickerSnapshot buildTickerSnapshot(JsonNode item) {
        String instId = item.path("instId").asText();
        if (!instId.endsWith("-USDT-SWAP")) {
            return null;
        }
        BigDecimal last = decimal(item, "last");
        BigDecimal open24h = decimal(item, "open24h");
        BigDecimal high24h = decimal(item, "high24h");
        BigDecimal low24h = decimal(item, "low24h");
        BigDecimal volCcy24h = nullableDecimal(item, "volCcy24h");
        BigDecimal vol24h = nullableDecimal(item, "vol24h");
        BigDecimal bidPx = decimal(item, "bidPx");
        BigDecimal askPx = decimal(item, "askPx");
        BigDecimal change24h = BigDecimal.ZERO;
        if (open24h.signum() > 0) {
            change24h = last.subtract(open24h)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(open24h, 4, RoundingMode.HALF_UP);
        }
        BigDecimal turnover24h = quoteTurnoverUsdt(volCcy24h, vol24h, last, null);
        if (turnover24h == null) {
            return null;
        }
        BigDecimal range24h = last.signum() > 0 && high24h.signum() > 0 && low24h.signum() > 0
                ? high24h.subtract(low24h).multiply(BigDecimal.valueOf(100)).divide(last, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal spreadPct = last.signum() > 0 && bidPx.signum() > 0 && askPx.signum() > 0
                ? askPx.subtract(bidPx).multiply(BigDecimal.valueOf(100)).divide(last, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new TickerSnapshot(instId, baseCurrency(instId), last, open24h, turnover24h,
                change24h, range24h, spreadPct);
    }

    private FilterContext toFilterContext(TickerSnapshot s, ProviderResult<MarketCapSnapshot> mcap) {
        BigDecimal range24hPct = s.range24hPct() != null
                ? s.range24hPct().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                : null;
        ProviderResult<MarketCapSnapshot> mcapSafe = mcap == null
                ? ProviderResult.unavailable("no marketcap data") : mcap;
        // Phase B V2：从 snapshot absolute supply 提前算 ratio，传给 CirculationFilter /
        // VolumeMcapFilter 的 ratio 优先路径。即使未来 provider 只填 ratio，filter 也能正常 PASS。
        BigDecimal circulationRatio = null;
        BigDecimal volumeMcapRatio = null;
        if (mcapSafe != null && mcapSafe.data() != null) {
            MarketCapSnapshot data = mcapSafe.data();
            BigDecimal max = data.maxSupply();
            if (max == null || max.signum() == 0) {
                max = data.totalSupply();
            }
            if (data.circulatingSupply() != null && max != null && max.signum() > 0) {
                circulationRatio = data.circulatingSupply()
                        .divide(max, 6, RoundingMode.HALF_UP);
            }
            if (s.volume24h() != null && s.volume24h().signum() > 0
                    && data.marketCapUsd() != null && data.marketCapUsd().signum() > 0) {
                volumeMcapRatio = s.volume24h()
                        .divide(data.marketCapUsd(), 6, RoundingMode.HALF_UP);
            }
        }
        return FilterContext.withV2Ratios(
                s.instId(),
                safeBaseOf(s.instId()),
                s.lastPrice(),
                s.volume24h(),
                range24hPct,
                null, null, null,
                mcapSafe,
                circulationRatio,
                volumeMcapRatio
        );
    }

    private DataQuality overallMcapStatus(Map<String, ProviderResult<MarketCapSnapshot>> mcap) {
        if (mcap.isEmpty()) {
            return DataQuality.UNAVAILABLE;
        }
        int unavailable = 0;
        for (ProviderResult<MarketCapSnapshot> r : mcap.values()) {
            if (r == null || r.dataQuality() == DataQuality.UNAVAILABLE
                    || r.dataQuality() == DataQuality.TIMEOUT) {
                unavailable++;
            }
        }
        if (unavailable == mcap.size()) {
            return DataQuality.UNAVAILABLE;
        }
        if (unavailable > 0) {
            return DataQuality.STALE;
        }
        return DataQuality.FRESH;
    }

    /**
     * Wave 2 — 构造 providerStatus map，包含 market_cap + open_interest_history + funding_history。
     * OI / Funding 仅对 deep target 拉取，deepTargets 为空时填 UNAVAILABLE。
     */
    private Map<String, DataQuality> buildProviderStatus(
            DataQuality mcapStatus,
            Map<String, OpenInterestHistorySnapshot> oiByInstId,
            Map<String, FundingHistorySnapshot> fundingByInstId,
            Map<String, DataQuality> relativeStrengthQualityByInstId,
            Map<String, DataQuality> athBreakoutQualityByInstId,
            int deepTargetCount
    ) {
        Map<String, DataQuality> out = new LinkedHashMap<>();
        out.put("market_cap", mcapStatus);
        // OI: deep target 全部成功 → FRESH；部分缺失 → STALE；全无 → UNAVAILABLE
        out.put("open_interest_history", derivativesStatus(
                oiByInstId.size(), deepTargetCount,
                openInterestHistoryProvider == null));
        out.put("funding_history", derivativesStatus(
                fundingByInstId.size(), deepTargetCount,
                fundingHistoryProvider == null));
        out.put("relative_strength", derivativesStatus(
                relativeStrengthQualityByInstId,
                relativeStrengthProvider == null));
        out.put("ath_breakout", derivativesStatus(
                athBreakoutQualityByInstId, athBreakoutProvider == null));
        return out;
    }

    private static DataQuality derivativesStatus(Map<String, DataQuality> qualities, boolean disabled) {
        if (disabled || qualities.isEmpty()) {
            return DataQuality.UNAVAILABLE;
        }
        boolean stale = false;
        boolean usable = false;
        for (DataQuality quality : qualities.values()) {
            if (quality == DataQuality.STALE) {
                stale = true;
            }
            if (quality == DataQuality.FRESH || quality == DataQuality.AVAILABLE) {
                usable = true;
            }
        }
        return stale ? DataQuality.STALE : usable ? DataQuality.FRESH : DataQuality.UNAVAILABLE;
    }

    private static DataQuality derivativesStatus(int successCount, int targetCount, boolean disabled) {
        if (disabled) {
            return DataQuality.UNAVAILABLE;
        }
        if (targetCount == 0) {
            return DataQuality.UNAVAILABLE;
        }
        if (successCount == targetCount) {
            return DataQuality.FRESH;
        }
        if (successCount > 0) {
            return DataQuality.STALE;
        }
        return DataQuality.UNAVAILABLE;
    }

    private String safeBaseOf(String instId) {
        try {
            return TokenSymbolMapper.mapSwapToBase(instId);
        } catch (TokenSymbolMapper.SymbolMappingException ex) {
            return null;
        }
    }

    private static final int MARKET_CAP_BATCH_SIZE = 50;

    private Map<String, ProviderResult<MarketCapSnapshot>> collectMarketCaps(
            List<TickerSnapshot> snapshots,
            ScanContext ctx
    ) {
        Map<String, ProviderResult<MarketCapSnapshot>> mcapByBase = new LinkedHashMap<>();
        if (ctx.isExpired()) {
            ctx.markPartialTimeout();
            return mcapByBase;
        }
        Map<String, BigDecimal> volumeByBase = new LinkedHashMap<>();
        for (TickerSnapshot s : snapshots) {
            String base = safeBaseOf(s.instId());
            if (base == null) {
                mcapByBase.put(s.instId(), ProviderResult.unavailable("instId not mappable"));
                continue;
            }
            BigDecimal vol = s.volume24h() == null ? BigDecimal.ZERO : s.volume24h();
            volumeByBase.merge(base, vol, (left, right) -> left.compareTo(right) >= 0 ? left : right);
        }
        List<String> basesByVolume = volumeByBase.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        if (log.isDebugEnabled()) {
            log.debug("collectMarketCaps provider={} bases={}", marketCapProvider.getClass().getSimpleName(),
                    basesByVolume.size());
        }
        ExecutorService batchLoader = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < basesByVolume.size(); i += MARKET_CAP_BATCH_SIZE) {
                if (ctx.isExpired() || !ctx.acceptingResults()) {
                    ctx.markPartialTimeout();
                    break;
                }
                List<String> chunk = basesByVolume.subList(i, Math.min(i + MARKET_CAP_BATCH_SIZE, basesByVolume.size()));
                List<String> missing = new ArrayList<>();
                for (String base : chunk) {
                    Optional<ProviderResult<MarketCapSnapshot>> cached = marketCapCache.peek(base);
                    if (cached.isPresent()) {
                        mcapByBase.put(base, cached.orElseThrow());
                    } else {
                        missing.add(base);
                    }
                }
                if (missing.isEmpty()) {
                    continue;
                }
                Duration waitBudget = ctx.requestTimeout();
                if (waitBudget.isZero()) {
                    ctx.markPartialTimeout();
                    break;
                }
                Map<String, ProviderResult<MarketCapSnapshot>> batch;
                try {
                    Future<Map<String, ProviderResult<MarketCapSnapshot>>> future = batchLoader.submit(
                            () -> marketCapProvider.fetchBatch(missing, waitBudget));
                    batch = future.get(waitBudget.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException ex) {
                    ctx.markPartialTimeout();
                    ctx.recordProviderTimeout();
                    break;
                } catch (ExecutionException ex) {
                    log.warn("collectMarketCaps batch failed scanId={} message={}",
                            ctx.scanId(), ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage());
                    break;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    ctx.markPartialTimeout();
                    break;
                }
                if (ctx.isExpired() || !ctx.acceptingResults()) {
                    ctx.markPartialTimeout();
                    break;
                }
                for (String base : missing) {
                    ProviderResult<MarketCapSnapshot> result = batch.getOrDefault(
                            base,
                            batch.getOrDefault(base.toUpperCase(), ProviderResult.unavailable("no marketcap data")));
                    mcapByBase.put(base, result);
                    if (ctx.acceptingResults() && !ctx.isExpired()) {
                        marketCapCache.seed(base, result);
                    }
                }
            }
        } finally {
            batchLoader.shutdownNow();
            try {
                batchLoader.awaitTermination(ScanContext.CLEANUP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        return mcapByBase;
    }

    private ScanDiagnostics emptyDiagnostics() {
        return ScanDiagnostics.fromV1(null, 0, Map.of(), 0);
    }

    private ContractScoreBreakdown adjustedScoreBreakdown(
            ContractScoreBreakdown score,
            OrderBookLiquiditySnapshot liquidity,
            MarketEnvironment environment,
            DirectionBias direction,
            ContractNewsRiskAnalysis newsRisk,
            BigDecimal turnover24h
    ) {
        int liquidityScore = liquidityScore(liquidity, turnover24h);
        int marketScore = Math.max(0, Math.min(100, environment.scoreFor(direction) * 10));
        return new ContractScoreBreakdown(
                score.trend(),
                score.volume(),
                liquidityScore,
                score.volatility(),
                score.oiFunding(),
                marketScore,
                newsRisk.newsRiskScore()
        );
    }

    private int liquidityScore(OrderBookLiquiditySnapshot liquidity, BigDecimal turnover24h) {
        int turnoverScore = ContractSignalAnalyzer.turnoverScore(turnover24h);
        int spreadScore;
        if (!liquidity.tradable()) {
            spreadScore = 20;
        } else if (liquidity.spreadBps().compareTo(BigDecimal.valueOf(4)) <= 0
                && liquidity.availableDepthUsdt().compareTo(BigDecimal.valueOf(500_000)) >= 0) {
            spreadScore = 95;
        } else if (liquidity.spreadBps().compareTo(BigDecimal.valueOf(6)) <= 0
                && liquidity.availableDepthUsdt().compareTo(BigDecimal.valueOf(200_000)) >= 0) {
            spreadScore = 82;
        } else {
            spreadScore = 65;
        }
        int depthScore = liquidity.availableDepthUsdt().compareTo(BigDecimal.valueOf(500_000)) >= 0 ? 95
                : liquidity.availableDepthUsdt().compareTo(agentProperties.market().minDepthUsdt()) >= 0 ? 80 : 25;
        return (int) Math.round(turnoverScore * 0.40 + spreadScore * 0.30 + depthScore * 0.30);
    }

    private ContractKlineSet safeKlines(String instId) {
        return safeKlines(instId, null);
    }

    private ContractKlineSet safeKlines(String instId, ScanContext ctx) {
        if (ctx != null && (ctx.isExpired() || !ctx.acceptingResults())) {
            return new ContractKlineSet(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<com.example.quant.crypto.dto.ContractCandle> fiveMinute = safeCandles(instId, "5m", 260, ctx);
        List<com.example.quant.crypto.dto.ContractCandle> twentyMinute =
                com.example.quant.crypto.util.CandleAggregator.aggregate20m(fiveMinute);
        List<com.example.quant.crypto.dto.ContractCandle> oneHour = safeCandles(instId, "1H", 72, ctx);
        List<com.example.quant.crypto.dto.ContractCandle> fourHour = safeCandles(instId, "4H", 60, ctx);
        List<com.example.quant.crypto.dto.ContractCandle> oneDay = safeCandles(instId, "1D", 30, ctx);
        return new ContractKlineSet(twentyMinute, fiveMinute, oneHour, fourHour, oneDay);
    }

    private List<com.example.quant.crypto.dto.ContractCandle> safeCandles(String instId, String bar, int limit) {
        return safeCandles(instId, bar, limit, null);
    }

    private List<com.example.quant.crypto.dto.ContractCandle> safeCandles(
            String instId, String bar, int limit, ScanContext ctx) {
        if (ctx != null && (ctx.isExpired() || !ctx.acceptingResults())) {
            return List.of();
        }
        try {
            if (ctx != null) {
                return okxMarketService.candles(instId, bar, limit, ctx.requestTimeout());
            }
            return okxMarketService.candles(instId, bar, limit);
        } catch (RuntimeException ex) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    private BigDecimal finalRankScore(int adjustedScore, ContractScoreBreakdown scoreBreakdown, ContractSignal signal,
                                      OrderBookLiquiditySnapshot liquidity) {
        BigDecimal finalScore = BigDecimal.valueOf(adjustedScore);
        if (!liquidity.tradable() || !isTradableSignal(signal.signalType())) {
            finalScore = finalScore.min(BigDecimal.valueOf(60));
        }
        if (scoreBreakdown.liquidity() < 50) {
            finalScore = finalScore.min(BigDecimal.valueOf(55));
        }
        if (scoreBreakdown.volume() < 50) {
            finalScore = finalScore.min(BigDecimal.valueOf(60));
        }
        if (signal.signalType() == ContractSignalType.WAIT_OVERHEATED
                || signal.signalType() == ContractSignalType.WAIT_OVERSOLD) {
            // V2: signalType 与 selectionScore 解耦，移除 WAIT_* → 55 的硬 ceiling。
            // 保留流动性 / 成交量子项的硬约束（liquidity<50、volume<50），它们属于 MarketQuality。
            // 交易"是不是好的入场时机"由 tradeable / action 字段表达，不由 selectionScore 上限表达。
            log.debug("V2: signalType={} no longer caps finalRankScore (kept: liquidity<50, volume<50 guards)",
                    signal.signalType());
        }
        return finalScore.setScale(2, RoundingMode.HALF_UP);
    }

    String adjustedAction(ContractSignalType signalType, String entryTiming5m, int adjustedScore,
                          ContractNewsRiskAnalysis newsRisk, OrderBookLiquiditySnapshot liquidity,
                          BigDecimal finalRankScore) {
        if (!liquidity.tradable() || "CRITICAL".equals(newsRisk.newsRiskLevel()) || "HIGH".equals(newsRisk.newsRiskLevel())) {
            return "NO_TRADE";
        }
        if (newsRisk.decision() == null || !newsRisk.decision().allowTrade()) {
            return "WAIT";
        }
        if (finalRankScore.compareTo(BigDecimal.valueOf(agentProperties.ranking().minFinalRankScore())) < 0) {
            return "WAIT";
        }
        return ContractSignalAnalyzer.action(signalType, adjustedScore, entryTiming5m, newsRisk,
                agentProperties.score().minTotalScore());
    }

    private BigDecimal safeFundingRate(String instId) {
        try {
            JsonNode root = okxRestClient.publicGet("/api/v5/public/funding-rate?instId=" + OkxRestClient.encode(instId));
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                return nullableDecimal(data.get(0), "fundingRate");
            }
        } catch (Exception ex) {
            log.debug("funding rate unavailable instId={} message={}", instId, ex.getMessage());
        }
        return null;
    }

    /**
     * 拉取当前 OI。{@code openInterest} 公共字段只承载币数量（oiCcy）；
     * 资金流评分使用 {@code openInterestUsd}（优先 oiUsd，USDT-SWAP 可 oiCcy×lastPrice）。
     * 请求失败 / 空响应 / 字段缺失返回两侧均为 null，绝不默认成零。
     */
    private OpenInterestReading fetchOpenInterestReading(String instId, BigDecimal lastPrice) {
        try {
            JsonNode root = okxRestClient.publicGet("/api/v5/public/open-interest?instType=SWAP&instId="
                    + OkxRestClient.encode(instId));
            if (root == null || !"0".equals(root.path("code").asText(null))) {
                return OpenInterestReading.unavailable();
            }
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return OpenInterestReading.unavailable();
            }
            return OpenInterestReading.fromOkxRow(instId, data.get(0), lastPrice);
        } catch (Exception ex) {
            log.debug("open interest fetch failed instId={} message={}", instId, ex.getMessage());
            return OpenInterestReading.unavailable();
        }
    }

    /** @deprecated 仅保留给旧测试反射；生产路径使用 {@link #fetchOpenInterestReading}。 */
    private BigDecimal safeOpenInterest(String instId) {
        return fetchOpenInterestReading(instId, null).coinAmount();
    }

    /**
     * OKX {@code /api/v5/public/open-interest} 单行解析。
     * <ul>
     *   <li>{@code oi}：合约张数，不得当作美元；</li>
     *   <li>{@code oiCcy}：币数量，写入 {@code openInterest}；</li>
     *   <li>{@code oiUsd}：美元名义价值（优先）；缺失时仅对 USDT-SWAP 用 oiCcy×lastPrice。</li>
     * </ul>
     */
    static final class OpenInterestReading {
        private final BigDecimal coinAmount;
        private final BigDecimal usdNotional;

        private final long timestamp;

        private OpenInterestReading(BigDecimal coinAmount, BigDecimal usdNotional, long timestamp) {
            this.timestamp = timestamp;
            this.coinAmount = coinAmount;
            this.usdNotional = usdNotional;
        }

        static OpenInterestReading unavailable() {
            return new OpenInterestReading(null, null, 0);
        }

        static OpenInterestReading fromOkxRow(String instId, JsonNode row, BigDecimal lastPrice) {
            if (row == null || row.isMissingNode() || row.isNull()) {
                return unavailable();
            }
            BigDecimal oiCcy = nullableDecimal(row, "oiCcy");
            BigDecimal oiUsd = nullableDecimal(row, "oiUsd");
            BigDecimal usd = null;
            if (oiUsd != null) {
                usd = oiUsd;
            } else if (oiCcy != null && lastPrice != null && lastPrice.signum() > 0
                    && instId != null && instId.endsWith("-USDT-SWAP")) {
                usd = oiCcy.multiply(lastPrice);
            }
            if (!row.path("instId").asText(instId).equals(instId)) {
                return unavailable();
            }
            return new OpenInterestReading(oiCcy, usd, row.path("ts").asLong(0));
        }

        BigDecimal coinAmount() {
            return coinAmount;
        }

        BigDecimal usdNotional() {
            return usdNotional;
        }
    }

    /**
     * 将 OKX ticker 成交量换算为 USDT 名义成交额。
     *
     * <p>{@code volCcy24h} 是基础币数量（非美元），应乘以 {@code last}；
     * {@code vol24h} 是合约张数，回退路径必须再乘 {@code ctVal}。
     * 不可计算时返回 {@code null}（数据不可用），不得伪装成真实零成交额参与选币或评分。
     */
    static BigDecimal quoteTurnoverUsdt(BigDecimal volCcy24h, BigDecimal vol24h,
                                        BigDecimal last, BigDecimal ctVal) {
        BigDecimal price = last == null ? BigDecimal.ZERO : last;
        if (price.signum() <= 0) {
            return null;
        }
        if (volCcy24h != null && volCcy24h.signum() > 0) {
            return volCcy24h.multiply(price);
        }
        if (vol24h != null && vol24h.signum() > 0 && ctVal != null && ctVal.signum() > 0) {
            return vol24h.multiply(ctVal).multiply(price);
        }
        return null;
    }

    private static BigDecimal nullableDecimal(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = node.path(field).asText("0");
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    private static String baseCurrency(String instId) {
        int index = instId.indexOf('-');
        return index > 0 ? instId.substring(0, index) : instId;
    }

    private static List<String> mergedReasons(BigDecimal change24h, BigDecimal volume24h, DirectionBias direction,
                                              List<String> signalReasons,
                                              OrderBookLiquiditySnapshot liquidity,
                                              MarketEnvironment environment,
                                              ContractSignalType signalType,
                                              ContractNewsRiskAnalysis newsRisk) {
        List<String> reasons = new ArrayList<>();
        reasons.add("信号类型：" + signalType);
        reasons.add(direction == DirectionBias.BULLISH ? "24h价格强于开盘价" : "24h价格弱于开盘价");
        if (change24h.abs().compareTo(BigDecimal.valueOf(8)) >= 0) {
            reasons.add("24h波动幅度较大");
        } else if (change24h.abs().compareTo(BigDecimal.valueOf(2)) >= 0) {
            reasons.add("24h方向变化明显");
        }
        if (volume24h.compareTo(BigDecimal.valueOf(1_000_000_000L)) >= 0) {
            reasons.add("24h成交量进入流动性候选池");
        }
        reasons.addAll(signalReasons);
        if (liquidity.tradable()) {
            reasons.add("订单簿价差与深度满足流动性门控");
        }
        if (!environment.conflictsWith(direction)) {
            reasons.add("BTC/ETH市场环境未与候选方向明显冲突");
        }
        reasons.add("新闻风险等级：" + newsRisk.newsRiskLevel() + "，安全分=" + newsRisk.newsRiskScore());
        return reasons;
    }

    private static List<String> mergedRisks(List<String> signalRisks,
                                            OrderBookLiquiditySnapshot liquidity,
                                            MarketEnvironment environment,
                                            DirectionBias direction,
                                            ContractNewsRiskAnalysis newsRisk,
                                            String action) {
        List<String> risks = new ArrayList<>(signalRisks);
        if (!liquidity.tradable()) {
            risks.add("订单簿流动性不足：" + liquidity.denyReason());
        }
        if (environment.conflictsWith(direction)) {
            risks.add("BTC/ETH市场环境与候选方向冲突");
        } else if (environment.partiallyConflictsWith(direction)) {
            risks.add("BTC/ETH市场环境存在单边冲突");
        }
        if (environment.riskLevel().ordinal() >= com.example.quant.risk.RiskLevel.HIGH.ordinal()) {
            risks.add("BTC/ETH短周期市场风险偏高");
        }
        if (!newsRisk.decision().allowTrade()) {
            risks.add("新闻风险限制：" + newsRisk.decision().reason());
        }
        if (!"AUTO_TRADE_ALLOWED".equals(action)) {
            risks.add("候选动作不是AUTO_TRADE_ALLOWED：" + action);
        }
        return risks;
    }

    private boolean firstPlaceEligible(ContractCandidate candidate) {
        return isTradableSignal(candidate.signalType())
                && "AUTO_TRADE_ALLOWED".equals(candidate.action())
                && candidate.scoreBreakdown().liquidity() >= 70
                && candidate.scoreBreakdown().volume() >= 60
                && candidate.riskRewardRatio().compareTo(agentProperties.ranking().minRiskReward()) >= 0
                && candidate.stopLossPct().compareTo(BigDecimal.valueOf(5)) <= 0
                && candidate.fundingRate().abs().compareTo(agentProperties.market().maxFundingAbs().multiply(BigDecimal.valueOf(2))) <= 0;
    }

    private static boolean isTradableSignal(ContractSignalType signalType) {
        return signalType == ContractSignalType.STRONG_LONG
                || signalType == ContractSignalType.PULLBACK_LONG
                || signalType == ContractSignalType.TREND_SHORT
                || signalType == ContractSignalType.REVERSAL_SHORT;
    }

    private record TickerSnapshot(
            String instId,
            String baseCurrency,
            BigDecimal lastPrice,
            BigDecimal open24h,
            BigDecimal volume24h,
            BigDecimal change24h,
            BigDecimal range24hPct,
            BigDecimal spreadPct
    ) {
    }
}

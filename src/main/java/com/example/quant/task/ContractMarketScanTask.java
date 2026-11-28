package com.example.quant.task;

import com.example.quant.agent.execution.AutoTradeService;
import com.example.quant.agent.execution.AutoTradeService.AutoTradeResult;
import com.example.quant.agent.execution.PositionCapacityScanGate;
import com.example.quant.agent.evaluation.ShadowSelectionService;
import com.example.quant.agent.scan.ScanContext;
import com.example.quant.agent.scan.ScanRunReport;
import com.example.quant.agent.scan.ScanRunStatus;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.ContractProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.ContractScanResult;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.ContractAnalysisService;
import com.example.quant.strategydecision.StrategyDecisionService;
import com.example.quant.strategydecision.StrategyDecisionStatus;
import com.example.quant.system.SystemControlService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ContractMarketScanTask {
    private static final Logger log = LoggerFactory.getLogger(ContractMarketScanTask.class);

    private final ContractAnalysisService contractAnalysisService;
    private final AutoTradeService autoTradeService;
    private final AgentProperties agentProperties;
    private final StrategyDecisionService strategyDecisionService;
    private final SystemControlService systemControlService;
    private PositionCapacityScanGate positionCapacityScanGate;
    private ContractProperties contractProperties;
    private ShadowSelectionService shadowSelectionService;
    private TradingProperties tradingProperties;
    private com.example.quant.agent.platform.TradingAgentOrchestrator agentOrchestrator;
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private volatile String runningScanId;
    private volatile Instant runningScanStartedAt;
    private volatile long lastSkipWarnAtMs;
    private volatile String lastSkipWarnPreviousScanId;

    public ContractMarketScanTask(ContractAnalysisService contractAnalysisService, AutoTradeService autoTradeService) {
        this(contractAnalysisService, autoTradeService, new AgentProperties(), null, null);
    }

    public ContractMarketScanTask(ContractAnalysisService contractAnalysisService, AutoTradeService autoTradeService,
                                  AgentProperties agentProperties) {
        this(contractAnalysisService, autoTradeService, agentProperties, null, null);
    }

    @Autowired
    public ContractMarketScanTask(ContractAnalysisService contractAnalysisService, AutoTradeService autoTradeService,
                                  AgentProperties agentProperties, StrategyDecisionService strategyDecisionService,
                                  SystemControlService systemControlService) {
        this.contractAnalysisService = contractAnalysisService;
        this.autoTradeService = autoTradeService;
        this.agentProperties = agentProperties;
        this.strategyDecisionService = strategyDecisionService;
        this.systemControlService = systemControlService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        runOnce();
    }

    @Autowired
    void configureContractProperties(ContractProperties contractProperties) {
        this.contractProperties = contractProperties;
    }

    @Autowired(required = false)
    void configureScanCapacityGate(PositionCapacityScanGate positionCapacityScanGate) {
        this.positionCapacityScanGate = positionCapacityScanGate;
    }

    @Autowired(required = false)
    void configureShadowSelection(ShadowSelectionService shadowSelectionService,
                                  TradingProperties tradingProperties) {
        this.shadowSelectionService = shadowSelectionService;
        this.tradingProperties = tradingProperties;
    }

    @Autowired(required = false)
    void configureAgentOrchestrator(com.example.quant.agent.platform.TradingAgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    @Scheduled(
            fixedRateString = "${quant.contract.scan-interval-seconds:60}",
            initialDelayString = "${quant.contract.scan-interval-seconds:60}",
            timeUnit = TimeUnit.SECONDS
    )
    public void runHourly() {
        runOnce();
    }

    public ScanTaskLog runOnce() {
        String scanId = UUID.randomUUID().toString();
        if (contractProperties != null && !contractProperties.enabled()) {
            return new ScanTaskLog("contract-market-scan", "SKIPPED",
                    "scanId=" + scanId + ", reason=CONTRACT_MODULE_DISABLED", Instant.now());
        }
        if (positionCapacityScanGate != null) {
            PositionCapacityScanGate.Decision capacity = positionCapacityScanGate.evaluate();
            if (capacity.skipScan()) {
                ScanTaskLog result = new ScanTaskLog(
                        "contract-market-scan",
                        "SKIPPED",
                        "scanId=" + scanId
                                + ", reason=" + capacity.reason()
                                + ", openOrPending=" + capacity.openOrPendingCount()
                                + ", maxOpenPositions=" + capacity.maxOpenPositions()
                                + ", message=new-entry candidate scan paused until position capacity is available",
                        Instant.now());
                log.info("{}: {}", result.taskName(), result.message());
                return result;
            }
        }
        if (agentProperties.concurrency().scanLockEnabled() && !scanRunning.compareAndSet(false, true)) {
            ScanTaskLog result = buildSkippedLog(scanId);
            logSkipped(result);
            return result;
        }
        runningScanId = scanId;
        runningScanStartedAt = Instant.now();
        long durationMs = 0L;
        try {
            ContractScanResult scanResult = contractAnalysisService.refreshScan(scanId);
            List<ContractCandidate> candidates = scanResult.candidates();
            ScanRunReport report = scanResult.report();
            ScanRunStatus scanStatus = scanResult.status();
            recordShadowSelections(scanId, candidates);
            if (scanStatus != ScanRunStatus.COMPLETED) {
                if (scanStatus == ScanRunStatus.PARTIAL_TIMEOUT
                        && agentOrchestrator != null
                        && candidates != null
                        && !candidates.isEmpty()) {
                    try {
                        int dispatchedAgents = agentOrchestrator.dispatchScan(scanId, candidates, scanResult.v2Scores());
                        if (dispatchedAgents > 0) {
                            log.info("contract-market-scan dispatched on partial timeout scanId={} candidates={} activeAgents={}",
                                    scanId, candidates.size(), dispatchedAgents);
                        }
                    } catch (RuntimeException ex) {
                        log.warn("contract-market-scan partial agent dispatch failed scanId={} error={}",
                                scanId, ex.getMessage());
                    }
                }
                String reason = report == null ? "SCAN_REPORT_MISSING" : scanStatus.name();
                ScanTaskLog result = new ScanTaskLog(
                        "contract-market-scan",
                        scanStatus.name(),
                        buildFinishedMessage(scanId, report, candidates.size(), null, true)
                                + ", reason=" + reason,
                        Instant.now()
                );
                if (report == null) {
                    log.warn("{}: scanId={} status=UNKNOWN reason=SCAN_REPORT_MISSING message={}",
                            result.taskName(), scanId, result.message());
                } else {
                    log.warn("{}: {}", result.taskName(), result.message());
                }
                return result;
            }
            DecisionBatch decisionBatch = claimDecisions(scanResult, Instant.now());
            Duration elapsed = Duration.between(runningScanStartedAt, Instant.now());
            boolean slowScan = elapsed.compareTo(
                    Duration.ofSeconds(agentProperties.timeout().scanRoundTimeoutSeconds())) > 0;
            AutoTradeResult autoTrade;
            if (strategyDecisionService != null && decisionBatch.candidates().isEmpty()) {
                autoTrade = new AutoTradeResult("SKIPPED", null, null, null, null, null, null, 0,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        "duplicate_or_missing_closed_candle", Instant.now());
            } else {
                String coverage = coverageSummary(report);
                try (var scope = org.slf4j.MDC.putCloseable("scanCoverage", coverage);
                     var scanScope = org.slf4j.MDC.putCloseable("coverageScanId", scanId)) {
                    if (scanResult.v2Scores().isEmpty()) {
                        autoTrade = strategyDecisionService == null
                                ? autoTradeService.evaluateAndExecute(decisionBatch.candidates())
                                : autoTradeService.evaluateAndExecute(
                                        decisionBatch.candidates(), decisionBatch.clientOrderIds());
                    } else {
                        autoTrade = strategyDecisionService == null
                                ? autoTradeService.evaluateAndExecute(
                                        decisionBatch.candidates(), Map.of(), scanResult.v2Scores())
                                : autoTradeService.evaluateAndExecute(
                                        decisionBatch.candidates(), decisionBatch.clientOrderIds(), scanResult.v2Scores());
                    }
                }
            }
            completeDecisions(decisionBatch.claims(), autoTrade, Instant.now());
            if (agentOrchestrator != null) {
                try {
                    int dispatchedAgents = agentOrchestrator.dispatchScan(scanId, candidates, scanResult.v2Scores());
                    if (dispatchedAgents > 0) {
                        log.info("contract-market-scan dispatched scanId={} activeAgents={}", scanId, dispatchedAgents);
                    }
                } catch (RuntimeException ex) {
                    log.warn("contract-market-scan agent dispatch failed scanId={} error={}", scanId, ex.getMessage());
                }
            }
            String timingMessage = slowScan
                    ? ", warning=SCAN_SLOW, elapsedSeconds=" + elapsed.toSeconds()
                    : "";
            ScanTaskLog result = new ScanTaskLog(
                    "contract-market-scan",
                    "SUCCESS",
                    buildFinishedMessage(scanId, report, candidates.size(), autoTrade, false)
                            + ", executionCandidates=" + decisionBatch.candidates().size() + timingMessage,
                    Instant.now()
            );
            log.info("{}: {}", result.taskName(), result.message());
            return result;
        } catch (RuntimeException ex) {
            durationMs = elapsedMs(runningScanStartedAt);
            ScanTaskLog result = new ScanTaskLog(
                    "contract-market-scan",
                    "FAILED",
                    "scanId=" + scanId
                            + ", previousScanId="
                            + ", status=FAILED"
                            + ", durationMs=" + durationMs
                            + ", message=Contract signal scan failed: "
                            + compact(ex.getMessage()),
                    Instant.now()
            );
            log.warn("{}: {}", result.taskName(), result.message(), ex);
            return result;
        } finally {
            if (durationMs == 0L && runningScanStartedAt != null) {
                durationMs = elapsedMs(runningScanStartedAt);
            }
            runningScanId = null;
            runningScanStartedAt = null;
            if (agentProperties.concurrency().scanLockEnabled()) {
                scanRunning.set(false);
            }
            log.debug("contract-market-scan scanId={} durationMs={} lockReleased=true", scanId, durationMs);
        }
    }

    private void logSkipped(ScanTaskLog result) {
        String previousScanId = runningScanId;
        long now = System.currentTimeMillis();
        boolean duplicate = previousScanId != null
                && previousScanId.equals(lastSkipWarnPreviousScanId)
                && now - lastSkipWarnAtMs < 60_000L;
        if (duplicate) {
            log.debug("{}: {}", result.taskName(), result.message());
            return;
        }
        lastSkipWarnAtMs = now;
        lastSkipWarnPreviousScanId = previousScanId;
        log.warn("{}: {}", result.taskName(), result.message());
    }

    private static long elapsedMs(Instant startedAt) {
        return startedAt == null ? -1L : Duration.between(startedAt, Instant.now()).toMillis();
    }

    private ScanTaskLog buildSkippedLog(String scanId) {
        String previousScanId = runningScanId;
        long runningMs = runningScanStartedAt == null
                ? -1
                : Duration.between(runningScanStartedAt, Instant.now()).toMillis();
        ScanContext running = contractAnalysisService.runningScanContext();
        String stage = running == null ? "unknown" : running.currentStage();
        long timeoutMs = Math.max(1, agentProperties.timeout().scanRoundTimeoutSeconds()) * 1000L;
        boolean stuck = runningMs >= timeoutMs * 2;
        String reason = stuck ? "SCAN_STUCK" : "SCAN_ALREADY_RUNNING";
        return new ScanTaskLog(
                "contract-market-scan",
                "SKIPPED",
                "scanId=" + scanId
                        + ", previousScanId=" + previousScanId
                        + ", runningScanId=" + previousScanId
                        + ", runningMs=" + runningMs
                        + ", currentStage=" + stage
                        + ", result=" + reason
                        + ", reason=" + reason,
                Instant.now()
        );
    }

    private static String buildFinishedMessage(String scanId, ScanRunReport report, int recommendationCount,
                                               AutoTradeResult autoTrade, boolean autoTradeSkipped) {
        StringBuilder message = new StringBuilder("scanId=").append(scanId);
        if (report != null) {
            message.append(", result=").append(report.status())
                    .append(", startedAt=").append(report.startedAt())
                    .append(", finishedAt=").append(report.finishedAt())
                    .append(", elapsedMs=").append(report.elapsedMs())
                    .append(", currentStage=").append(report.currentStage())
                    .append(", universe=").append(report.universeCount())
                    .append(", hardFilterPassed=").append(report.hardFilterPassed())
                    .append(", deepTargetLimit=").append(report.deepTargetLimit())
                    .append(", deepAnalysisStarted=").append(report.deepAnalysisStarted())
                    .append(", deepAnalysisCompleted=").append(report.deepAnalysisCompleted())
                    .append(", providerTimeoutCount=").append(report.providerTimeoutCount())
                    .append(", skippedByDeadline=").append(report.skippedByDeadline());
        }
        message.append(", status=").append(report == null ? "UNKNOWN" : report.status());
        message.append(", durationMs=").append(report == null ? -1 : report.elapsedMs());
        message.append(", recommendations=").append(recommendationCount);
        if (autoTradeSkipped) {
            message.append(", autoTrade=SKIPPED, autoMessage=scan_not_completed");
        } else if (autoTrade != null) {
            message.append(", autoTrade=").append(autoTrade.status())
                    .append(", autoMessage=").append(compact(autoTrade.message()));
        }
        message.append(", lockReleased=true");
        return message.toString();
    }

    private static String coverageSummary(ScanRunReport report) {
        if (report == null || report.diagnostics() == null) return "";
        var diagnostics = report.diagnostics();
        var stages = diagnostics.funnel().stages();
        if (!stages.containsKey("analyzed_signals")) return "";
        return "初筛合格 " + report.hardFilterPassed() + "，近30分钟已覆盖 " + stages.get("analyzed_last_30m")
                + "，本轮分析 " + stages.get("analyzed_signals")
                + "，补充数据完整 " + report.deepAnalysisCompleted()
                + "，轮换名额 " + stages.get("rotation_targets") + "，可交易类型信号 " + diagnostics.signalPassedCount();
    }

    private void recordShadowSelections(String scanId, List<ContractCandidate> candidates) {
        if (shadowSelectionService == null) return;
        try {
            String venue = tradingProperties == null ? "UNKNOWN" : tradingProperties.mode().name();
            shadowSelectionService.recordScan(scanId, candidates, venue);
        } catch (RuntimeException ex) {
            log.warn("Shadow selection persistence failed without affecting execution: scanId={}, message={}",
                    scanId, ex.getMessage());
        }
    }

    private DecisionBatch claimDecisions(ContractScanResult scanResult, Instant now) {
        List<ContractCandidate> published = scanResult.candidates();
        if (strategyDecisionService == null || systemControlService == null) {
            return new DecisionBatch(published, Map.of(), Map.of());
        }
        String owner = systemControlService.autoTradeOwnerUsername();
        if (owner == null || owner.isBlank()) {
            owner = "local-admin";
        }
        List<ContractCandidate> ordered = new ArrayList<>(published);
        ordered.addAll(scanResult.reserveCandidates());
        List<ContractCandidate> claimedCandidates = new ArrayList<>();
        Map<String, String> clientOrderIds = new LinkedHashMap<>();
        Map<String, StrategyDecisionService.DecisionClaim> claims = new LinkedHashMap<>();
        for (ContractCandidate candidate : ordered) {
            if (claimedCandidates.size() >= published.size()) {
                break;
            }
            Instant candle = candidate == null || candidate.klineAnalysis() == null
                    ? null : candidate.klineAnalysis().decisionCandleTimestamp();
            if (candidate == null || candidate.instId() == null || candle == null) {
                continue;
            }
            StrategyDecisionService.DecisionClaim claim = strategyDecisionService.claim(
                    owner, "AUTO_CONTRACT_V1", candidate.instId(), "5m", candle, now);
            if (!claim.claimed()) {
                continue;
            }
            claimedCandidates.add(candidate);
            clientOrderIds.put(candidate.instId(), claim.clientOrderId());
            claims.put(candidate.instId(), claim);
        }
        return new DecisionBatch(List.copyOf(claimedCandidates), Map.copyOf(clientOrderIds), Map.copyOf(claims));
    }

    private void completeDecisions(Map<String, StrategyDecisionService.DecisionClaim> claims,
                                   AutoTradeResult result, Instant now) {
        if (strategyDecisionService == null || claims == null || claims.isEmpty()) {
            return;
        }
        for (Map.Entry<String, StrategyDecisionService.DecisionClaim> entry : claims.entrySet()) {
            boolean selected = result != null && entry.getKey().equalsIgnoreCase(result.instId());
            StrategyDecisionStatus status = selected ? decisionStatus(result.status()) : StrategyDecisionStatus.NO_SIGNAL;
            strategyDecisionService.complete(entry.getValue().decisionId(), status,
                    selected ? result.pendingOrderId() : null,
                    selected ? result.okxOrderId() : null,
                    result == null ? "AUTO_TRADE_RESULT_MISSING" : result.message(), now);
        }
    }

    private static StrategyDecisionStatus decisionStatus(String status) {
        if ("WAITING_ENTRY".equals(status)) {
            return StrategyDecisionStatus.WAITING_ENTRY;
        }
        if ("UNKNOWN_SUBMIT_STATUS".equals(status)) {
            return StrategyDecisionStatus.RECONCILING;
        }
        if ("ENTRY_SUBMITTED".equals(status) || "EXECUTED".equals(status)) {
            return StrategyDecisionStatus.ORDER_SUBMITTED;
        }
        if ("FAILED".equals(status) || "REJECTED".equals(status)) {
            return StrategyDecisionStatus.FAILED_SAFE;
        }
        return StrategyDecisionStatus.NO_SIGNAL;
    }

    private record DecisionBatch(List<ContractCandidate> candidates, Map<String, String> clientOrderIds,
                                 Map<String, StrategyDecisionService.DecisionClaim> claims) {
    }

    private static String compact(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }
}

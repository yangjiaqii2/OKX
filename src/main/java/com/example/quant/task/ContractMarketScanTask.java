package com.example.quant.task;

import com.example.quant.agent.execution.AutoTradeService;
import com.example.quant.agent.execution.AutoTradeService.AutoTradeResult;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.ContractAnalysisService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private volatile String runningScanId;

    public ContractMarketScanTask(ContractAnalysisService contractAnalysisService, AutoTradeService autoTradeService) {
        this(contractAnalysisService, autoTradeService, new AgentProperties());
    }

    @Autowired
    public ContractMarketScanTask(ContractAnalysisService contractAnalysisService, AutoTradeService autoTradeService,
                                  AgentProperties agentProperties) {
        this.contractAnalysisService = contractAnalysisService;
        this.autoTradeService = autoTradeService;
        this.agentProperties = agentProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        runOnce();
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
        if (agentProperties.concurrency().scanLockEnabled() && !scanRunning.compareAndSet(false, true)) {
            ScanTaskLog result = new ScanTaskLog(
                    "contract-market-scan",
                    "SKIPPED",
                    "scanId=" + scanId
                            + ", previousScanId=" + runningScanId
                            + ", reason=SCAN_ALREADY_RUNNING",
                    Instant.now()
            );
            log.warn("{}: {}", result.taskName(), result.message());
            return result;
        }
        runningScanId = scanId;
        Instant startedAt = Instant.now();
        try {
            List<ContractCandidate> candidates = contractAnalysisService.refreshCandidates();
            Duration elapsed = Duration.between(startedAt, Instant.now());
            if (elapsed.compareTo(Duration.ofSeconds(agentProperties.timeout().scanRoundTimeoutSeconds())) > 0) {
                ScanTaskLog result = new ScanTaskLog(
                        "contract-market-scan",
                        "FAILED",
                        "scanId=" + scanId
                                + ", reason=SCAN_TIMEOUT"
                                + ", elapsedSeconds=" + elapsed.toSeconds(),
                        Instant.now()
                );
                log.warn("{}: {}", result.taskName(), result.message());
                return result;
            }
            AutoTradeResult autoTrade = autoTradeService.evaluateAndExecute(candidates);
            ScanTaskLog result = new ScanTaskLog(
                    "contract-market-scan",
                    "SUCCESS",
                    "scanId=" + scanId
                            + ", Contract signal scan completed, recommendations=" + candidates.size()
                            + ", autoTrade=" + autoTrade.status()
                            + ", autoMessage=" + compact(autoTrade.message()),
                    Instant.now()
            );
            log.info("{}: {}", result.taskName(), result.message());
            return result;
        } catch (RuntimeException ex) {
            ScanTaskLog result = new ScanTaskLog(
                    "contract-market-scan",
                    "FAILED",
                    "scanId=" + scanId + ", Contract signal scan failed: " + compact(ex.getMessage()),
                    Instant.now()
            );
            log.warn("{}: {}", result.taskName(), result.message(), ex);
            return result;
        } finally {
            runningScanId = null;
            if (agentProperties.concurrency().scanLockEnabled()) {
                scanRunning.set(false);
            }
        }
    }

    private static String compact(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }
}

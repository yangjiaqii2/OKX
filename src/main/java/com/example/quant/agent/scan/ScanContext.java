package com.example.quant.agent.scan;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.ContractScanResult;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScanContext {
    public static final Duration CLEANUP_GRACE = Duration.ofMillis(500);

    private final String scanId;
    private final Instant startedAt;
    private final Instant deadline;
    private volatile String currentStage = "init";
    private final AtomicInteger providerTimeoutCount = new AtomicInteger();
    private final AtomicInteger skippedByDeadline = new AtomicInteger();
    private final AtomicInteger deepAnalysisStarted = new AtomicInteger();
    private final AtomicInteger deepAnalysisCompleted = new AtomicInteger();
    private final AtomicBoolean acceptingResults = new AtomicBoolean(true);
    private volatile ScanRunStatus status = ScanRunStatus.COMPLETED;
    private volatile ContractScanResult result;

    private ScanContext(String scanId, Instant startedAt, Instant deadline) {
        this.scanId = scanId;
        this.startedAt = startedAt;
        this.deadline = deadline;
    }

    public static ScanContext create(String scanId, AgentProperties agentProperties) {
        Instant startedAt = Instant.now();
        int timeoutSeconds = agentProperties == null ? 0 : agentProperties.timeout().scanRoundTimeoutSeconds();
        Instant deadline = timeoutSeconds > 0 ? startedAt.plusSeconds(timeoutSeconds) : null;
        String id = scanId == null || scanId.isBlank() ? UUID.randomUUID().toString() : scanId;
        return new ScanContext(id, startedAt, deadline);
    }

    public static ScanContext unbounded(String scanId) {
        String id = scanId == null || scanId.isBlank() ? UUID.randomUUID().toString() : scanId;
        return new ScanContext(id, Instant.now(), null);
    }

    public static ScanContext forTest(String scanId, Instant startedAt, Instant deadline) {
        return new ScanContext(scanId, startedAt, deadline);
    }

    public String scanId() {
        return scanId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant deadline() {
        return deadline;
    }

    public String currentStage() {
        return currentStage;
    }

    public void setStage(String stage) {
        if (stage != null && !stage.isBlank()) {
            this.currentStage = stage;
        }
    }

    public boolean isExpired() {
        return deadline != null && Instant.now().isAfter(deadline);
    }

    public Instant hardStopAt() {
        return deadline == null ? null : deadline.plus(CLEANUP_GRACE);
    }

    public boolean isPastHardStop() {
        Instant stop = hardStopAt();
        return stop != null && Instant.now().isAfter(stop);
    }

    public Duration remainingTime() {
        if (deadline == null) {
            return Duration.ofSeconds(Integer.MAX_VALUE);
        }
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public Duration requestTimeout() {
        Duration remaining = remainingTime();
        if (remaining.isZero()) {
            return Duration.ofMillis(1);
        }
        Duration capped = remaining.compareTo(Duration.ofSeconds(6)) > 0
                ? Duration.ofSeconds(6)
                : remaining;
        return capped.isZero() ? Duration.ofMillis(1) : capped;
    }

    public boolean acceptingResults() {
        return acceptingResults.get() && !isPastHardStop();
    }

    public void stopAcceptingResults() {
        acceptingResults.set(false);
    }

    public void markPartialTimeout() {
        status = ScanRunStatus.PARTIAL_TIMEOUT;
        stopAcceptingResults();
    }

    public void markFailed() {
        status = ScanRunStatus.FAILED;
        stopAcceptingResults();
    }

    public ScanRunStatus status() {
        return status;
    }

    public ContractScanResult result() {
        return result;
    }

    public void recordResult(ContractScanResult result) {
        this.result = result;
    }

    public int providerTimeoutCount() {
        return providerTimeoutCount.get();
    }

    public void recordProviderTimeout() {
        providerTimeoutCount.incrementAndGet();
    }

    public int skippedByDeadline() {
        return skippedByDeadline.get();
    }

    public void addSkippedByDeadline(int count) {
        if (count > 0) {
            skippedByDeadline.addAndGet(count);
        }
    }

    public int deepAnalysisStarted() {
        return deepAnalysisStarted.get();
    }

    public int beginDeepAnalysis() {
        return deepAnalysisStarted.incrementAndGet();
    }

    public int deepAnalysisCompleted() {
        return deepAnalysisCompleted.get();
    }

    public void completeDeepAnalysis() {
        deepAnalysisCompleted.incrementAndGet();
    }

    public long elapsedMs() {
        return java.time.Duration.between(startedAt, Instant.now()).toMillis();
    }
}

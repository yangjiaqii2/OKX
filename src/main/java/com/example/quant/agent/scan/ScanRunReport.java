package com.example.quant.agent.scan;

import java.time.Instant;

public record ScanRunReport(
        String scanId,
        ScanRunStatus status,
        Instant startedAt,
        Instant finishedAt,
        long elapsedMs,
        String currentStage,
        int universeCount,
        int hardFilterPassed,
        int deepTargetLimit,
        int deepAnalysisStarted,
        int deepAnalysisCompleted,
        int providerTimeoutCount,
        int skippedByDeadline,
        ScanDiagnostics diagnostics
) {
    public static ScanRunReport failed(String scanId, Instant startedAt, String stage, ScanDiagnostics diagnostics) {
        Instant finishedAt = Instant.now();
        return new ScanRunReport(
                scanId,
                ScanRunStatus.FAILED,
                startedAt,
                finishedAt,
                java.time.Duration.between(startedAt, finishedAt).toMillis(),
                stage,
                diagnostics == null ? 0 : diagnostics.universeTickers(),
                diagnostics == null ? 0 : diagnostics.passedCount(),
                0,
                0,
                0,
                0,
                0,
                diagnostics
        );
    }
}

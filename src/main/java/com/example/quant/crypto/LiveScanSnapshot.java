package com.example.quant.crypto;

import com.example.quant.agent.scan.ScanRunStatus;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record LiveScanSnapshot(
        List<ContractCandidate> candidates,
        Map<String, V2SelectionScoreResult> v2Scores,
        String scanId,
        ScanRunStatus status,
        Instant startedAt,
        Instant finishedAt
) {
    public static LiveScanSnapshot empty() {
        return new LiveScanSnapshot(List.of(), Map.of(), null, ScanRunStatus.UNKNOWN, null, null);
    }

    public static LiveScanSnapshot completed(
            String scanId,
            List<ContractCandidate> candidates,
            Map<String, V2SelectionScoreResult> v2Scores,
            Instant startedAt,
            Instant finishedAt
    ) {
        return new LiveScanSnapshot(
                List.copyOf(candidates),
                Map.copyOf(v2Scores),
                scanId,
                ScanRunStatus.COMPLETED,
                startedAt,
                finishedAt
        );
    }
}

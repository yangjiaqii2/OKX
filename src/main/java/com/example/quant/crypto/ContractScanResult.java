package com.example.quant.crypto;

import com.example.quant.agent.scan.ScanRunReport;
import com.example.quant.agent.scan.ScanRunStatus;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import java.util.List;
import java.util.Map;

public record ContractScanResult(
        List<ContractCandidate> candidates,
        ScanRunReport report,
        Map<String, V2SelectionScoreResult> v2Scores,
        List<ContractCandidate> reserveCandidates
) {
    public ContractScanResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        v2Scores = v2Scores == null ? Map.of() : Map.copyOf(v2Scores);
        reserveCandidates = reserveCandidates == null ? List.of() : List.copyOf(reserveCandidates);
    }

    public ContractScanResult(List<ContractCandidate> candidates, ScanRunReport report,
                              Map<String, V2SelectionScoreResult> v2Scores) {
        this(candidates, report, v2Scores, List.of());
    }

    public ContractScanResult(List<ContractCandidate> candidates, ScanRunReport report) {
        this(candidates, report, Map.of());
    }

    public ScanRunStatus status() {
        return report == null ? ScanRunStatus.UNKNOWN : report.status();
    }

    public boolean autoTradeAllowed() {
        return status() == ScanRunStatus.COMPLETED;
    }
}

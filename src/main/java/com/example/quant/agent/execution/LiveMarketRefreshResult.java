package com.example.quant.agent.execution;

import com.example.quant.crypto.dto.ContractCandidate;
import java.time.Instant;
import java.util.List;

public record LiveMarketRefreshResult(
        boolean passed,
        ContractCandidate candidate,
        Instant analysisTimestamp,
        Instant jevDecisionTimestamp,
        Instant refreshTimestamp,
        Instant latestCandleTimestamp,
        List<String> reasons
) {
    public LiveMarketRefreshResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static LiveMarketRefreshResult rejected(Instant timestamp, String reason) {
        return new LiveMarketRefreshResult(false, null, null, null, timestamp, null, List.of(reason));
    }
}

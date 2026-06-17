package com.example.quant.agent.entry;

import java.util.List;

public record SmartEntryModeDecision(
        SmartEntryMode mode,
        boolean allowSubmit,
        List<String> reasons
) {
    public SmartEntryModeDecision {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}

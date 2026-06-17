package com.example.quant.okxtrade;

import java.util.List;
import java.util.Map;

public record ProtectionPlanResult(
        boolean valid,
        String failureReason,
        OkxOrderFill fill,
        List<Map<String, String>> payloads
) {
    public ProtectionPlanResult {
        payloads = payloads == null ? List.of() : List.copyOf(payloads);
    }
}

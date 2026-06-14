package com.example.quant.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RiskCheckResult(
        boolean passed,
        RiskLevel riskLevel,
        String rejectCode,
        String rejectReason,
        List<String> warningList,
        BigDecimal adjustedSize,
        Integer adjustedLeverage,
        BigDecimal maxLossAmount,
        Instant createdAt
) {
    public static RiskCheckResult rejected(String code, String reason) {
        return new RiskCheckResult(false, RiskLevel.BLOCKED, code, reason, List.of(), null, null, null, Instant.now());
    }
}

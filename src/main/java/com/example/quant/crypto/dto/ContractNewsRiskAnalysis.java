package com.example.quant.crypto.dto;

import java.util.List;

public record ContractNewsRiskAnalysis(
        int newsRiskScore,
        String newsRiskLevel,
        List<String> eventTypes,
        List<String> positiveEvents,
        List<String> negativeEvents,
        ContractNewsRiskDecision decision
) {
    public ContractNewsRiskAnalysis {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        positiveEvents = positiveEvents == null ? List.of() : List.copyOf(positiveEvents);
        negativeEvents = negativeEvents == null ? List.of() : List.copyOf(negativeEvents);
        decision = decision == null ? ContractNewsRiskDecision.waitOnly("新闻风险数据不足") : decision;
    }

    public static ContractNewsRiskAnalysis low() {
        return new ContractNewsRiskAnalysis(
                90,
                "LOW",
                List.of(),
                List.of(),
                List.of(),
                ContractNewsRiskDecision.allow("无重大负面新闻风险")
        );
    }

    public static ContractNewsRiskAnalysis unknown(String reason) {
        return new ContractNewsRiskAnalysis(
                60,
                "UNKNOWN",
                List.of("DATA_UNAVAILABLE"),
                List.of(),
                List.of(reason),
                ContractNewsRiskDecision.waitOnly(reason)
        );
    }

    public static ContractNewsRiskAnalysis critical(String reason) {
        return new ContractNewsRiskAnalysis(
                10,
                "CRITICAL",
                List.of("CRITICAL_RISK"),
                List.of(),
                List.of(reason),
                ContractNewsRiskDecision.noTrade(reason)
        );
    }
}

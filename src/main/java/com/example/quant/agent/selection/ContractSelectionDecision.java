package com.example.quant.agent.selection;

import com.example.quant.market.DirectionBias;
import java.math.BigDecimal;
import java.util.List;

public record ContractSelectionDecision(
        DecisionSource source,
        SelectionAction decision,
        String selectedInstId,
        DirectionBias direction,
        String entryMode,
        BigDecimal confidence,
        List<RankedCandidate> rankedCandidates,
        List<String> reasonList,
        List<String> riskList,
        String fallbackReason
) {
    public ContractSelectionDecision {
        rankedCandidates = rankedCandidates == null ? List.of() : List.copyOf(rankedCandidates);
        reasonList = reasonList == null ? List.of() : List.copyOf(reasonList);
        riskList = riskList == null ? List.of() : List.copyOf(riskList);
    }

    public enum DecisionSource { AI_PRIMARY, AI_WAIT, RULE_FALLBACK, NO_ELIGIBLE_CANDIDATE }

    public enum SelectionAction { TRADE, WAIT }

    public record RankedCandidate(
            String instId,
            int rank,
            DirectionBias direction,
            String entryMode,
            BigDecimal confidence,
            List<String> reasonList,
            List<String> riskList
    ) {
        public RankedCandidate(String instId, int rank, DirectionBias direction, String entryMode,
                               BigDecimal confidence) {
            this(instId, rank, direction, entryMode, confidence, List.of(), List.of());
        }
        public RankedCandidate {
            reasonList = reasonList == null ? List.of() : List.copyOf(reasonList);
            riskList = riskList == null ? List.of() : List.copyOf(riskList);
        }
    }

    static ContractSelectionDecision noEligible() {
        return new ContractSelectionDecision(
                DecisionSource.NO_ELIGIBLE_CANDIDATE, SelectionAction.WAIT, "", null, "WAIT",
                BigDecimal.ZERO, List.of(), List.of(), List.of(), "NO_ELIGIBLE_CANDIDATE");
    }

    static ContractSelectionDecision fallback(String reason) {
        return new ContractSelectionDecision(
                DecisionSource.RULE_FALLBACK, SelectionAction.TRADE, "", null, "RULE",
                BigDecimal.ZERO, List.of(), List.of(), List.of(), reason);
    }
}

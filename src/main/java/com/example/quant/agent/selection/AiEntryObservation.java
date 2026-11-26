package com.example.quant.agent.selection;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

record AiEntryObservation(
        Reference reference, String scope, String owner, String scanId, Instant observedAt,
        String instId, String requestedModel, boolean typeSafe, String prompt, Map<String, Object> input,
        JsonNode response, ContractSelectionDecision finalDecision, List<Rejection> thresholdVetoes,
        List<String> hardRules, String detail
) {
    enum Rejection {
        NONE, MODEL_WAIT, ENTRY_MODE_WAIT, QUALITY_BELOW_THRESHOLD, RISK_BELOW_THRESHOLD,
        QUALITY_AND_RISK_BELOW_THRESHOLD, HARD_DENY, DIRECTION_CONFLICT, AI_CALL_FAILED, AI_RESPONSE_INVALID, OTHER
    }

    record Reference(String id, Rejection reason) {
        public String reasonCode() {
            return reason.name();
        }
    }

    static final double QUALITY_MINIMUM = 1.75;
    static final double RISK_MINIMUM = 0.55;
}

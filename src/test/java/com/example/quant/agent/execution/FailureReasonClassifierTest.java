package com.example.quant.agent.execution;

import com.example.quant.config.AgentProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FailureReasonClassifierTest {
    @Test
    void candidateBudgetMessagesDoNotStopRound() {
        FailureReasonClassifier classifier = new FailureReasonClassifier(new AgentProperties());
        assertThat(classifier.classify("BUDGET", "BUDGET_ORDER_MARGIN_BELOW_MIN: margin too small"))
                .isEqualTo(FailureClassification.NEXT_CANDIDATE_ALLOWED);
    }

    @Test
    void globalBlockerWinsOverCandidateBudgetCode() {
        FailureReasonClassifier classifier = new FailureReasonClassifier(new AgentProperties());
        assertThat(classifier.classify("PLAN_OR_CONFIRM", "PRE_ORDER_SETUP_FAILED: BUDGET_ORDER_MARGIN_BELOW_MIN"))
                .isEqualTo(FailureClassification.STOP_ROUND);
    }

    @Test
    void failedDoesNotMatchAiWhenPlanFallbackIsDisabled() {
        AgentProperties properties = new AgentProperties();
        properties.fallback().setContinueOnPlanRiskFail(false);
        assertThat(new FailureReasonClassifier(properties).classify("PLAN_OR_CONFIRM", "FAILED"))
                .isEqualTo(FailureClassification.STOP_ROUND);
    }
}

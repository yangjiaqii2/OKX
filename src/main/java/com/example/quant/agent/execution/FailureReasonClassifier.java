package com.example.quant.agent.execution;

import com.example.quant.config.AgentProperties;
import org.springframework.stereotype.Service;

@Service
public class FailureReasonClassifier {
    private final AgentProperties agentProperties;

    public FailureReasonClassifier(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    public FailureClassification classify(String stage, String reason) {
        String normalized = reason == null ? "" : reason.toLowerCase();
        if (normalized.contains("budget_order_margin_below_min")
                || normalized.contains("candidate_budget_allocation_too_low")
                || normalized.contains("symbol_already_in_flight")
                || normalized.contains("pre_confirm_refresh_failed")) {
            return FailureClassification.NEXT_CANDIDATE_ALLOWED;
        }
        if (normalized.contains("total_budget_exhausted")
                || normalized.contains("budget_system_error")
                || normalized.contains("budget_reserve_failed_due_to_concurrency")
                || normalized.contains("okx_submit_status_unknown")) {
            return FailureClassification.STOP_ROUND;
        }
        if (normalized.contains("实时订单簿流动性不足")
                || normalized.contains("spread")
                || normalized.contains("slippage")
                || normalized.contains("risk_reward")
                || normalized.contains("盈亏比")
                || normalized.contains("stop_loss")
                || normalized.contains("止损")
                || normalized.contains("smart entry")
                || normalized.contains("智能入场")
                || normalized.contains("plan")
                || normalized.contains("ai")) {
            return FailureClassification.NEXT_CANDIDATE_ALLOWED;
        }
        if (normalized.contains("margin")
                || normalized.contains("余额")
                || normalized.contains("保证金")
                || normalized.contains("account")
                || normalized.contains("api")
                || normalized.contains("权限")
                || normalized.contains("leverage")
                || normalized.contains("杠杆")
                || normalized.contains("emergency")
                || normalized.contains("熔断")
                || normalized.contains("protection")) {
            return FailureClassification.STOP_ROUND;
        }
        if (agentProperties.fallback().continueOnPlanRiskFail()) {
            return FailureClassification.NEXT_CANDIDATE_ALLOWED;
        }
        return FailureClassification.STOP_ROUND;
    }
}

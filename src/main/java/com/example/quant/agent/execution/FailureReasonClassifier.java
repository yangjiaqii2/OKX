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
        String normalized = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
        boolean candidateBudgetTooSmall = normalized.contains("budget_order_margin_below_min")
                || normalized.contains("candidate_budget_allocation_too_low")
                || normalized.contains("net_risk_or_margin_below_minimum_size");
        // Global blockers take precedence over candidate-local plan/AI wording.
        if (normalized.contains("pre_order_setup_failed")
                || normalized.contains("daily_risk_")
                || normalized.contains("max_daily_")
                || normalized.contains("daily_ledger_sync_required")
                || normalized.contains("max_consecutive_losses")
                || normalized.contains("loss_cooldown_active")
                || normalized.contains("trading_mode_disabled")
                || normalized.contains("trading engine")
                || normalized.contains("启动对账")
                || normalized.contains("新增风险操作已阻断")
                || normalized.contains("写请求未发送")
                || normalized.contains("total_budget_exhausted")
                || normalized.contains("budget_system_error")
                || normalized.contains("budget_reserve_failed_due_to_concurrency")
                || normalized.contains("okx_submit_status_unknown")
                || (normalized.contains("margin") && !candidateBudgetTooSmall)
                || normalized.contains("余额") || normalized.contains("保证金")
                || normalized.contains("account") || normalized.contains("api") || normalized.contains("权限")
                || normalized.contains("leverage") || normalized.contains("杠杆") || normalized.contains("emergency")
                || normalized.contains("熔断") || normalized.contains("protection")) {
            return FailureClassification.STOP_ROUND;
        }
        if (candidateBudgetTooSmall
                || normalized.contains("symbol_already_in_flight")
                || normalized.contains("market_entry_outside_exit_range")
                || normalized.contains("pre_confirm_refresh_failed")) {
            return FailureClassification.NEXT_CANDIDATE_ALLOWED;
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
                || normalized.contains("plan_risk") || normalized.contains("trade_plan")
                || normalized.contains("ai_plan_invalid")
                || normalized.contains("ai_direction_conflict")) {
            return FailureClassification.NEXT_CANDIDATE_ALLOWED;
        }
        if (agentProperties.fallback().continueOnPlanRiskFail()) {
            return FailureClassification.NEXT_CANDIDATE_ALLOWED;
        }
        return FailureClassification.STOP_ROUND;
    }
}

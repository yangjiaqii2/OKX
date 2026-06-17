package com.example.quant.agent.gate;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class ContractTradeGate {
    private static final BigDecimal MIN_PLAN_RISK_REWARD = BigDecimal.valueOf(1.5);

    private ContractTradeGate() {
    }

    public static List<String> planDenyReasons(ContractCandidate candidate, AgentProperties agentProperties) {
        List<String> reasons = baseDenyReasons(candidate, agentProperties, MIN_PLAN_RISK_REWARD);
        if (candidate.score() < agentProperties.score().minTotalScore()) {
            reasons.add(0, "total_score_below_" + agentProperties.score().minTotalScore());
        }
        return reasons;
    }

    public static List<String> autoDenyReasons(ContractCandidate candidate, AgentProperties agentProperties) {
        List<String> reasons = new ArrayList<>();
        AgentProperties.AutoTrade autoTrade = agentProperties.autoTrade();
        if (autoTrade.requireBacktestPass()) {
            reasons.add("backtest_gate_required_but_not_available");
        }
        if (!"AUTO_TRADE_ALLOWED".equals(candidate.action())) {
            reasons.add("candidate_action_not_auto_trade_allowed_" + candidate.action());
        }
        if (!isTradableSignal(candidate.signalType())) {
            reasons.add("candidate_signal_not_tradable_" + candidate.signalType());
        }
        if (candidate.spreadBps().compareTo(agentProperties.market().maxSpreadBps().multiply(BigDecimal.valueOf(3))) > 0) {
            reasons.add("spread_bps_extreme_" + candidate.spreadBps());
        }
        return reasons;
    }

    public static List<String> scoreDenyReasons(ContractCandidate candidate, AgentProperties agentProperties) {
        return planDenyReasons(candidate, agentProperties);
    }

    private static List<String> baseDenyReasons(
            ContractCandidate candidate,
            AgentProperties agentProperties,
            BigDecimal minRiskReward
    ) {
        List<String> reasons = new ArrayList<>();
        AgentProperties.Score score = agentProperties.score();
        ContractFactorScore factor = candidate.factorScore();
        if (factor.trendScore() < score.minTrendScore()) {
            reasons.add("trend_score_below_" + score.minTrendScore());
        }
        if (factor.volumeScore() < score.minVolumeScore()) {
            reasons.add("volume_score_below_" + score.minVolumeScore());
        }
        if (factor.liquidityScore() < score.minLiquidityScore()) {
            reasons.add("liquidity_score_below_" + score.minLiquidityScore());
        }
        if (factor.newsRiskScore() < score.minNewsRiskScore()) {
            reasons.add("news_risk_score_below_" + score.minNewsRiskScore());
        }
        if (candidate.riskRewardRatio() == null || candidate.riskRewardRatio().compareTo(minRiskReward) < 0) {
            reasons.add("risk_reward_below_" + minRiskReward);
        }
        if (candidate.stopLossPct() != null && candidate.stopLossPct().compareTo(BigDecimal.valueOf(5)) > 0) {
            reasons.add("stop_loss_pct_above_5");
        }
        if (!isTradableSignal(candidate.signalType())) {
            reasons.add("signal_type_not_tradable_" + candidate.signalType());
        }
        if ("CRITICAL".equals(candidate.newsAnalysis().newsRiskLevel())) {
            reasons.add("news_risk_critical");
        } else if ("HIGH".equals(candidate.newsAnalysis().newsRiskLevel())) {
            reasons.add("news_risk_high");
        } else if ("UNKNOWN".equals(candidate.newsAnalysis().newsRiskLevel())) {
            reasons.add("news_risk_unknown_wait_confirm_only");
        }
        if (candidate.spreadBps().compareTo(agentProperties.market().maxSpreadBps()) > 0) {
            reasons.add("spread_bps_above_" + agentProperties.market().maxSpreadBps());
        }
        if (candidate.marketRiskLevel().ordinal() >= RiskLevel.HIGH.ordinal()) {
            reasons.add("market_environment_high_risk");
        }
        return reasons;
    }

    private static boolean isTradableSignal(ContractSignalType signalType) {
        return signalType == ContractSignalType.STRONG_LONG
                || signalType == ContractSignalType.PULLBACK_LONG
                || signalType == ContractSignalType.TREND_SHORT
                || signalType == ContractSignalType.REVERSAL_SHORT;
    }
}

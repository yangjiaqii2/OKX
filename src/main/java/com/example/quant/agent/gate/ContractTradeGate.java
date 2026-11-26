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

    public static List<String> newsDenyReasons(ContractCandidate candidate, AgentProperties properties) {
        List<String> reasons = new ArrayList<>();
        addNewsDenyReasons(reasons, candidate, properties.score().minNewsRiskRawScore());
        return reasons;
    }

    public static List<String> aiHardDenyReasons(ContractCandidate candidate, AgentProperties properties) {
        List<String> reasons = new ArrayList<>();
        if (candidate == null || candidate.instId() == null || !candidate.instId().endsWith("-USDT-SWAP")) {
            reasons.add("unsupported_instrument");
            return reasons;
        }
        if (candidate.signalType() == ContractSignalType.WAIT_RECLAIM) {
            reasons.add("short_term_reclaim_unconfirmed");
        }
        if (candidate.lastPrice() == null || candidate.lastPrice().signum() <= 0) reasons.add("invalid_last_price");
        if (candidate.volume24h() == null || candidate.volume24h().signum() <= 0) reasons.add("invalid_turnover");
        if (candidate.fundingRate() == null) reasons.add("funding_unavailable");
        if (candidate.marketRiskLevel() == null || candidate.marketRiskLevel().ordinal() >= RiskLevel.HIGH.ordinal()) {
            reasons.add("market_risk_high");
        }
        addNewsDenyReasons(reasons, candidate, properties.score().minNewsRiskRawScore());
        BigDecimal maxSpread = properties.market().maxSpreadBps().multiply(BigDecimal.valueOf(3));
        if (candidate.spreadBps() == null || candidate.spreadBps().compareTo(maxSpread) > 0) {
            reasons.add("spread_bps_extreme");
        }
        return reasons;
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
        addNewsDenyReasons(reasons, candidate, score.minNewsRiskRawScore());
        if (candidate.riskRewardRatio() == null || candidate.riskRewardRatio().compareTo(minRiskReward) < 0) {
            reasons.add("risk_reward_below_" + minRiskReward);
        }
        if (candidate.stopLossPct() != null && candidate.stopLossPct().compareTo(BigDecimal.valueOf(5)) > 0) {
            reasons.add("stop_loss_pct_above_5");
        }
        if (!isTradableSignal(candidate.signalType())) {
            reasons.add("signal_type_not_tradable_" + candidate.signalType());
        }
        if (candidate.spreadBps().compareTo(agentProperties.market().maxSpreadBps()) > 0) {
            reasons.add("spread_bps_above_" + agentProperties.market().maxSpreadBps());
        }
        if (candidate.marketRiskLevel().ordinal() >= RiskLevel.HIGH.ordinal()) {
            reasons.add("market_environment_high_risk");
        }
        return reasons;
    }

    private static void addNewsDenyReasons(List<String> reasons, ContractCandidate candidate,
                                           int minRawSafetyScore) {
        if (candidate.newsAnalysis() == null || candidate.newsAnalysis().decision() == null) {
            reasons.add("news_data_unavailable");
            return;
        }
        String level = candidate.newsAnalysis().newsRiskLevel();
        if ("CRITICAL".equals(level)) {
            reasons.add("news_risk_critical");
            return;
        }
        if ("HIGH".equals(level)) {
            reasons.add("news_risk_high");
            return;
        }
        if (!candidate.newsAnalysis().decision().allowTrade()) {
            reasons.add("UNKNOWN".equals(level) ? "news_data_unavailable" : "news_decision_not_tradeable");
            return;
        }
        if (candidate.newsAnalysis().newsRiskScore() < minRawSafetyScore) {
            reasons.add("news_risk_raw_score_below_" + minRawSafetyScore);
        }
    }

    public static boolean isTradableSignal(ContractSignalType signalType) {
        return signalType == ContractSignalType.STRONG_LONG
                || signalType == ContractSignalType.PULLBACK_LONG
                || signalType == ContractSignalType.TREND_SHORT;
    }
}

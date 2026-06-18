package com.example.quant.agent.entry;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractSignalType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SmartEntryModeResolver {
    private static final Set<ContractSignalType> DEFAULT_MARKET_SIGNAL_TYPES =
            Set.of(ContractSignalType.STRONG_LONG, ContractSignalType.TREND_SHORT);
    private static final Set<ContractSignalType> NO_ENTRY_SIGNAL_TYPES = Set.of(
            ContractSignalType.WAIT_OVERHEATED,
            ContractSignalType.WAIT_OVERSOLD,
            ContractSignalType.NEUTRAL,
            ContractSignalType.NO_TRADE
    );

    private final AgentProperties agentProperties;

    public SmartEntryModeResolver(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    public SmartEntryModeDecision resolve(ContractCandidate candidate) {
        AgentProperties.Entry entry = agentProperties.entry();
        if (!entry.smartEntryEnabled()) {
            return new SmartEntryModeDecision(SmartEntryMode.MARKET, true, List.of("智能入场关闭，保持原有MARKET行为"));
        }

        List<String> reasons = new ArrayList<>();
        if (NO_ENTRY_SIGNAL_TYPES.contains(candidate.signalType())) {
            reasons.add("信号类型不可入场：" + candidate.signalType());
            return new SmartEntryModeDecision(SmartEntryMode.NO_ENTRY, false, reasons);
        }
        String newsRiskLevel = candidate.newsAnalysis().newsRiskLevel();
        if ("HIGH".equals(newsRiskLevel) || "CRITICAL".equals(newsRiskLevel)) {
            reasons.add("新闻风险不允许市价入场：" + newsRiskLevel);
            return new SmartEntryModeDecision(SmartEntryMode.NO_ENTRY, false, reasons);
        }
        if (candidate.stopLossPct() != null && candidate.stopLossPct().compareTo(BigDecimal.valueOf(5)) > 0) {
            reasons.add("止损距离过大：" + candidate.stopLossPct() + "%");
            return new SmartEntryModeDecision(SmartEntryMode.NO_ENTRY, false, reasons);
        }
        if (candidate.riskRewardRatio() == null
                || candidate.riskRewardRatio().compareTo(agentProperties.ranking().minRiskReward()) < 0) {
            reasons.add("盈亏比不足：" + candidate.riskRewardRatio());
            return new SmartEntryModeDecision(SmartEntryMode.NO_ENTRY, false, reasons);
        }
        if (spreadPct(candidate).compareTo(entry.maxMarketSpreadPct()) > 0) {
            reasons.add("spread超过市价入场阈值：" + spreadPct(candidate) + "%");
            return new SmartEntryModeDecision(SmartEntryMode.NO_ENTRY, false, reasons);
        }
        if (slippagePct(candidate).compareTo(entry.maxMarketSlippagePct()) > 0) {
            reasons.add("预估滑点超过市价入场阈值：" + slippagePct(candidate) + "%");
            return new SmartEntryModeDecision(SmartEntryMode.NO_ENTRY, false, reasons);
        }
        if (entry.waitPullbackSignalTypes().contains(candidate.signalType().name())
                || "WAIT_PULLBACK".equals(candidate.klineAnalysis().entryTiming5m())
                || distanceFromEma(candidate).compareTo(entry.maxDistanceFromEma20Pct()) > 0) {
            reasons.add("等待回踩：信号或价格距离EMA20不适合追单");
            return new SmartEntryModeDecision(SmartEntryMode.WAIT_PULLBACK, false, reasons);
        }
        if ("WAIT_RETEST".equals(candidate.klineAnalysis().entryTiming5m())
                || candidate.klineAnalysis().structure().contains("BREAKOUT_PREV_HIGH")
                || candidate.klineAnalysis().structure().contains("BREAKDOWN_PREV_LOW")) {
            reasons.add("等待突破后回踩确认");
            return new SmartEntryModeDecision(SmartEntryMode.WAIT_RETEST, false, reasons);
        }
        if (!marketSignalAllowed(candidate, entry)) {
            reasons.add("信号类型不在市价入场白名单：" + candidate.signalType());
            return new SmartEntryModeDecision(SmartEntryMode.WAIT_BREAKOUT, false, reasons);
        }
        if (candidate.score() < entry.marketMinScore()
                && safe(candidate.finalRankScore()).compareTo(BigDecimal.valueOf(entry.marketMinScore())) < 0) {
            reasons.add("分数未达到市价入场门槛：" + candidate.score() + "/" + candidate.finalRankScore());
            return new SmartEntryModeDecision(SmartEntryMode.WAIT_BREAKOUT, false, reasons);
        }
        if (candidate.riskRewardRatio().compareTo(entry.marketMinRiskReward()) < 0) {
            reasons.add("市价入场盈亏比不足：" + candidate.riskRewardRatio());
            return new SmartEntryModeDecision(SmartEntryMode.WAIT_BREAKOUT, false, reasons);
        }
        if (!candidate.klineAnalysis().twentyMinuteStructureClear()) {
            reasons.add("20m结构不够清晰，等待确认");
            return new SmartEntryModeDecision(SmartEntryMode.WAIT_RETEST, false, reasons);
        }
        if (!"READY".equals(candidate.klineAnalysis().entryTiming5m())) {
            reasons.add("5m入场节奏未就绪：" + candidate.klineAnalysis().entryTiming5m());
            return new SmartEntryModeDecision(SmartEntryMode.WAIT_RETEST, false, reasons);
        }
        if (candidate.fundingRate() != null
                && candidate.fundingRate().abs().compareTo(agentProperties.market().maxFundingAbs()) > 0) {
            reasons.add("资金费率拥挤，不做市价追单：" + candidate.fundingRate());
            return new SmartEntryModeDecision(SmartEntryMode.WAIT_BREAKOUT, false, reasons);
        }
        reasons.add("MARKET入场：强信号、低价差低滑点、20m结构清晰且5m就绪");
        return new SmartEntryModeDecision(SmartEntryMode.MARKET, true, reasons);
    }

    private static boolean marketSignalAllowed(ContractCandidate candidate, AgentProperties.Entry entry) {
        if (entry.allowMarketSignalTypes().isEmpty()) {
            return DEFAULT_MARKET_SIGNAL_TYPES.contains(candidate.signalType());
        }
        return entry.allowMarketSignalTypes().contains(candidate.signalType().name());
    }

    private static BigDecimal spreadPct(ContractCandidate candidate) {
        return bpsToPct(candidate.spreadBps());
    }

    private static BigDecimal slippagePct(ContractCandidate candidate) {
        return bpsToPct(candidate.estimatedSlippageBps());
    }

    private static BigDecimal bpsToPct(BigDecimal bps) {
        if (bps == null) {
            return BigDecimal.ZERO;
        }
        return bps.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal distanceFromEma(ContractCandidate candidate) {
        return candidate.klineAnalysis().distanceFromEma20Pct() == null
                ? BigDecimal.ZERO
                : candidate.klineAnalysis().distanceFromEma20Pct().abs();
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

package com.example.quant.agent.execution;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.market.DirectionBias;
import com.example.quant.tradeplan.TradePlan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PreConfirmRefreshService {
    private final AgentProperties agentProperties;

    public PreConfirmRefreshService(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    public PreConfirmRefreshResult check(ContractCandidate candidate, TradePlan plan) {
        AgentProperties.PreConfirmRefresh config = agentProperties.preConfirmRefresh();
        if (!config.enabled()) {
            return PreConfirmRefreshResult.passed("pre_confirm_refresh_disabled");
        }
        List<String> reasons = new ArrayList<>();
        BigDecimal deviationPct = priceDeviationPct(candidate.lastPrice(), plan.entryPrice());
        if (deviationPct.compareTo(config.maxPriceDeviationPct()) > 0) {
            reasons.add("PRICE_DEVIATION_TOO_WIDE_" + deviationPct + "%");
        }
        BigDecimal spreadPct = bpsToPct(candidate.spreadBps());
        if (spreadPct.compareTo(config.maxSpreadPct()) > 0) {
            reasons.add("SPREAD_TOO_WIDE_" + spreadPct + "%");
        }
        if (config.rejectIf5mReverse() && directionReversed(candidate)) {
            reasons.add("FIVE_MINUTE_REVERSE");
        }
        if (config.rejectIfBtcReverse() && btcEthReversed(candidate)) {
            reasons.add("BTC_ETH_REVERSE");
        }
        if (config.rejectIfNewsRiskUpgraded()
                && !"LOW".equals(candidate.newsAnalysis().newsRiskLevel())) {
            reasons.add("NEWS_RISK_UPGRADED_" + candidate.newsAnalysis().newsRiskLevel());
        }
        if (candidate.fundingRate() != null
                && candidate.fundingRate().abs().compareTo(agentProperties.market().maxFundingAbs()) > 0) {
            reasons.add("FUNDING_CROWDED_" + candidate.fundingRate());
        }
        if (reasons.isEmpty()) {
            return PreConfirmRefreshResult.passed("pre_confirm_refresh_passed");
        }
        return PreConfirmRefreshResult.rejected(reasons);
    }

    private static boolean directionReversed(ContractCandidate candidate) {
        String timing = candidate.klineAnalysis().entryTiming5m();
        if (candidate.trendDirection() == DirectionBias.BULLISH) {
            return "SHORT_REVERSE".equals(timing);
        }
        if (candidate.trendDirection() == DirectionBias.BEARISH) {
            return "LONG_REVERSE".equals(timing);
        }
        return false;
    }

    private static boolean btcEthReversed(ContractCandidate candidate) {
        if (candidate.trendDirection() == DirectionBias.BULLISH) {
            return candidate.btcTrend() == DirectionBias.BEARISH && candidate.ethTrend() == DirectionBias.BEARISH;
        }
        if (candidate.trendDirection() == DirectionBias.BEARISH) {
            return candidate.btcTrend() == DirectionBias.BULLISH && candidate.ethTrend() == DirectionBias.BULLISH;
        }
        return false;
    }

    private static BigDecimal priceDeviationPct(BigDecimal currentPrice, BigDecimal referencePrice) {
        if (currentPrice == null || referencePrice == null || referencePrice.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(referencePrice).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(referencePrice, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal bpsToPct(BigDecimal bps) {
        if (bps == null) {
            return BigDecimal.ZERO;
        }
        return bps.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
    }
}

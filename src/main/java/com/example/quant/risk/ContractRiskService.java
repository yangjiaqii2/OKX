package com.example.quant.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ContractRiskService implements RiskService {

    @Override
    public RiskCheckResult check(ContractRiskRequest request) {
        if (request.emergencyStop()) {
            return RiskCheckResult.rejected("EMERGENCY_STOP", "emergency_stop=true，禁止下单");
        }
        if (!request.websocketConnected()) {
            return RiskCheckResult.rejected("WEBSOCKET_DISCONNECTED", "WebSocket断线，禁止下单");
        }
        if (request.marketDelayMs() > request.maxMarketDelayMs()) {
            return RiskCheckResult.rejected("MARKET_DELAY", "行情延迟超过阈值");
        }
        if (request.stopLossPrice() == null) {
            return RiskCheckResult.rejected("MISSING_STOP_LOSS", "没有止损价，禁止开仓");
        }
        if (request.leverage() > request.maxLeverage()) {
            return RiskCheckResult.rejected("LEVERAGE_TOO_HIGH", "杠杆超过最大配置");
        }
        BigDecimal maxMargin = request.accountEquity().multiply(request.maxSingleMarginRate());
        if (request.suggestedMargin().compareTo(maxMargin) > 0) {
            return RiskCheckResult.rejected("MARGIN_TOO_HIGH", "单笔保证金超过账户权益配置比例");
        }
        if (request.riskRewardRatio().compareTo(request.minRiskRewardRatio()) < 0) {
            return RiskCheckResult.rejected("RISK_REWARD_TOO_LOW", "盈亏比低于配置阈值");
        }
        if (request.fundingRate().abs().compareTo(request.maxAbsFundingRate()) > 0) {
            return RiskCheckResult.rejected("FUNDING_RATE_ABNORMAL", "资金费率过度异常");
        }
        if (request.volume24h().compareTo(request.minVolume24h()) < 0) {
            return RiskCheckResult.rejected("VOLUME_TOO_LOW", "24小时成交量太低");
        }
        if (request.newsRiskLevel() == RiskLevel.BLOCKED) {
            return RiskCheckResult.rejected("NEWS_RISK_BLOCKED", "新闻风险等级BLOCKED");
        }

        List<String> warnings = new ArrayList<>();
        RiskLevel riskLevel = RiskLevel.LOW;
        if (request.fundingRate().abs().compareTo(request.maxAbsFundingRate().multiply(BigDecimal.valueOf(0.8))) > 0) {
            warnings.add("资金费率接近阈值");
            riskLevel = RiskLevel.MEDIUM;
        }
        return new RiskCheckResult(true, riskLevel, null, null, warnings, null, request.leverage(), null, Instant.now());
    }
}

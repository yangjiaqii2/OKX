package com.example.quant.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ContractRiskService implements RiskService {

    @Override
    public RiskCheckResult check(ContractRiskRequest request) {
        if (request.emergencyStop()) {
            return RiskCheckResult.rejected("EMERGENCY_STOP", "Emergency stop is enabled");
        }
        if (!request.websocketConnected()) {
            return RiskCheckResult.rejected("WEBSOCKET_DISCONNECTED", "Market websocket is disconnected");
        }
        if (request.marketDelayMs() > request.maxMarketDelayMs()) {
            return RiskCheckResult.rejected("MARKET_DELAY", "Market data delay exceeds limit");
        }
        if (request.stopLossPrice() == null) {
            return RiskCheckResult.rejected("MISSING_STOP_LOSS", "Stop loss is required");
        }
        if (request.leverage() > request.maxLeverage()) {
            return RiskCheckResult.rejected("LEVERAGE_TOO_HIGH", "Leverage exceeds configured maximum");
        }
        BigDecimal maxMargin = request.accountEquity().multiply(request.maxSingleMarginRate());
        if (request.suggestedMargin().compareTo(maxMargin) > 0) {
            return RiskCheckResult.rejected("MARGIN_TOO_HIGH", "Margin exceeds configured single-trade cap");
        }
        if (request.riskRewardRatio().compareTo(request.minRiskRewardRatio()) < 0) {
            return RiskCheckResult.rejected("RISK_REWARD_TOO_LOW", "Risk/reward is below required threshold");
        }
        if (request.fundingRate().abs().compareTo(request.maxAbsFundingRate()) > 0) {
            return RiskCheckResult.rejected("FUNDING_RATE_ABNORMAL", "Funding rate is abnormal");
        }
        if (request.volume24h().compareTo(request.minVolume24h()) < 0) {
            return RiskCheckResult.rejected("VOLUME_TOO_LOW", "24h volume is below minimum liquidity");
        }
        if (request.newsRiskLevel() == RiskLevel.BLOCKED) {
            return RiskCheckResult.rejected("NEWS_RISK_BLOCKED", "External/news risk is blocked");
        }
        if (request.signalScore() < request.minSignalScore()) {
            return RiskCheckResult.rejected("SIGNAL_SCORE_TOO_LOW", "Signal score is below execution threshold");
        }

        BigDecimal stopDistance = request.entryPrice().subtract(request.stopLossPrice()).abs()
                .divide(request.entryPrice(), 8, RoundingMode.HALF_UP);
        if (stopDistance.compareTo(BigDecimal.ZERO) <= 0) {
            return RiskCheckResult.rejected("STOP_DISTANCE_INVALID", "Stop distance is invalid");
        }
        if (stopDistance.compareTo(request.maxStopLossDistanceRate()) > 0) {
            return RiskCheckResult.rejected("STOP_DISTANCE_TOO_WIDE", "Stop distance exceeds maximum risk band");
        }

        List<String> warnings = new ArrayList<>();
        RiskLevel riskLevel = request.newsRiskLevel();
        int dynamicLeverageCap = Math.max(1, request.maxLeverage());
        if (request.volatility().compareTo(BigDecimal.valueOf(0.08)) >= 0) {
            dynamicLeverageCap = Math.min(dynamicLeverageCap, 1);
            riskLevel = max(riskLevel, RiskLevel.HIGH);
            warnings.add("volatility >= 8%, cap leverage to 1x");
        } else if (request.volatility().compareTo(BigDecimal.valueOf(0.045)) >= 0) {
            dynamicLeverageCap = Math.min(dynamicLeverageCap, 2);
            riskLevel = max(riskLevel, RiskLevel.MEDIUM);
            warnings.add("volatility elevated, reduce leverage cap");
        }
        if (request.fundingRate().abs().compareTo(request.maxAbsFundingRate().multiply(BigDecimal.valueOf(0.9))) >= 0) {
            dynamicLeverageCap = Math.min(dynamicLeverageCap, 2);
            riskLevel = max(riskLevel, RiskLevel.MEDIUM);
            warnings.add("funding crowding near limit, reduce leverage cap");
        } else if (request.fundingRate().abs().compareTo(request.maxAbsFundingRate().multiply(BigDecimal.valueOf(0.75))) >= 0) {
            dynamicLeverageCap = Math.min(dynamicLeverageCap, 2);
            riskLevel = max(riskLevel, RiskLevel.MEDIUM);
            warnings.add("funding rate near limit");
        }
        if (stopDistance.compareTo(BigDecimal.valueOf(0.05)) >= 0) {
            dynamicLeverageCap = Math.min(dynamicLeverageCap, 1);
            riskLevel = max(riskLevel, RiskLevel.HIGH);
            warnings.add("wide stop distance, cap leverage to 1x");
        } else if (stopDistance.compareTo(BigDecimal.valueOf(0.03)) >= 0) {
            dynamicLeverageCap = Math.min(dynamicLeverageCap, 2);
            riskLevel = max(riskLevel, RiskLevel.MEDIUM);
            warnings.add("stop distance requires lower leverage");
        }
        if (request.signalScore() < 65) {
            dynamicLeverageCap = Math.min(dynamicLeverageCap, 1);
            riskLevel = max(riskLevel, RiskLevel.HIGH);
            warnings.add("signal score is weak, cap leverage to 1x");
        }
        if (request.newsRiskLevel() == RiskLevel.HIGH) {
            dynamicLeverageCap = Math.min(dynamicLeverageCap, 1);
            riskLevel = RiskLevel.HIGH;
            warnings.add("external/news risk high, cap leverage to 1x");
        }
        if (request.leverage() > dynamicLeverageCap) {
            return new RiskCheckResult(false, RiskLevel.BLOCKED, "LEVERAGE_ABOVE_DYNAMIC_CAP",
                    "Leverage exceeds dynamic risk cap", warnings, null, dynamicLeverageCap, null, Instant.now());
        }

        BigDecimal maxLoss = request.accountEquity()
                .multiply(request.maxSingleLossRate())
                .multiply(lossMultiplier(riskLevel))
                .setScale(8, RoundingMode.HALF_UP);
        return new RiskCheckResult(true, riskLevel, null, null, warnings, null, dynamicLeverageCap, maxLoss, Instant.now());
    }

    private static RiskLevel max(RiskLevel left, RiskLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private static BigDecimal lossMultiplier(RiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW -> BigDecimal.ONE;
            case MEDIUM -> BigDecimal.valueOf(0.5);
            case HIGH -> BigDecimal.valueOf(0.25);
            case BLOCKED -> BigDecimal.ZERO;
        };
    }
}

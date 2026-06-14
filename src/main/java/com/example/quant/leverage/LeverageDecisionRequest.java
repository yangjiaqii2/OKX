package com.example.quant.leverage;

import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;

public record LeverageDecisionRequest(
        String instId,
        int trendScore,
        BigDecimal volatility,
        BigDecimal fundingRate,
        BigDecimal openInterestChange,
        BigDecimal stopLossDistanceRate,
        BigDecimal accountEquity,
        int maxLeverageConfig,
        RiskLevel newsRiskLevel,
        BigDecimal confidence
) {
}

package com.example.quant.position;

import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;

public record PositionSizingRequest(
        BigDecimal accountEquity,
        BigDecimal availableBalance,
        BigDecimal entryPrice,
        BigDecimal stopLossPrice,
        BigDecimal takeProfitPrice,
        int suggestedLeverage,
        BigDecimal maxSingleLossRate,
        BigDecimal maxSingleMarginRate,
        BigDecimal minRiskRewardRatio,
        BigDecimal volatility,
        RiskLevel riskLevel
) {
}

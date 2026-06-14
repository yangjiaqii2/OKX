package com.example.quant.leverage;

import com.example.quant.risk.RiskLevel;
import java.util.List;

public record LeverageDecisionResult(
        int suggestedLeverage,
        int maxAllowedLeverage,
        List<String> leverageReasonList,
        List<String> leverageRiskList,
        RiskLevel riskLevel
) {
}

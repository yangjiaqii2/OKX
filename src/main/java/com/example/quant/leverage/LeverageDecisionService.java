package com.example.quant.leverage;

import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LeverageDecisionService {

    public LeverageDecisionResult decide(LeverageDecisionRequest request) {
        List<String> reasons = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        int maxAllowed = Math.max(1, request.maxLeverageConfig());
        if (!isMajorContract(request.instId())) {
            maxAllowed = Math.min(maxAllowed, 5);
        }

        if (request.newsRiskLevel() == RiskLevel.BLOCKED) {
            risks.add("风险等级BLOCKED，禁止开仓");
            return new LeverageDecisionResult(0, maxAllowed, reasons, risks, RiskLevel.BLOCKED);
        }

        int suggested = maxAllowed;
        RiskLevel riskLevel = request.newsRiskLevel();
        if (request.volatility().compareTo(BigDecimal.valueOf(0.08)) > 0) {
            suggested = Math.min(suggested, 1);
            riskLevel = RiskLevel.HIGH;
            risks.add("波动率过高，杠杆降至1x");
        } else if (request.volatility().compareTo(BigDecimal.valueOf(0.04)) > 0) {
            suggested = Math.min(suggested, 2);
            riskLevel = max(riskLevel, RiskLevel.MEDIUM);
            risks.add("波动率偏高，限制杠杆");
        }
        if (request.stopLossDistanceRate().compareTo(BigDecimal.valueOf(0.05)) > 0) {
            suggested = Math.min(suggested, 1);
            riskLevel = RiskLevel.HIGH;
            risks.add("止损距离过远，降低杠杆");
        }
        if (request.fundingRate().abs().compareTo(BigDecimal.valueOf(0.001)) > 0) {
            suggested = Math.min(suggested, 1);
            riskLevel = max(riskLevel, RiskLevel.HIGH);
            risks.add("资金费率极端，降低杠杆");
        }
        if (request.newsRiskLevel() == RiskLevel.HIGH) {
            suggested = Math.min(suggested, 1);
            riskLevel = RiskLevel.HIGH;
            risks.add("新闻风险高，杠杆降至1x");
        }
        if (request.trendScore() >= 80 && request.confidence().compareTo(BigDecimal.valueOf(0.75)) >= 0 && risks.isEmpty()) {
            reasons.add("趋势评分和置信度较高，允许使用配置上限内杠杆");
        } else {
            reasons.add("根据波动、资金费率、止损距离和新闻风险限制杠杆");
        }

        suggested = Math.max(1, Math.min(suggested, maxAllowed));
        return new LeverageDecisionResult(suggested, maxAllowed, reasons, risks, riskLevel);
    }

    private static RiskLevel max(RiskLevel left, RiskLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private static boolean isMajorContract(String instId) {
        return instId != null && (instId.startsWith("BTC-") || instId.startsWith("ETH-"));
    }
}

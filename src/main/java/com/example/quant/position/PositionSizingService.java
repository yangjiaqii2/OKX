package com.example.quant.position;

import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PositionSizingService {

    public PositionSizingResult calculate(PositionSizingRequest request) {
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (request.riskLevel() == RiskLevel.BLOCKED || request.suggestedLeverage() <= 0) {
            return rejected("风险等级不允许生成订单", warnings);
        }
        BigDecimal stopDistance = request.entryPrice().subtract(request.stopLossPrice()).abs()
                .divide(request.entryPrice(), 8, RoundingMode.HALF_UP);
        if (stopDistance.compareTo(BigDecimal.ZERO) <= 0) {
            return rejected("止损距离无效", warnings);
        }
        if (stopDistance.compareTo(BigDecimal.valueOf(0.1)) > 0) {
            return rejected("止损距离过大", warnings);
        }
        BigDecimal rewardDistance = request.takeProfitPrice().subtract(request.entryPrice()).abs()
                .divide(request.entryPrice(), 8, RoundingMode.HALF_UP);
        BigDecimal riskReward = rewardDistance.divide(stopDistance, 8, RoundingMode.HALF_UP);
        if (riskReward.compareTo(request.minRiskRewardRatio()) < 0) {
            return rejected("盈亏比低于配置阈值", warnings);
        }

        BigDecimal maxLoss = request.accountEquity().multiply(request.maxSingleLossRate());
        BigDecimal positionValue = maxLoss.divide(stopDistance, 8, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(request.suggestedLeverage()), 8, RoundingMode.HALF_UP);
        BigDecimal maxMargin = request.accountEquity().multiply(request.maxSingleMarginRate());
        BigDecimal allowedMargin = maxMargin.min(request.availableBalance());
        if (margin.compareTo(allowedMargin) > 0) {
            margin = allowedMargin;
            positionValue = margin.multiply(BigDecimal.valueOf(request.suggestedLeverage()));
            warnings.add("保证金超过单笔上限，已自动缩小仓位");
        }
        BigDecimal size = positionValue.divide(request.entryPrice(), 8, RoundingMode.HALF_UP);
        if (size.compareTo(BigDecimal.valueOf(0.0001)) < 0) {
            return rejected("仓位小于最小下单量", warnings);
        }
        reasons.add("按账户权益最大亏损倒推仓位");
        reasons.add("保证金受可用余额和单笔保证金比例约束");
        BigDecimal lossRate = maxLoss.divide(request.accountEquity(), 8, RoundingMode.HALF_UP);
        return new PositionSizingResult(true, size, margin, maxLoss, lossRate, positionValue, riskReward, reasons, warnings, null);
    }

    private static PositionSizingResult rejected(String reason, List<String> warnings) {
        return new PositionSizingResult(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), warnings, reason);
    }
}

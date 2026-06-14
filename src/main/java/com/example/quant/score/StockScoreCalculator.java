package com.example.quant.score;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StockScoreCalculator {

    public ScoreDetail calculate(StockScoreInput input) {
        List<String> reasons = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        if (input.st()) {
            risks.add("ST风险");
            return new ScoreDetail(10, "暂不推荐", "仅可做风险观察，存在ST风险和不确定性。", reasons, risks);
        }
        if (input.suspended()) {
            risks.add("停牌风险");
            return new ScoreDetail(10, "暂不推荐", "仅可做风险观察，当前停牌。", reasons, risks);
        }

        int score = 30;
        if (input.aboveMa5()) {
            score += 10;
            reasons.add("站上MA5");
        }
        if (input.aboveMa20()) {
            score += 12;
            reasons.add("站上MA20");
        }
        if (input.volumeRatio().compareTo(BigDecimal.valueOf(1.5)) >= 0) {
            score += 12;
            reasons.add("成交量放大");
        }
        if (between(input.changePercent(), 2, 8)) {
            score += 10;
            reasons.add("涨幅处于观察区间");
        }
        score += Math.min(12, input.sectorHeatScore());
        score += Math.min(10, input.moneyFlowScore());
        score -= input.negativeNewsCount() * 10;

        if (input.limitUp()) {
            risks.add("已涨停，买入不确定性高");
            score -= 8;
        }
        if (input.negativeNewsCount() > 0) {
            risks.add("存在负面消息");
        }
        score = Math.max(0, Math.min(100, score));
        return new ScoreDetail(score, level(score), "分析仅供观察，包含风险提示和不确定性说明。", reasons, risks);
    }

    private static boolean between(BigDecimal value, int min, int max) {
        return value.compareTo(BigDecimal.valueOf(min)) >= 0 && value.compareTo(BigDecimal.valueOf(max)) <= 0;
    }

    private static String level(int score) {
        if (score >= 80) {
            return "重点观察";
        }
        if (score >= 65) {
            return "可以观察";
        }
        if (score >= 50) {
            return "谨慎观察";
        }
        if (score >= 35) {
            return "风险较高";
        }
        return "暂不推荐";
    }
}

package com.example.quant.score;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ContractScoreCalculator {

    public ScoreDetail calculate(ContractScoreInput input) {
        List<String> reasons = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        int score = 20;
        if (input.trendScore() >= 70) {
            score += 25;
            reasons.add("趋势强度较好");
        } else {
            score += Math.max(0, input.trendScore() / 4);
        }
        if (input.volumeSpikeRatio().compareTo(BigDecimal.valueOf(1.8)) >= 0) {
            score += 20;
            reasons.add("成交量放大");
        }
        if (input.fundingRate().abs().compareTo(BigDecimal.valueOf(0.001)) <= 0) {
            score += 15;
        } else {
            score -= 20;
            risks.add("资金费率异常");
        }
        if (input.volatility().compareTo(BigDecimal.valueOf(0.06)) > 0) {
            score -= 18;
            risks.add("波动率过高");
        } else {
            score += 10;
        }
        score += input.positiveNewsCount() * 5;
        score -= input.negativeNewsCount() * 8;
        score = Math.max(0, Math.min(100, score));
        return new ScoreDetail(score, level(score), "合约分析需结合风控，结果不构成投资建议。", reasons, risks);
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

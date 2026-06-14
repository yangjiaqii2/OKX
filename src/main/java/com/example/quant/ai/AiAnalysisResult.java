package com.example.quant.ai;

import java.util.List;

public record AiAnalysisResult(
        String conclusion,
        String recommendLevel,
        String directionBias,
        String summary,
        List<String> riskTips,
        String watchCondition,
        String invalidCondition,
        String uncertainty
) {
}

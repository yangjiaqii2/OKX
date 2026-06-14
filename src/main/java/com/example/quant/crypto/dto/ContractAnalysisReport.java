package com.example.quant.crypto.dto;

import com.example.quant.market.DirectionBias;
import java.time.Instant;
import java.util.List;

public record ContractAnalysisReport(
        String instId,
        int score,
        String recommendLevel,
        DirectionBias directionBias,
        String summary,
        List<String> reasonList,
        List<String> riskList,
        String tradePlanSummary,
        String invalidCondition,
        Instant generatedAt
) {
}

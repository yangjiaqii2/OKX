package com.example.quant.stock.dto;

import com.example.quant.market.DirectionBias;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record StockAnalysisReport(
        String symbol,
        String name,
        BigDecimal price,
        int score,
        String recommendLevel,
        DirectionBias directionBias,
        String technicalSummary,
        String sectorSummary,
        String moneyFlowSummary,
        String newsSummary,
        String announcementSummary,
        List<String> riskList,
        List<String> reasonList,
        String watchCondition,
        String invalidCondition,
        String suggestion,
        Instant generatedAt
) {
}

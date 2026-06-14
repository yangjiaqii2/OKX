package com.example.quant.stock.dto;

import com.example.quant.market.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record StockCandidate(
        MarketType marketType,
        String symbol,
        String name,
        BigDecimal price,
        BigDecimal changePercent,
        BigDecimal turnoverAmount,
        BigDecimal volumeRatio,
        String sector,
        List<String> conceptList,
        BigDecimal ma5,
        BigDecimal ma20,
        List<String> candidateReasonList,
        List<String> riskTagList,
        Instant createdAt
) {
}

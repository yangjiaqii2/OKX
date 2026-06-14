package com.example.quant.news.dto;

import com.example.quant.market.MarketType;
import java.time.Instant;
import java.util.List;

public record NewsItem(
        String title,
        String source,
        String url,
        Instant publishTime,
        String contentSummary,
        String sentiment,
        int sentimentScore,
        int importanceScore,
        List<String> relatedSymbols,
        List<String> riskKeywords,
        MarketType marketType
) {
}

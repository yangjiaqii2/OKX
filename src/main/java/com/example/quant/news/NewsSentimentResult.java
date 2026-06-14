package com.example.quant.news;

import java.util.List;

public record NewsSentimentResult(String sentiment, int sentimentScore, List<String> riskKeywords) {
}

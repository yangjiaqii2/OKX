package com.example.quant.news;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NewsSentimentService {
    private static final List<String> POSITIVE = List.of("利好", "增长", "合作", "突破", "回购", "中标");
    private static final List<String> NEGATIVE = List.of("减持", "问询", "处罚", "亏损", "诉讼", "监管", "黑客", "攻击", "暴雷");

    public NewsSentimentResult analyze(String text) {
        int score = 0;
        List<String> riskKeywords = new ArrayList<>();
        for (String word : POSITIVE) {
            if (text.contains(word)) {
                score += 20;
            }
        }
        for (String word : NEGATIVE) {
            if (text.contains(word)) {
                score -= 25;
                riskKeywords.add(word);
            }
        }
        String sentiment = score > 0 ? "POSITIVE" : score < 0 ? "NEGATIVE" : "NEUTRAL";
        return new NewsSentimentResult(sentiment, score, riskKeywords);
    }
}

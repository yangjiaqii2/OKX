package com.example.quant.news;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NewsSentimentServiceTest {

    @Test
    void detectsPositiveKeywords() {
        NewsSentimentService service = new NewsSentimentService();

        NewsSentimentResult result = service.analyze("公司业绩增长，行业政策利好");

        assertThat(result.sentiment()).isEqualTo("POSITIVE");
        assertThat(result.sentimentScore()).isPositive();
    }

    @Test
    void detectsRiskKeywords() {
        NewsSentimentService service = new NewsSentimentService();

        NewsSentimentResult result = service.analyze("项目遭遇黑客攻击，监管处罚和诉讼风险上升");

        assertThat(result.sentiment()).isEqualTo("NEGATIVE");
        assertThat(result.riskKeywords()).contains("黑客", "处罚", "诉讼");
    }
}

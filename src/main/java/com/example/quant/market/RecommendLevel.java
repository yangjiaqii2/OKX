package com.example.quant.market;

public enum RecommendLevel {
    FOCUS("重点观察"),
    WATCH("可以观察"),
    CAUTIOUS("谨慎观察"),
    HIGH_RISK("风险较高"),
    NOT_RECOMMENDED("暂不推荐");

    private final String text;

    RecommendLevel(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}

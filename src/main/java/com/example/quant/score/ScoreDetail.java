package com.example.quant.score;

import java.util.List;

public record ScoreDetail(
        int score,
        String recommendLevel,
        String summary,
        List<String> reasonList,
        List<String> riskList
) {
}

package com.example.quant.position;

import java.math.BigDecimal;
import java.util.List;

public record PositionSizingResult(
        boolean accepted,
        BigDecimal suggestedSize,
        BigDecimal suggestedMargin,
        BigDecimal maxLossAmount,
        BigDecimal lossRate,
        BigDecimal positionValue,
        BigDecimal riskRewardRatio,
        List<String> reasonList,
        List<String> warningList,
        String rejectReason
) {
}

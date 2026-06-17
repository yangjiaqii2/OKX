package com.example.quant.account.dto;

import java.math.BigDecimal;

public record PositionSummary(
        String instId,
        String posSide,
        String size,
        BigDecimal avgPrice,
        BigDecimal unrealizedPnl,
        BigDecimal margin,
        BigDecimal initialMargin,
        String marginMode,
        BigDecimal leverage,
        BigDecimal liquidationPrice,
        BigDecimal notionalUsd,
        BigDecimal marginRatio,
        String mode
) {
    public PositionSummary(String instId, String posSide, String size, BigDecimal avgPrice,
                           BigDecimal unrealizedPnl, String mode) {
        this(instId, posSide, size, avgPrice, unrealizedPnl, BigDecimal.ZERO, BigDecimal.ZERO,
                "", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, mode);
    }
}

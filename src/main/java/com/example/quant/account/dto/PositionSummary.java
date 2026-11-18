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
        String mode,
        String ownership,
        String reconciliationReason,
        /**
         * Latest mark price reported by OKX for this position. Present so exit
         * logic can work in price space instead of reverse-engineering a price
         * from unrealised PnL. Null when the producer did not supply it.
         */
        BigDecimal markPrice,
        /** Latest traded price reported by OKX. Null when not supplied. */
        BigDecimal lastPrice
) {
    public PositionSummary(String instId, String posSide, String size, BigDecimal avgPrice,
                           BigDecimal unrealizedPnl, BigDecimal margin, BigDecimal initialMargin,
                           String marginMode, BigDecimal leverage, BigDecimal liquidationPrice,
                           BigDecimal notionalUsd, BigDecimal marginRatio, String mode) {
        this(instId, posSide, size, avgPrice, unrealizedPnl, margin, initialMargin, marginMode,
                leverage, liquidationPrice, notionalUsd, marginRatio, mode,
                "NOT_RECONCILED", "OWNERSHIP_UNKNOWN", null, null);
    }

    public PositionSummary(String instId, String posSide, String size, BigDecimal avgPrice,
                           BigDecimal unrealizedPnl, BigDecimal margin, BigDecimal initialMargin,
                           String marginMode, BigDecimal leverage, BigDecimal liquidationPrice,
                           BigDecimal notionalUsd, BigDecimal marginRatio, String mode,
                           String ownership, String reconciliationReason) {
        this(instId, posSide, size, avgPrice, unrealizedPnl, margin, initialMargin, marginMode,
                leverage, liquidationPrice, notionalUsd, marginRatio, mode,
                ownership, reconciliationReason, null, null);
    }

    public PositionSummary(String instId, String posSide, String size, BigDecimal avgPrice,
                           BigDecimal unrealizedPnl, String mode) {
        this(instId, posSide, size, avgPrice, unrealizedPnl, BigDecimal.ZERO, BigDecimal.ZERO,
                "", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, mode,
                "NOT_RECONCILED", "OWNERSHIP_UNKNOWN", null, null);
    }

    /**
     * Price used for exit logic. Falls back to last traded price and then to the
     * average entry price so a missing field degrades to "no movement" rather
     * than to a null that would silently disable protection.
     */
    public BigDecimal effectivePrice() {
        if (markPrice != null && markPrice.signum() > 0) {
            return markPrice;
        }
        if (lastPrice != null && lastPrice.signum() > 0) {
            return lastPrice;
        }
        return avgPrice;
    }
}

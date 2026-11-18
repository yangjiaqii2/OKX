package com.example.quant.okxtrade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record AlgoFillReconciliation(
        LedgerCompleteness completeness,
        BigDecimal totalSize,
        BigDecimal grossPnl,
        BigDecimal fee,
        BigDecimal averageFillPrice
) {
    public static AlgoFillReconciliation summarize(BigDecimal expectedSize,
                                                   List<OkxCurrentOrderService.Fill> fills) {
        BigDecimal size = BigDecimal.ZERO;
        BigDecimal pnl = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal notional = BigDecimal.ZERO;
        boolean factsComplete = true;
        if (fills != null) {
            for (OkxCurrentOrderService.Fill fill : fills) {
                if (fill == null) {
                    continue;
                }
                BigDecimal fillSize = zero(fill.fillSize());
                size = size.add(fillSize);
                pnl = pnl.add(zero(fill.fillPnl()));
                fee = fee.add(zero(fill.fee()));
                notional = notional.add(zero(fill.fillPrice()).multiply(fillSize));
                factsComplete &= fill.tradeId() != null && !fill.tradeId().isBlank()
                        && fill.fillPrice() != null && fill.fillPrice().signum() > 0
                        && fill.fillSize() != null && fill.fillSize().signum() > 0
                        && fill.fillPnl() != null && fill.fee() != null;
            }
        }
        BigDecimal expected = zero(expectedSize).abs();
        LedgerCompleteness completeness = expected.signum() > 0 && size.compareTo(expected) >= 0 && factsComplete
                ? LedgerCompleteness.COMPLETE
                : size.signum() > 0 ? LedgerCompleteness.PARTIAL : LedgerCompleteness.UNKNOWN;
        BigDecimal average = size.signum() > 0
                ? notional.divide(size, 12, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new AlgoFillReconciliation(completeness, size, pnl, fee, average);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

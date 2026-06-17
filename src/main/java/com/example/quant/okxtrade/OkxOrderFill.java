package com.example.quant.okxtrade;

import java.math.BigDecimal;

public record OkxOrderFill(
        String ordId,
        BigDecimal avgPx,
        BigDecimal filledSize
) {
    public boolean complete() {
        return avgPx != null && avgPx.signum() > 0
                && filledSize != null && filledSize.signum() > 0;
    }
}

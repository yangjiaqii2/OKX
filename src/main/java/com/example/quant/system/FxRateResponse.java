package com.example.quant.system;

import java.math.BigDecimal;
import java.time.Instant;

public record FxRateResponse(
        String base,
        String quote,
        BigDecimal rate,
        String source,
        Instant updatedAt
) {
}

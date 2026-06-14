package com.example.quant.crypto.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ContractCandle(
        Instant timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume
) {
}

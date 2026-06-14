package com.example.quant.account.dto;

import java.math.BigDecimal;

public record OkxAccountVerificationResult(
        boolean ok,
        String mode,
        String message,
        BigDecimal equity,
        BigDecimal availableBalance
) {
}

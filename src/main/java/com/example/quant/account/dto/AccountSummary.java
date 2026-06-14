package com.example.quant.account.dto;

import java.math.BigDecimal;

public record AccountSummary(BigDecimal equity, BigDecimal availableBalance, String mode, String message) {
}

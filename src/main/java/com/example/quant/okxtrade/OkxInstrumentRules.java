package com.example.quant.okxtrade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public record OkxInstrumentRules(
        String instId,
        BigDecimal contractValue,
        String contractValueCurrency,
        BigDecimal lotSize,
        BigDecimal minSize,
        BigDecimal tickSize
) {
    private static final BigDecimal DEFAULT_STEP = BigDecimal.ONE;

    public OkxInstrumentRules {
        contractValue = positiveOrDefault(contractValue, BigDecimal.ONE);
        lotSize = positiveOrDefault(lotSize, DEFAULT_STEP);
        minSize = positiveOrDefault(minSize, lotSize);
        tickSize = positiveOrDefault(tickSize, BigDecimal.ZERO);
        contractValueCurrency = contractValueCurrency == null || contractValueCurrency.isBlank()
                ? baseCurrency(instId)
                : contractValueCurrency;
    }

    public static OkxInstrumentRules defaultFor(String instId) {
        return new OkxInstrumentRules(instId, BigDecimal.ONE, baseCurrency(instId),
                DEFAULT_STEP, DEFAULT_STEP, BigDecimal.ZERO);
    }

    public BigDecimal contractSizeFromBaseQuantity(BigDecimal baseQuantity, BigDecimal price) {
        if (baseQuantity == null || baseQuantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (contractValueInQuoteCurrency()) {
            if (price == null || price.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return baseQuantity.multiply(price).divide(contractValue, 16, RoundingMode.DOWN);
        }
        return baseQuantity.divide(contractValue, 16, RoundingMode.DOWN);
    }

    public BigDecimal normalizeEntrySize(BigDecimal baseQuantity, BigDecimal price) {
        BigDecimal rawContracts = contractSizeFromBaseQuantity(baseQuantity, price);
        if (rawContracts.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal normalized = nearestToLot(rawContracts);
        if (normalized.signum() <= 0 || normalized.compareTo(minSize) < 0) {
            return ceilToLot(minSize);
        }
        return normalized;
    }

    public BigDecimal floorToLot(BigDecimal value) {
        return floorToStep(value, lotSize);
    }

    public BigDecimal normalizePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0 || tickSize.signum() <= 0) {
            return price;
        }
        BigDecimal rounded = price.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
        if (rounded.signum() <= 0) {
            return tickSize;
        }
        return rounded.stripTrailingZeros();
    }

    private BigDecimal ceilToLot(BigDecimal value) {
        return ceilToStep(value, lotSize);
    }

    private BigDecimal nearestToLot(BigDecimal value) {
        BigDecimal floor = floorToStep(value, lotSize);
        BigDecimal ceiling = ceilToStep(value, lotSize);
        if (floor.signum() <= 0) {
            return ceiling;
        }
        BigDecimal floorDistance = value.subtract(floor).abs();
        BigDecimal ceilingDistance = ceiling.subtract(value).abs();
        return ceilingDistance.compareTo(floorDistance) <= 0 ? ceiling : floor;
    }

    private boolean contractValueInQuoteCurrency() {
        String normalized = contractValueCurrency == null ? "" : contractValueCurrency.toUpperCase(Locale.ROOT);
        return "USDT".equals(normalized) || "USD".equals(normalized) || "USDC".equals(normalized);
    }

    private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (step == null || step.signum() <= 0) {
            return value.stripTrailingZeros();
        }
        return value.divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros();
    }

    private static BigDecimal ceilToStep(BigDecimal value, BigDecimal step) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (step == null || step.signum() <= 0) {
            return value.stripTrailingZeros();
        }
        return value.divide(step, 0, RoundingMode.CEILING).multiply(step).stripTrailingZeros();
    }

    private static BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value != null && value.signum() > 0 ? value : fallback;
    }

    private static String baseCurrency(String instId) {
        if (instId == null) {
            return "";
        }
        int index = instId.indexOf('-');
        return index > 0 ? instId.substring(0, index) : instId;
    }
}

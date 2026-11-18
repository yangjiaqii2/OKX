package com.example.quant.agent.exit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Profit-giveback accounting: how much of the profit a trade once held was
 * actually kept.
 *
 * <p>{@code givebackR = mfeR - realizedR}. The most severe failure mode this
 * metric exists to expose is {@code mfeR = +3R} ending at {@code realizedR = -1R}.
 *
 * <p>{@code realizedR} must come from real fills plus real fees/funding -
 * never from a trigger price. The caller is responsible for that; this class
 * only does the arithmetic and the boundary handling.
 */
public final class GivebackCalculator {
    private static final int SCALE = 8;
    private GivebackCalculator() {
    }

    public static Giveback calculate(BigDecimal mfeR, BigDecimal realizedR) {
        if (mfeR == null || mfeR.signum() <= 0) {
            // No favourable excursion to give back. Returning a ratio here would
            // either divide by zero or invent a negative giveback, both misleading.
            return new Giveback(BigDecimal.ZERO, null, "MFE_NOT_POSITIVE");
        }
        if (realizedR == null) {
            return new Giveback(null, null, "REALIZED_R_UNKNOWN");
        }
        BigDecimal giveback = mfeR.subtract(realizedR).max(BigDecimal.ZERO);
        BigDecimal ratio = giveback.divide(mfeR, SCALE, RoundingMode.HALF_UP);
        return new Giveback(giveback, ratio, giveback.signum() == 0 ? "NO_GIVEBACK" : "GIVEBACK_RECORDED");
    }

    public record Giveback(BigDecimal givebackR, BigDecimal givebackRatio, String status) {
        public Giveback {
            Objects.requireNonNull(status, "status");
        }

        public boolean complete() {
            return givebackR != null && givebackRatio != null;
        }
    }
}

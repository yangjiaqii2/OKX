package com.example.quant.agent.exit;

import java.math.BigDecimal;

/**
 * Enforces the single most important invariant of this whole feature:
 *
 * <pre>
 *   LONG  : newProtectedStop &gt;= oldProtectedStop
 *   SHORT : newProtectedStop &lt;= oldProtectedStop
 * </pre>
 *
 * <p>Profit that has already been locked must never be released again, no
 * matter what a later (or stale, or out-of-order) market observation says.
 *
 * <p>The R-based stop formula is already monotone by construction because it is
 * a non-decreasing function of the running peak, so this guard is
 * defence-in-depth: a violation means there is a bug or a concurrency problem,
 * and it must be surfaced as an alarm rather than silently applied.
 */
public final class MonotonicStopGuard {
    private MonotonicStopGuard() {
    }

    /**
     * @return true when {@code candidate} is at least as protective as {@code current}.
     */
    public static boolean improves(BigDecimal candidate, BigDecimal current,
                                   PositionExcursion.TradeSide side) {
        if (candidate == null || candidate.signum() <= 0) {
            return false;
        }
        if (current == null || current.signum() <= 0) {
            return true;
        }
        return side == PositionExcursion.TradeSide.SHORT
                ? candidate.compareTo(current) <= 0
                : candidate.compareTo(current) >= 0;
    }

    /**
     * @return true when {@code candidate} would release protection that is already held.
     */
    public static boolean isRegression(BigDecimal candidate, BigDecimal current,
                                       PositionExcursion.TradeSide side) {
        if (candidate == null || current == null || candidate.signum() <= 0 || current.signum() <= 0) {
            return false;
        }
        return !improves(candidate, current, side);
    }

    /**
     * Relative improvement of {@code candidate} over {@code current} in basis points.
     * Returns a large value when there is no current stop yet.
     */
    public static long improvementBps(BigDecimal candidate, BigDecimal current) {
        if (candidate == null || current == null || current.signum() <= 0 || candidate.signum() <= 0) {
            return Long.MAX_VALUE;
        }
        BigDecimal delta = candidate.subtract(current).abs();
        return delta.multiply(BigDecimal.valueOf(10_000))
                .divide(current, 0, java.math.RoundingMode.HALF_UP)
                .longValue();
    }
}

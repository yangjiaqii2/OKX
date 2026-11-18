package com.example.quant.agent.exit;

import java.math.BigDecimal;

/**
 * Tunable inputs of the profit-protection engine.
 *
 * <p><strong>Every value below is a BASELINE, not an optimum.</strong> They were
 * chosen to stay semantically aligned with the offline policies already
 * evaluated by {@code CounterfactualExitService} ({@code BE_075},
 * {@code TRAIL_1R}) so that historical counterfactual rows can be used to
 * calibrate them. None of them has been fitted to this project's trade history.
 *
 * <p>Kept as a plain record so the engine stays a pure function: the engine
 * receives this value, it never reads configuration, Spring or a clock.
 */
public record ProfitProtectionConfig(
        boolean enabled,

        /** mfeR at which the protective stop is pulled to effective break-even. */
        BigDecimal breakEvenActivationR,
        /** mfeR at which a positive amount of R starts to be locked. */
        BigDecimal profitLockActivationR,
        /** mfeR at which the position is considered to be trailing. */
        BigDecimal trailingActivationR,

        /** Share of the favourable excursion we are willing to give back (percent). */
        BigDecimal givebackRatioPct,
        /** Hard cap on giveback in R, so very large MFE is not eroded linearly. */
        BigDecimal maxGivebackR,

        /** Opening + closing fee, percent of notional. Used for effective break-even. */
        BigDecimal roundTripFeePct,
        /** Estimated slippage, percent of notional. Used for effective break-even. */
        BigDecimal estimatedSlippagePct,

        /** Minimum distance between the protective stop and the current mark price (percent). */
        BigDecimal stopBufferFromMarkPct,

        /** Minimum relative improvement (basis points) before an amend is worth sending. */
        int minStopImprovementBps,
        /** Minimum seconds between two protective-stop amends. */
        long minStopAmendIntervalSeconds,
        /** Rate limit: maximum amends within one rolling hour window. */
        int maxStopAmendsPerHour,

        /**
         * Reserved capability: whether trailing should cancel the remaining TP
         * legs so the runner can keep going. Default {@code false} and the
         * cancellation code path is intentionally not wired up yet - removing
         * protection needs demo evidence first.
         */
        boolean trailingCancelRemainingTp
) {
    public static ProfitProtectionConfig defaults() {
        return new ProfitProtectionConfig(
                true,
                new BigDecimal("0.75"),
                new BigDecimal("1.5"),
                new BigDecimal("3.0"),
                new BigDecimal("35"),
                new BigDecimal("3.0"),
                new BigDecimal("0.12"),
                new BigDecimal("0.05"),
                new BigDecimal("0.10"),
                5,
                60L,
                20,
                false);
    }

    public boolean isReservedTrailingCancelEnabled() {
        return trailingCancelRemainingTp;
    }
}

package com.example.quant.agent.exit;

import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Everything {@link ProfitProtectionEngine} is allowed to know. No services, no
 * repositories, no clock, no configuration lookup: the caller supplies an
 * already-resolved {@code now}, so the same context can be replayed for
 * historical simulation and parameter search.
 */
public record ExitContext(
        TradePlanType action,

        /** Actual entry price, normally the entry-fill VWAP. Never changed afterwards. */
        BigDecimal entryPrice,
        /** Canonical initial stop loss. Source of R. Never overwritten by protection. */
        BigDecimal initialStopLoss,
        /** Current observed price (mark price by default). */
        BigDecimal currentPrice,

        /** Running excursion already folded with {@link PositionExcursion#observe}. */
        PositionExcursion excursion,

        /** Protective stop currently confirmed on the exchange, if any. */
        BigDecimal appliedProtectiveStop,
        /** Durable exit state before this evaluation. */
        PositionExitState currentState,

        /** When the applied stop was last changed; drives the min-interval debounce. */
        Instant lastStopChangeAt,
        /** Amends already sent inside the current rolling hour window. */
        int amendsInCurrentWindow,

        BigDecimal tickSize,
        Instant now,
        ProfitProtectionConfig config
) {
    public ExitContext {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(now, "now");
        config = config == null ? ProfitProtectionConfig.defaults() : config;
    }

    public PositionExcursion.TradeSide side() {
        return PositionExcursion.TradeSide.from(action);
    }

    /** {@code |entryPrice - initialStopLoss|}: this trade's own 1R. */
    public BigDecimal initialRisk() {
        if (entryPrice == null || initialStopLoss == null) {
            return BigDecimal.ZERO;
        }
        return entryPrice.subtract(initialStopLoss).abs();
    }

    /** Current unrealised result in R. */
    public BigDecimal currentR() {
        BigDecimal risk = initialRisk();
        if (risk.signum() <= 0 || currentPrice == null || entryPrice == null) {
            return BigDecimal.ZERO;
        }
        return side().signed(entryPrice, currentPrice).divide(risk, 8, java.math.RoundingMode.HALF_UP);
    }
}

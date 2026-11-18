package com.example.quant.agent.exit;

/**
 * Durable profit-protection lifecycle of ONE managed position.
 *
 * <p>This is deliberately a separate dimension from
 * {@code com.example.quant.order.OrderStatus}. OrderStatus already carries
 * several legacy values that no code path ever writes (ACTIVE,
 * SIDEWAYS_TIMEOUT, MAX_HOLD_TIMEOUT); adding profit states there would mix
 * order delivery state with exit economics and make recovery ambiguous.
 *
 * <p>States are ordered and may only move forward. That monotonicity IS the
 * hysteresis mechanism: a pullback never demotes a position, so repeated
 * re-evaluation cannot make the lifecycle oscillate.
 */
public enum PositionExitState {
    /** Position facts not initialised yet. */
    NEW,
    /** Only the canonical initial stop loss is in force. */
    INITIAL_PROTECTION,
    /** Protective stop has been moved to (or above) the effective break-even. */
    BREAK_EVEN_ARMED,
    /** Protective stop already locks a positive amount of R. */
    PROFIT_LOCKED,
    /** Deep profit; stop follows the favourable excursion. */
    TRAILING,
    /** An exit has been initiated (terminal-intent). */
    EXITING,
    /** Terminal. */
    CLOSED;

    /**
     * Forward-only transition with two terminal overrides: any state may go to
     * {@link #EXITING} or {@link #CLOSED} (emergency / safety / max-hold).
     */
    public boolean canTransitionTo(PositionExitState target) {
        if (target == null || target == this) {
            return false;
        }
        if (target == EXITING || target == CLOSED) {
            return true;
        }
        return target.ordinal() > this.ordinal();
    }

    /**
     * Best of two states; used when re-deriving state after a restart so that a
     * partially recovered row can never be demoted.
     */
    public PositionExitState highest(PositionExitState other) {
        return other == null || ordinal() >= other.ordinal() ? this : other;
    }

    public boolean isTerminal() {
        return this == CLOSED;
    }

    public boolean isExitingOrClosed() {
        return this == EXITING || this == CLOSED;
    }
}

package com.example.quant.agent.exit;

/**
 * Actions the profit-protection engine may request.
 *
 * <p>{@code CLOSE} is intentionally absent. Profit protection only ever moves
 * an existing exchange-hosted protective stop; it never originates a close.
 * That keeps {@code AutoTradeLifecycleService} the single exit authority and
 * makes it structurally impossible for this engine to race with the max-hold,
 * loss-close or safety-close branches.
 */
public enum ExitAction {
    /** Move the protective stop toward profit. */
    TIGHTEN_STOP,
    /** Keep protecting; nothing to change right now. */
    HOLD,
    /** Deliberately do nothing (disabled, debounced, throttled or unsafe). */
    NO_OP
}

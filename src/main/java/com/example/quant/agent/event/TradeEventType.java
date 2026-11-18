package com.example.quant.agent.event;

public enum TradeEventType {
    AUTO_CANDIDATE_SKIPPED,
    PLAN_CREATED,
    BUDGET_RESERVED,
    ENTRY_SUBMITTED,
    ENTRY_SUBMIT_UNKNOWN,
    ENTRY_FILLED,
    ENTRY_PARTIAL_FILLED,
    ENTRY_TIMEOUT_CANCELLED,
    PROTECTION_SUBMITTED,
    PROTECTION_FAILED,
    SIDEWAYS_TP_REPLACED,
    MAX_HOLD_CLOSE_SUBMITTED,
    MANUAL_CLOSE_SUBMITTED,
    CLOSE_CONFIRMED,
    BUDGET_RELEASED,
    /** A position reached BREAK_EVEN_ARMED or a higher profit-protection stage. */
    PROFIT_PROTECTION_ARMED,
    /** The exchange-hosted protective stop was moved toward profit. */
    PROTECTIVE_STOP_TIGHTENED,
    /** Profit giveback was recorded when the position closed. */
    PROFIT_GIVEBACK_RECORDED,
    /** Profit protection could not be applied this round. */
    PROFIT_PROTECTION_FAILED,
    RECOVERY_FAILED
}

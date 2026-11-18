package com.example.quant.agent.exit;

/** Durable state of one canonical fixed-TP tranche. */
public enum ExitLegLedgerStatus {
    PLANNED,
    SUBMITTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REPLACED,
    OBSOLETE,
    RECONCILING
}

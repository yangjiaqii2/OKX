package com.example.quant.strategydecision;

public enum StrategyDecisionStatus {
    PROCESSING,
    WAITING_ENTRY,
    NO_SIGNAL,
    SIGNAL_CREATED,
    ORDER_SUBMITTED,
    FAILED_SAFE,
    RECONCILING
}

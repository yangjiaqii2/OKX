package com.example.quant.tradeplan;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic allocation of one immutable canonical plan onto the currently
 * filled position. It never changes target prices or fractions.
 */
public record ProtectionAllocation(
        UUID planId,
        BigDecimal positionContracts,
        BigDecimal stopLossContracts,
        List<CanonicalExecutablePlan.ExitLeg> takeProfitLegs,
        Status status,
        List<String> decisions
) {
    public ProtectionAllocation {
        takeProfitLegs = takeProfitLegs == null ? List.of() : List.copyOf(takeProfitLegs);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public enum Status {
        FULL,
        TP_REPLAN_REQUIRED,
        REJECTED
    }
}

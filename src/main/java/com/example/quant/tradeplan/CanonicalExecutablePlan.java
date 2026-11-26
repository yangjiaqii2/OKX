package com.example.quant.tradeplan;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Immutable, exchange-executable snapshot. Every downstream lifecycle
 * consumer must consume this snapshot instead of recomputing TP/SL legs.
 */
public record CanonicalExecutablePlan(
        UUID planId,
        String instId,
        TradePlanType action,
        String strategyVersion,
        String compilerVersion,
        Entry entry,
        StopLoss stopLoss,
        List<ExitLeg> takeProfitLegs,
        Risk risk,
        InstrumentSnapshot instrument,
        CompilationStatus status,
        List<String> decisions
) {
    public CanonicalExecutablePlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(instId, "instId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(status, "status");
        takeProfitLegs = takeProfitLegs == null ? List.of() : List.copyOf(takeProfitLegs);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public boolean accepted() {
        return status == CompilationStatus.ACCEPTED;
    }

    public enum CompilationStatus {
        ACCEPTED,
        REJECTED,
        REPLAN_REQUIRED,
        EXPLICIT_DEGRADE
    }

    public record Entry(
            BigDecimal intendedBaseQuantity,
            BigDecimal executableContractQuantity,
            BigDecimal normalizedEntryPrice,
            String priceRole
    ) {
        public Entry(BigDecimal intendedBaseQuantity, BigDecimal executableContractQuantity,
                     BigDecimal normalizedEntryPrice) {
            this(intendedBaseQuantity, executableContractQuantity, normalizedEntryPrice,
                    "LIMIT_EXECUTABLE");
        }

        @JsonIgnore
        public boolean isReferenceOnly() {
            return "REFERENCE".equalsIgnoreCase(priceRole);
        }
    }

    public record StopLoss(
            BigDecimal intendedPrice,
            BigDecimal normalizedPrice,
            String triggerSource,
            String normalization
    ) {}

    public record ExitLeg(
            String semanticId,
            BigDecimal intendedTargetPrice,
            BigDecimal executableTargetPrice,
            BigDecimal intendedFraction,
            BigDecimal executableContractQuantity,
            BigDecimal targetR,
            String reason,
            String normalization
    ) {}

    public record Risk(
            BigDecimal riskBudget,
            BigDecimal executableStopLossAmount,
            BigDecimal estimatedEntryPrice,
            BigDecimal estimatedNetStopLossAmount,
            BigDecimal estimatedNetRiskRewardRatio,
            Instant quoteTime
    ) {
        public Risk(BigDecimal riskBudget, BigDecimal executableStopLossAmount) {
            this(riskBudget, executableStopLossAmount, null, null, null, null);
        }
    }

    CanonicalExecutablePlan withRisk(Risk estimate) {
        return new CanonicalExecutablePlan(planId, instId, action, strategyVersion, compilerVersion,
                entry, stopLoss, takeProfitLegs, estimate, instrument, status, decisions);
    }

    public record InstrumentSnapshot(
            String instId,
            BigDecimal contractValue,
            String contractValueCurrency,
            BigDecimal lotSize,
            BigDecimal minSize,
            BigDecimal tickSize
    ) {}
}

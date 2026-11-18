package com.example.quant.tradeplan;

import com.example.quant.order.PendingOrder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable strategy-owned intent. It deliberately contains no exchange
 * precision or quantity decisions; those belong to {@link CanonicalPlanCompiler}.
 */
public record TradeIntent(
        UUID planId,
        String instId,
        TradePlanType action,
        String entryPolicy,
        BigDecimal intendedEntryPrice,
        BigDecimal initialStopLossPrice,
        BigDecimal intendedTakeProfitPrice,
        BigDecimal intendedBaseQuantity,
        BigDecimal riskBudget,
        String strategyVersion,
        List<ExitLeg> exitLegs
) {
    public TradeIntent {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(instId, "instId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(intendedEntryPrice, "intendedEntryPrice");
        Objects.requireNonNull(initialStopLossPrice, "initialStopLossPrice");
        Objects.requireNonNull(intendedTakeProfitPrice, "intendedTakeProfitPrice");
        Objects.requireNonNull(intendedBaseQuantity, "intendedBaseQuantity");
        exitLegs = exitLegs == null ? List.of() : List.copyOf(exitLegs);
        strategyVersion = strategyVersion == null || strategyVersion.isBlank()
                ? "strategy-unknown" : strategyVersion;
    }

    public record ExitLeg(
            String semanticId,
            BigDecimal targetR,
            BigDecimal intendedTargetPrice,
            BigDecimal intendedFraction,
            String reason
    ) {
        public ExitLeg {
            Objects.requireNonNull(semanticId, "semanticId");
            Objects.requireNonNull(intendedTargetPrice, "intendedTargetPrice");
            Objects.requireNonNull(intendedFraction, "intendedFraction");
        }
    }

    public static TradeIntent fromPlan(TradePlan plan, BigDecimal tp1Fraction,
                                       BigDecimal tp2Fraction, BigDecimal tp3Fraction,
                                       String strategyVersion) {
        Objects.requireNonNull(plan, "plan");
        BigDecimal risk = plan.entryPrice().subtract(plan.stopLossPrice()).abs();
        BigDecimal sign = plan.action() == TradePlanType.OPEN_SHORT ? BigDecimal.valueOf(-1) : BigDecimal.ONE;
        BigDecimal tp1 = plan.entryPrice().add(risk.multiply(sign));
        BigDecimal tp2 = plan.entryPrice().add(risk.multiply(BigDecimal.valueOf(2)).multiply(sign));
        BigDecimal finalR = risk.signum() == 0 ? BigDecimal.ZERO
                : plan.takeProfitPrice().subtract(plan.entryPrice()).multiply(sign)
                .divide(risk, 8, RoundingMode.HALF_UP);
        return new TradeIntent(
                plan.id(), plan.instId(), plan.action(), plan.orderType(), plan.entryPrice(),
                plan.stopLossPrice(), plan.takeProfitPrice(), plan.suggestedSize(),
                plan.riskBudgetAmount(), strategyVersion,
                ladder(tp1, tp2, plan.takeProfitPrice(), finalR, tp1Fraction, tp2Fraction, tp3Fraction));
    }

    public static TradeIntent fromPendingOrder(PendingOrder order, BigDecimal tp1Fraction,
                                                BigDecimal tp2Fraction, BigDecimal tp3Fraction,
                                                String strategyVersion) {
        Objects.requireNonNull(order, "order");
        BigDecimal risk = order.price().subtract(order.stopLossPrice()).abs();
        BigDecimal sign = order.action() == TradePlanType.OPEN_SHORT ? BigDecimal.valueOf(-1) : BigDecimal.ONE;
        BigDecimal tp1 = order.price().add(risk.multiply(sign));
        BigDecimal tp2 = order.price().add(risk.multiply(BigDecimal.valueOf(2)).multiply(sign));
        BigDecimal finalR = risk.signum() == 0 ? BigDecimal.ZERO
                : order.takeProfitPrice().subtract(order.price()).multiply(sign)
                .divide(risk, 8, RoundingMode.HALF_UP);
        return new TradeIntent(
                order.id(), order.instId(), order.action(),
                order.orderType(), order.price(), order.stopLossPrice(), order.takeProfitPrice(), order.intendedBaseQuantity(),
                order.maxLossAmount(), strategyVersion,
                ladder(tp1, tp2, order.takeProfitPrice(), finalR, tp1Fraction, tp2Fraction, tp3Fraction));
    }

    private static List<ExitLeg> ladder(BigDecimal tp1, BigDecimal tp2, BigDecimal finalPrice,
                                        BigDecimal finalR, BigDecimal tp1Fraction,
                                        BigDecimal tp2Fraction, BigDecimal tp3Fraction) {
        if (finalR.compareTo(BigDecimal.valueOf(2)) < 0) {
            return List.of(
                    new ExitLeg("TP1", BigDecimal.ONE, tp1, tp1Fraction, "TP1: 1R"),
                    new ExitLeg("TP_FINAL", finalR, finalPrice, tp2Fraction.add(tp3Fraction),
                            "final target below 2R; merged remaining allocation")
            );
        }
        if (finalR.compareTo(BigDecimal.valueOf(2)) == 0) {
            return List.of(
                    new ExitLeg("TP1", BigDecimal.ONE, tp1, tp1Fraction, "TP1: 1R"),
                    new ExitLeg("TP2", BigDecimal.valueOf(2), tp2, tp2Fraction.add(tp3Fraction),
                            "2R equals final target; merged final allocation")
            );
        }
        return List.of(
                new ExitLeg("TP1", BigDecimal.ONE, tp1, tp1Fraction, "TP1: 1R"),
                new ExitLeg("TP2", BigDecimal.valueOf(2), tp2, tp2Fraction, "TP2: 2R"),
                new ExitLeg("TP3", finalR, finalPrice, tp3Fraction, "TP3: plan target")
        );
    }
}

package com.example.quant.tradeplan;

import com.example.quant.okxtrade.OkxInstrumentRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pure deterministic compiler from strategy intent to an executable OKX
 * position plan. It never queries time, databases, or market data.
 */
@Component
public class CanonicalPlanCompiler {
    public static final String VERSION = "canonical-plan-v1";
    public static final String ENTRY_SIZE_REDUCED_TO_RISK_BUDGET = "ENTRY_SIZE_REDUCED_TO_RISK_BUDGET";
    private static final BigDecimal ONE = BigDecimal.ONE;

    public CanonicalExecutablePlan compile(TradeIntent intent, OkxInstrumentRules rules) {
        return compile(intent, rules, false);
    }

    public CanonicalExecutablePlan compileAutomaticEntry(TradeIntent intent, OkxInstrumentRules rules) {
        return compile(intent, rules, true);
    }

    private CanonicalExecutablePlan compile(TradeIntent intent, OkxInstrumentRules rules, boolean resizeToRiskBudget) {
        if (intent == null) {
            throw new IllegalArgumentException("TradeIntent 不能为空");
        }
        if (rules == null) {
            throw new IllegalArgumentException("OKX 合约规则不能为空");
        }

        List<String> decisions = new ArrayList<>();
        boolean shortSide = intent.action() == TradePlanType.OPEN_SHORT;
        BigDecimal entry = normalizePrice(intent.intendedEntryPrice(), rules, shortSide, PriceRole.ENTRY);
        BigDecimal stop = normalizePrice(intent.initialStopLossPrice(), rules, shortSide, PriceRole.STOP);
        if (!validStopGeometry(shortSide, entry, stop)) {
            return rejected(intent, rules, decisions, "INVALID_STOP_GEOMETRY");
        }

        BigDecimal contracts = rules.normalizeEntrySize(intent.intendedBaseQuantity(), intent.intendedEntryPrice());
        if (contracts.signum() <= 0 || contracts.compareTo(rules.minSize()) < 0) {
            return rejected(intent, rules, decisions, "ENTRY_MIN_SIZE_UNEXPRESSIBLE");
        }

        BigDecimal executableStopLoss = calculateExecutableStopLossAmount(entry, stop, contracts, rules);
        if (intent.riskBudget() != null && intent.riskBudget().signum() > 0
                && executableStopLoss.compareTo(intent.riskBudget()) > 0) {
            if (!resizeToRiskBudget) {
                return rejected(intent, rules, decisions, "EXECUTABLE_RISK_EXCEEDS_RISK_BUDGET");
            }
            contracts = contracts.min(maxContractsForRiskBudget(entry, stop, intent.riskBudget(), rules));
            if (contracts.signum() <= 0 || contracts.compareTo(rules.minSize()) < 0) {
                return rejected(intent, rules, decisions, "EXECUTABLE_RISK_EXCEEDS_RISK_BUDGET");
            }
            executableStopLoss = calculateExecutableStopLossAmount(entry, stop, contracts, rules);
            if (executableStopLoss.compareTo(intent.riskBudget()) > 0) {
                return rejected(intent, rules, decisions, "EXECUTABLE_RISK_EXCEEDS_RISK_BUDGET");
            }
            decisions.add(ENTRY_SIZE_REDUCED_TO_RISK_BUDGET);
        }

        if (intent.exitLegs().isEmpty()) {
            return rejected(intent, rules, decisions, "TP_LADDER_EMPTY");
        }
        BigDecimal fractionSum = intent.exitLegs().stream()
                .map(TradeIntent.ExitLeg::intendedFraction)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (fractionSum.compareTo(ONE) != 0) {
            return rejected(intent, rules, decisions, "TP_FRACTIONS_MUST_SUM_TO_ONE");
        }
        if (intent.exitLegs().stream().anyMatch(leg -> leg.intendedFraction().signum() <= 0)) {
            return rejected(intent, rules, decisions, "TP_FRACTIONS_MUST_BE_POSITIVE");
        }

        List<CanonicalExecutablePlan.ExitLeg> legs = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal previousTarget = null;
        for (int i = 0; i < intent.exitLegs().size(); i++) {
            TradeIntent.ExitLeg source = intent.exitLegs().get(i);
            BigDecimal target = normalizePrice(source.intendedTargetPrice(), rules, shortSide, PriceRole.TARGET);
            if (!validTargetGeometry(shortSide, entry, target)) {
                return rejected(intent, rules, decisions, source.semanticId() + "_INVALID_TARGET_GEOMETRY");
            }
            if (previousTarget != null) {
                int ordering = target.compareTo(previousTarget);
                if ((!shortSide && ordering <= 0) || (shortSide && ordering >= 0)) {
                    return rejected(intent, rules, decisions, "TP_LADDER_NOT_MONOTONIC");
                }
            }
            previousTarget = target;
            BigDecimal quantity = i == intent.exitLegs().size() - 1
                    ? rules.floorToLot(contracts.subtract(allocated))
                    : rules.floorToLot(contracts.multiply(source.intendedFraction()));
            if (quantity.compareTo(rules.minSize()) < 0) {
                return rejected(intent, rules, decisions,
                        source.semanticId() + "_MIN_SIZE_UNEXPRESSIBLE");
            }
            allocated = allocated.add(quantity);
            if (allocated.compareTo(contracts) > 0) {
                return rejected(intent, rules, decisions, "TP_QUANTITY_EXCEEDS_POSITION");
            }
            String normalization = source.intendedTargetPrice().compareTo(target) == 0
                    ? "UNCHANGED" : "TICK_SIZE_NORMALIZATION";
            legs.add(new CanonicalExecutablePlan.ExitLeg(
                    source.semanticId(), source.intendedTargetPrice(), target,
                    source.intendedFraction(), quantity, source.targetR(), source.reason(), normalization));
        }
        if (allocated.compareTo(contracts) != 0) {
            return rejected(intent, rules, decisions, "TP_LADDER_REMAINDER_UNEXPRESSIBLE");
        }

        decisions.add("ACCEPTED");
        return new CanonicalExecutablePlan(
                intent.planId(), intent.instId(), intent.action(), intent.strategyVersion(), VERSION,
                new CanonicalExecutablePlan.Entry(intent.intendedBaseQuantity(), contracts, entry,
                        "MARKET".equalsIgnoreCase(intent.entryPolicy())
                                ? "REFERENCE" : "LIMIT_EXECUTABLE"),
                new CanonicalExecutablePlan.StopLoss(intent.initialStopLossPrice(), stop, "last", 
                        intent.initialStopLossPrice().compareTo(stop) == 0 ? "UNCHANGED" : "TICK_SIZE_NORMALIZATION"),
                legs,
                new CanonicalExecutablePlan.Risk(intent.riskBudget(), executableStopLoss),
                snapshot(rules), CanonicalExecutablePlan.CompilationStatus.ACCEPTED, decisions);
    }

    /**
     * Scale the already compiled protection ladder for a confirmed partial fill.
     * Prices, target-R and fractions are never recomputed here; only quantities
     * are allocated against the canonical filled position.
     */
    public List<CanonicalExecutablePlan.ExitLeg> scaleExitLegs(CanonicalExecutablePlan plan,
                                                                BigDecimal filledContracts,
                                                                OkxInstrumentRules rules) {
        if (plan == null || plan.status() != CanonicalExecutablePlan.CompilationStatus.ACCEPTED) {
            throw new IllegalArgumentException("只能缩放已接受的 canonical plan");
        }
        BigDecimal contracts = rules.floorToLot(filledContracts);
        if (contracts.signum() <= 0 || contracts.compareTo(rules.minSize()) < 0) {
            throw new IllegalArgumentException("成交数量低于 OKX 最小下单量");
        }
        return scaleExitLegsInternal(plan, contracts, rules, true);
    }

    private List<CanonicalExecutablePlan.ExitLeg> scaleExitLegsInternal(CanonicalExecutablePlan plan,
                                                                          BigDecimal contracts,
                                                                          OkxInstrumentRules rules,
                                                                          boolean preserveSnapshotPrices) {
        List<CanonicalExecutablePlan.ExitLeg> scaled = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        List<CanonicalExecutablePlan.ExitLeg> sourceLegs = plan.takeProfitLegs();
        for (int i = 0; i < sourceLegs.size(); i++) {
            CanonicalExecutablePlan.ExitLeg source = sourceLegs.get(i);
            BigDecimal quantity = i == sourceLegs.size() - 1
                    ? rules.floorToLot(contracts.subtract(allocated))
                    : rules.floorToLot(contracts.multiply(source.intendedFraction()));
            if (quantity.compareTo(rules.minSize()) < 0) {
                throw new IllegalArgumentException(source.semanticId() + "_MIN_SIZE_UNEXPRESSIBLE");
            }
            allocated = allocated.add(quantity);
            BigDecimal target = preserveSnapshotPrices
                    ? source.executableTargetPrice()
                    : normalizeTargetPrice(source.executableTargetPrice(), plan.action(), rules);
            if (!validTargetGeometry(plan.action() == TradePlanType.OPEN_SHORT,
                    plan.entry().normalizedEntryPrice(), target)) {
                throw new IllegalArgumentException(source.semanticId() + "_INVALID_TARGET_GEOMETRY");
            }
            scaled.add(new CanonicalExecutablePlan.ExitLeg(
                    source.semanticId(), source.intendedTargetPrice(), target,
                    source.intendedFraction(), quantity, source.targetR(), source.reason(), source.normalization()));
        }
        if (allocated.compareTo(contracts) != 0) {
            throw new IllegalArgumentException("TP_LADDER_REMAINDER_UNEXPRESSIBLE");
        }
        return List.copyOf(scaled);
    }

    /**
     * Allocate the immutable canonical exit semantics to the currently filled
     * position. A small position may keep its SL while its TP ladder is marked
     * for replanning; it is never silently merged or resized.
     */
    public ProtectionAllocation allocateProtection(CanonicalExecutablePlan plan,
                                                   BigDecimal filledContracts,
                                                   OkxInstrumentRules rules) {
        if (plan == null || plan.status() != CanonicalExecutablePlan.CompilationStatus.ACCEPTED) {
            throw new IllegalArgumentException("只能分配已接受的 canonical plan");
        }
        BigDecimal contracts = rules == null ? BigDecimal.ZERO : rules.floorToLot(filledContracts);
        if (contracts.signum() <= 0 || contracts.compareTo(rules.minSize()) < 0) {
            return new ProtectionAllocation(plan.planId(), contracts, BigDecimal.ZERO, List.of(),
                    ProtectionAllocation.Status.REJECTED, List.of("POSITION_MIN_SIZE_UNEXPRESSIBLE"));
        }
        try {
            List<CanonicalExecutablePlan.ExitLeg> legs = scaleExitLegs(plan, contracts, rules);
            return new ProtectionAllocation(plan.planId(), contracts, contracts, legs,
                    ProtectionAllocation.Status.FULL, List.of("TP_LADDER_ALLOCATED"));
        } catch (IllegalArgumentException ex) {
            return new ProtectionAllocation(plan.planId(), contracts, contracts, List.of(),
                    ProtectionAllocation.Status.TP_REPLAN_REQUIRED,
                    List.of("TP_LADDER_UNEXPRESSIBLE", ex.getMessage()));
        }
    }

    /** Re-allocates approved exit semantics against current exchange rules. */
    public ProtectionAllocation allocateProtectionForCurrentRules(CanonicalExecutablePlan plan,
                                                                    BigDecimal filledContracts,
                                                                    OkxInstrumentRules rules) {
        if (plan == null || plan.status() != CanonicalExecutablePlan.CompilationStatus.ACCEPTED
                || rules == null) {
            throw new IllegalArgumentException("canonical plan/rules 无效");
        }
        BigDecimal contracts = rules.floorToLot(filledContracts);
        if (contracts.signum() <= 0 || contracts.compareTo(rules.minSize()) < 0) {
            return new ProtectionAllocation(plan.planId(), contracts, BigDecimal.ZERO, List.of(),
                    ProtectionAllocation.Status.REJECTED, List.of("POSITION_MIN_SIZE_UNEXPRESSIBLE"));
        }
        try {
            List<CanonicalExecutablePlan.ExitLeg> legs = scaleExitLegsInternal(plan, contracts, rules, false);
            return new ProtectionAllocation(plan.planId(), contracts, contracts, legs,
                    ProtectionAllocation.Status.FULL, List.of("TP_LADDER_ALLOCATED_CURRENT_RULES"));
        } catch (IllegalArgumentException ex) {
            return new ProtectionAllocation(plan.planId(), contracts, contracts, List.of(),
                    ProtectionAllocation.Status.TP_REPLAN_REQUIRED,
                    List.of("TP_LADDER_UNEXPRESSIBLE", ex.getMessage()));
        }
    }

    /** Directional tick normalization for an already-approved stop intent. */
    public BigDecimal normalizeStopPrice(BigDecimal price, TradePlanType action, OkxInstrumentRules rules) {
        return normalizePrice(price, rules, action == TradePlanType.OPEN_SHORT, PriceRole.STOP);
    }

    /** Directional tick normalization for an already-approved target intent. */
    public BigDecimal normalizeTargetPrice(BigDecimal price, TradePlanType action, OkxInstrumentRules rules) {
        return normalizePrice(price, rules, action == TradePlanType.OPEN_SHORT, PriceRole.TARGET);
    }

    /** The persisted snapshot is authoritative for an existing pending order. */
    public boolean matchesSnapshot(CanonicalExecutablePlan plan, OkxInstrumentRules rules) {
        if (plan == null || plan.instrument() == null || rules == null) {
            return false;
        }
        CanonicalExecutablePlan.InstrumentSnapshot snapshot = plan.instrument();
        return snapshot.instId() != null && snapshot.contractValue() != null
                && snapshot.lotSize() != null && snapshot.minSize() != null && snapshot.tickSize() != null
                && snapshot.instId().equalsIgnoreCase(rules.instId())
                && snapshot.contractValue().compareTo(rules.contractValue()) == 0
                && java.util.Objects.equals(snapshot.contractValueCurrency(), rules.contractValueCurrency())
                && snapshot.lotSize().compareTo(rules.lotSize()) == 0
                && snapshot.minSize().compareTo(rules.minSize()) == 0
                && snapshot.tickSize().compareTo(rules.tickSize()) == 0;
    }

    private static CanonicalExecutablePlan rejected(TradeIntent intent, OkxInstrumentRules rules,
                                                     List<String> decisions, String reason) {
        decisions.add(reason);
        return new CanonicalExecutablePlan(
                intent.planId(), intent.instId(), intent.action(), intent.strategyVersion(), VERSION,
                null, null, List.of(),
                new CanonicalExecutablePlan.Risk(intent.riskBudget(), null),
                snapshot(rules), CanonicalExecutablePlan.CompilationStatus.REJECTED, decisions);
    }

    private static CanonicalExecutablePlan.InstrumentSnapshot snapshot(OkxInstrumentRules rules) {
        return new CanonicalExecutablePlan.InstrumentSnapshot(
                rules.instId(), rules.contractValue(), rules.contractValueCurrency(),
                rules.lotSize(), rules.minSize(), rules.tickSize());
    }

    /** Calculates loss using the normalized prices and final contract quantity. */
    public BigDecimal calculateExecutableStopLossAmount(BigDecimal entry, BigDecimal stop,
                                                        BigDecimal contracts, OkxInstrumentRules rules) {
        if (entry == null || stop == null || contracts == null || rules == null
                || entry.signum() <= 0 || contracts.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = rules.contractValueInQuoteCurrencyForAudit()
                ? contracts.multiply(rules.contractValue()).divide(entry, 16, RoundingMode.DOWN)
                : contracts.multiply(rules.contractValue());
        return entry.subtract(stop).abs().multiply(base).setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal maxContractsForRiskBudget(BigDecimal entry, BigDecimal stop, BigDecimal riskBudget,
                                                        OkxInstrumentRules rules) {
        BigDecimal lossPerLot = entry.subtract(stop).abs().multiply(rules.contractValue()).multiply(rules.lotSize());
        BigDecimal budget = rules.contractValueInQuoteCurrencyForAudit() ? riskBudget.multiply(entry) : riskBudget;
        return budget.divide(lossPerLot, 0, RoundingMode.DOWN).multiply(rules.lotSize());
    }

    private enum PriceRole { ENTRY, STOP, TARGET }

    private static BigDecimal normalizePrice(BigDecimal price, OkxInstrumentRules rules,
                                              boolean shortSide, PriceRole role) {
        if (price == null || rules.tickSize() == null || rules.tickSize().signum() <= 0) {
            return price;
        }
        RoundingMode mode;
        mode = switch (role) {
            case ENTRY, STOP -> shortSide ? RoundingMode.CEILING : RoundingMode.FLOOR;
            case TARGET -> shortSide ? RoundingMode.FLOOR : RoundingMode.CEILING;
        };
        return price.divide(rules.tickSize(), 0, mode).multiply(rules.tickSize()).stripTrailingZeros();
    }

    private static boolean validStopGeometry(boolean shortSide, BigDecimal entry, BigDecimal stop) {
        return entry != null && stop != null && (shortSide ? stop.compareTo(entry) > 0 : stop.compareTo(entry) < 0);
    }

    private static boolean validTargetGeometry(boolean shortSide, BigDecimal entry, BigDecimal target) {
        return entry != null && target != null && (shortSide ? target.compareTo(entry) < 0 : target.compareTo(entry) > 0);
    }
}

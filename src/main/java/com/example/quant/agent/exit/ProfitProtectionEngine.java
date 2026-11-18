package com.example.quant.agent.exit;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic profit-protection engine.
 *
 * <p><strong>Pure function.</strong> It depends on nothing but its arguments:
 * no Spring, no repository, no gateway, no service and no clock. The caller
 * supplies an already-resolved {@code now}. That is what makes historical
 * replay and parameter search possible against exactly the same code that runs
 * live.
 *
 * <p>What it does:
 * <ol>
 *   <li>Classifies the trade into a profit stage from its own MFE in R.</li>
 *   <li>Computes the protective stop that stage should defend.</li>
 *   <li>Clamps it so it can never be worse than the initial stop, never sit
 *       too close to the market, and never be expressed below tick size.</li>
 *   <li>Refuses anything that is not an improvement on what is already
 *       confirmed on the exchange.</li>
 * </ol>
 *
 * <p>What it never does: it never closes a position (see {@link ExitAction}) and
 * it never touches the initial stop loss, which stays the source of R for
 * position sizing, MFE/MAE and realised-R statistics.
 *
 * <h2>Why the formula is monotone</h2>
 * The stop is {@code mfePeakR - min(maxGivebackR, mfePeakR * givebackRatio)} in
 * R. Both branches are non-decreasing in {@code mfePeakR}, and the peak itself
 * never decreases, so the resulting stop can only move in the protective
 * direction. {@link MonotonicStopGuard} is still applied as defence in depth.
 */
public final class ProfitProtectionEngine {
    private static final int SCALE = 8;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ProfitProtectionEngine() {
    }

    public static ExitDecision evaluate(ExitContext ctx) {
        ProfitProtectionConfig config = ctx.config();
        if (!config.enabled()) {
            return respond(ctx, null, ctx.currentState(), "PROFIT_PROTECTION_DISABLED");
        }
        BigDecimal entry = ctx.entryPrice();
        BigDecimal initialStop = ctx.initialStopLoss();
        BigDecimal mark = ctx.currentPrice();
        PositionExcursion excursion = ctx.excursion();
        if (entry == null || entry.signum() <= 0) {
            return respond(ctx, null, ctx.currentState(), "EXIT_FACTS_INVALID_ENTRY");
        }
        if (initialStop == null || initialStop.signum() <= 0) {
            return respond(ctx, null, ctx.currentState(), "EXIT_FACTS_INVALID_INITIAL_STOP");
        }
        BigDecimal risk = ctx.initialRisk();
        if (risk.signum() <= 0) {
            return respond(ctx, null, ctx.currentState(), "EXIT_FACTS_INVALID_INITIAL_RISK");
        }
        if (mark == null || mark.signum() <= 0) {
            return respond(ctx, null, ctx.currentState(), "EXIT_FACTS_INVALID_MARK_PRICE");
        }
        if (excursion == null) {
            return respond(ctx, null, ctx.currentState(), "EXIT_FACTS_MISSING_EXCURSION");
        }

        PositionExcursion.TradeSide side = ctx.side();
        BigDecimal mfeR = excursion.mfeROrZero();
        PositionExitState stage = stageFor(mfeR, config);
        PositionExitState toState = stage.highest(ctx.currentState());

        BigDecimal target;
        String stageReason;
        BigDecimal givebackR = BigDecimal.ZERO;
        if (stage == PositionExitState.INITIAL_PROTECTION) {
            target = initialStop;
            stageReason = "BELOW_BREAK_EVEN_ACTIVATION";
        } else if (stage == PositionExitState.BREAK_EVEN_ARMED) {
            target = effectiveBreakEvenPrice(entry, side, ctx.tickSize(), config);
            stageReason = "BREAK_EVEN_ARMED";
        } else {
            givebackR = maxGiveback(mfeR, config);
            BigDecimal stopR = mfeR.subtract(givebackR).max(BigDecimal.ZERO);
            target = side.priceAt(entry, risk, stopR);
            stageReason = stage == PositionExitState.TRAILING ? "TRAILING_PROFIT_LOCK" : "PROFIT_LOCK";
        }

        // Clamp 1: never release the original risk definition.
        if (MonotonicStopGuard.isRegression(target, initialStop, side)) {
            target = initialStop;
            stageReason = stageReason + "_CLAMPED_TO_INITIAL_STOP";
        }
        // Clamp 2: keep a buffer from the market so the stop is not triggered by noise.
        BigDecimal buffered = side == PositionExcursion.TradeSide.SHORT
                ? mark.multiply(ONE.add(percent(config.stopBufferFromMarkPct())))
                : mark.multiply(ONE.subtract(percent(config.stopBufferFromMarkPct())));
        target = side == PositionExcursion.TradeSide.SHORT ? target.max(buffered) : target.min(buffered);
        // Clamp 3: tick normalization, rounded away from entry so tick rounding
        // only ever makes the stop wider (mirrors CanonicalPlanCompiler STOP role).
        target = normalizeStop(target, side, ctx.tickSize());

        BigDecimal applied = ctx.appliedProtectiveStop();
        if (applied == null || applied.signum() <= 0) {
            // No confirmed exchange stop: establishing protection is the
            // lifecycle's job, not this engine's.
            return respond(ctx, null, toState, "NO_APPLIED_PROTECTIVE_STOP");
        }

        ExitDecision.Evidence evidence = baseEvidence(ctx, mfeR, givebackR, stageReason);
        if (MonotonicStopGuard.isRegression(target, applied, side)) {
            // Structurally unreachable given the monotone peak; if it ever
            // happens it is a bug or a stale out-of-order observation.
            return new ExitDecision(ExitAction.NO_OP, "PROFIT_PROTECTION_INVARIANT_VIOLATION",
                    null, ctx.currentState(), toState, evidence.build());
        }
        if (target.compareTo(applied) == 0) {
            return respond(ctx, null, toState, "PROTECTIVE_STOP_ALREADY_AT_TARGET", evidence);
        }
        long improvement = MonotonicStopGuard.improvementBps(target, applied);
        evidence.put("improvementBps", improvement);
        if (improvement < config.minStopImprovementBps()) {
            return respond(ctx, null, toState, "STOP_IMPROVEMENT_BELOW_THRESHOLD", evidence);
        }
        if (ctx.lastStopChangeAt() != null) {
            long elapsed = java.time.Duration.between(ctx.lastStopChangeAt(), ctx.now()).toSeconds();
            if (elapsed < config.minStopAmendIntervalSeconds()) {
                return respond(ctx, null, toState, "STOP_AMEND_INTERVAL_NOT_ELAPSED", evidence);
            }
        }
        if (ctx.amendsInCurrentWindow() >= config.maxStopAmendsPerHour()) {
            return respond(ctx, null, toState, "STOP_AMEND_RATE_LIMITED", evidence);
        }
        evidence.put("newStop", target);
        return new ExitDecision(ExitAction.TIGHTEN_STOP, stageReason, target,
                ctx.currentState(), toState, evidence.build());
    }

    /**
     * Break-even after round-trip fees, estimated slippage and one tick.
     * Funding is intentionally excluded: it is a function of holding time, not
     * of the entry/exit round trip, and it is accounted for in realised P&amp;L
     * through the bill ledger.
     */
    public static BigDecimal effectiveBreakEvenPrice(BigDecimal entryPrice,
                                                     PositionExcursion.TradeSide side,
                                                     BigDecimal tickSize,
                                                     ProfitProtectionConfig config) {
        BigDecimal costRate = percent(config.roundTripFeePct().add(config.estimatedSlippagePct()));
        BigDecimal buffer = tickSize == null || tickSize.signum() <= 0 ? BigDecimal.ZERO : tickSize;
        BigDecimal price = side == PositionExcursion.TradeSide.SHORT
                ? entryPrice.multiply(ONE.subtract(costRate)).subtract(buffer)
                : entryPrice.multiply(ONE.add(costRate)).add(buffer);
        return price.signum() <= 0 ? entryPrice : price;
    }

    /** Profit stage derived from the trade's own favourable excursion in R. */
    public static PositionExitState stageFor(BigDecimal mfeR, ProfitProtectionConfig config) {
        BigDecimal value = mfeR == null ? BigDecimal.ZERO : mfeR;
        if (value.compareTo(config.breakEvenActivationR()) < 0) {
            return PositionExitState.INITIAL_PROTECTION;
        }
        if (value.compareTo(config.profitLockActivationR()) < 0) {
            return PositionExitState.BREAK_EVEN_ARMED;
        }
        if (value.compareTo(config.trailingActivationR()) < 0) {
            return PositionExitState.PROFIT_LOCKED;
        }
        return PositionExitState.TRAILING;
    }

    private static BigDecimal maxGiveback(BigDecimal mfeR, ProfitProtectionConfig config) {
        BigDecimal proportional = mfeR.multiply(percent(config.givebackRatioPct()));
        BigDecimal capped = proportional.min(config.maxGivebackR());
        return capped.max(BigDecimal.ZERO);
    }

    private static BigDecimal percent(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Directional tick rounding for a stop: LONG rounds down, SHORT rounds up,
     * i.e. always away from entry so rounding can only widen protection.
     */
    private static BigDecimal normalizeStop(BigDecimal price, PositionExcursion.TradeSide side,
                                            BigDecimal tickSize) {
        if (price == null || tickSize == null || tickSize.signum() <= 0) {
            return price;
        }
        RoundingMode mode = side == PositionExcursion.TradeSide.SHORT ? RoundingMode.CEILING : RoundingMode.FLOOR;
        BigDecimal rounded = price.divide(tickSize, 0, mode).multiply(tickSize).stripTrailingZeros();
        return rounded.signum() <= 0 ? price : rounded;
    }

    private static ExitDecision respond(ExitContext ctx, BigDecimal target, PositionExitState toState,
                                        String reason) {
        return respond(ctx, target, toState, reason, null);
    }

    private static ExitDecision respond(ExitContext ctx, BigDecimal target, PositionExitState toState,
                                        String reason, ExitDecision.Evidence supplied) {
        ExitDecision.Evidence evidence = supplied == null ? baseEvidence(ctx, null, null, reason) : supplied;
        PositionExitState from = ctx.currentState();
        boolean stateAdvanced = from != null && toState != null && toState != from;
        if (stateAdvanced) {
            // The trade reached a new profit stage even though the stop does not
            // need to move yet; persist the stage without touching the exchange.
            return ExitDecision.hold(reason, from, toState, evidence.build());
        }
        return ExitDecision.noOp(reason, from == null ? PositionExitState.NEW : from, evidence.build());
    }

    private static ExitDecision.Evidence baseEvidence(ExitContext ctx, BigDecimal mfeR,
                                                      BigDecimal givebackR, String stageReason) {
        PositionExcursion excursion = ctx.excursion();
        ExitDecision.Evidence evidence = ExitDecision.evidenceOf();
        evidence.put("entry", ctx.entryPrice());
        evidence.put("initialStopLoss", ctx.initialStopLoss());
        evidence.put("initialRisk", ctx.initialRisk());
        evidence.put("markPx", ctx.currentPrice());
        evidence.put("currentR", ctx.currentR());
        evidence.put("oldStop", ctx.appliedProtectiveStop());
        evidence.put("stage", stageReason);
        if (excursion != null) {
            evidence.put("mfePrice", excursion.mfePrice());
            evidence.put("maePrice", excursion.maePrice());
            evidence.put("mfeR", excursion.mfeROrZero());
            evidence.put("maeR", excursion.maeROrZero());
            evidence.put("mfePct", excursion.mfePct());
            evidence.put("maePct", excursion.maePct());
        }
        if (mfeR != null) {
            evidence.put("evaluatedMfeR", mfeR);
        }
        if (givebackR != null) {
            evidence.put("allowedGivebackR", givebackR);
        }
        return evidence;
    }
}

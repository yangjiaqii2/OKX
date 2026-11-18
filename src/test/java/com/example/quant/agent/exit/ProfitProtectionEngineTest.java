package com.example.quant.agent.exit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProfitProtectionEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final BigDecimal TICK = new BigDecimal("0.01");

    @Test
    void keepsTheInitialStopUntilBreakEvenActivationIsReached() {
        ExitContext context = longContext(new BigDecimal("1.025"), new BigDecimal("0.95"),
                PositionExitState.INITIAL_PROTECTION);

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isEqualTo(ExitAction.NO_OP);
        assertThat(decision.reason()).isEqualTo("PROTECTIVE_STOP_ALREADY_AT_TARGET");
        assertThat(decision.toState()).isEqualTo(PositionExitState.INITIAL_PROTECTION);
    }

    @Test
    void armsBreakEvenWithFeesAndTickIncludedNotBareEntryPrice() {
        ExitContext context = longContext(new BigDecimal("1.05"), new BigDecimal("0.95"),
                PositionExitState.INITIAL_PROTECTION);

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isEqualTo(ExitAction.TIGHTEN_STOP);
        assertThat(decision.reason()).isEqualTo("BREAK_EVEN_ARMED");
        assertThat(decision.toState()).isEqualTo(PositionExitState.BREAK_EVEN_ARMED);
        // entry 1.00 * (1 + 0.17%) = 1.0017, + one tick, floored to tick -> 1.01
        assertThat(decision.targetProtectiveStop()).isEqualByComparingTo("1.01");
        assertThat(decision.targetProtectiveStop()).isGreaterThan(new BigDecimal("1.00"));
    }

    @Test
    void locksFifteenRWhenTheTradeOnceReachedEighteenR() {
        ExitContext context = longContext(new BigDecimal("1.90"), new BigDecimal("0.95"),
                PositionExitState.INITIAL_PROTECTION);

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isEqualTo(ExitAction.TIGHTEN_STOP);
        assertThat(decision.reason()).isEqualTo("TRAILING_PROFIT_LOCK");
        assertThat(decision.toState()).isEqualTo(PositionExitState.TRAILING);
        // mfeR = 18, giveback capped at 3R -> stop at 15R -> 1.00 + 15 * 0.05
        assertThat(decision.targetProtectiveStop()).isEqualByComparingTo("1.75");
        assertThat(decision.evidence()).containsEntry("mfeR", "18");
    }

    @Test
    void trailingActivationFactsRemainExplicitForConfiguredTargets() {
        ProfitProtectionConfig config = ProfitProtectionConfig.defaults();

        assertThat(ProfitProtectionEngine.stageFor(new BigDecimal("1.8"), config))
                .isEqualTo(PositionExitState.PROFIT_LOCKED);
        assertThat(ProfitProtectionEngine.stageFor(new BigDecimal("2.2"), config))
                .isEqualTo(PositionExitState.PROFIT_LOCKED);
        assertThat(ProfitProtectionEngine.stageFor(new BigDecimal("3.0"), config))
                .isEqualTo(PositionExitState.TRAILING);
        assertThat(ProfitProtectionEngine.stageFor(new BigDecimal("3.1"), config))
                .isEqualTo(PositionExitState.TRAILING);
    }

    @Test
    void givebackIsProportionalBelowTheCap() {
        // mfeR = 2 (price 1.10) -> giveback 35% = 0.7R -> stop at 1.3R
        ExitContext context = longContext(new BigDecimal("1.10"), new BigDecimal("0.95"),
                PositionExitState.INITIAL_PROTECTION);

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        // 1.065 floored to the tick: rounding a stop must only widen it.
        assertThat(decision.targetProtectiveStop()).isEqualByComparingTo("1.06");
    }

    @Test
    void shortSideMirrorsTheLongSide() {
        ExitContext context = shortContext(new BigDecimal("0.10"), new BigDecimal("1.05"),
                PositionExitState.INITIAL_PROTECTION);

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isEqualTo(ExitAction.TIGHTEN_STOP);
        assertThat(decision.toState()).isEqualTo(PositionExitState.TRAILING);
        // entry 1.00 - 15 * 0.05
        assertThat(decision.targetProtectiveStop()).isEqualByComparingTo("0.25");
    }

    @Test
    void neverMovesTheStopBelowWhatIsAlreadyLocked() {
        // Peak already reached 18R and the stop is at 1.75; a later observation
        // must not be able to pull it back down.
        ExitContext context = new ExitContext(
                TradePlanType.OPEN_LONG, new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("1.20"),
                excursion(new BigDecimal("1.90")),
                new BigDecimal("1.75"), PositionExitState.TRAILING,
                null, 0, TICK, NOW, ProfitProtectionConfig.defaults());

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isEqualTo(ExitAction.NO_OP);
        assertThat(decision.reason()).isEqualTo("PROFIT_PROTECTION_INVARIANT_VIOLATION");
        assertThat(decision.targetProtectiveStop()).isNull();
    }

    @Test
    void refusesToPlaceAStopTooCloseToTheMarket() {
        // Break-even would sit above the market here, so the buffer must pull
        // the stop back instead of creating an instantly-triggering order.
        ExitContext context = new ExitContext(
                TradePlanType.OPEN_LONG, new BigDecimal("1.00"), new BigDecimal("0.99"),
                new BigDecimal("1.005"),
                PositionExcursion.seed(PositionExcursion.TradeSide.LONG, new BigDecimal("1.00"),
                        new BigDecimal("0.01"), new BigDecimal("1.01"), NOW),
                new BigDecimal("0.99"), PositionExitState.INITIAL_PROTECTION,
                null, 0, TICK, NOW, ProfitProtectionConfig.defaults());

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isEqualTo(ExitAction.TIGHTEN_STOP);
        assertThat(decision.targetProtectiveStop())
                .isLessThanOrEqualTo(new BigDecimal("1.005").multiply(new BigDecimal("0.999")));
        assertThat(decision.targetProtectiveStop()).isGreaterThan(new BigDecimal("0.99"));
    }

    @Test
    void debouncesTinyImprovements() {
        ExitContext context = new ExitContext(
                TradePlanType.OPEN_LONG, new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("1.90"),
                excursion(new BigDecimal("1.90001")),
                new BigDecimal("1.7499"), PositionExitState.TRAILING,
                null, 0, TICK, NOW, ProfitProtectionConfig.defaults());

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isEqualTo(ExitAction.NO_OP);
        assertThat(decision.reason()).isEqualTo("STOP_IMPROVEMENT_BELOW_THRESHOLD");
    }

    @Test
    void respectsTheMinimumAmendInterval() {
        ExitContext context = new ExitContext(
                TradePlanType.OPEN_LONG, new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("1.90"),
                excursion(new BigDecimal("1.90")),
                new BigDecimal("1.00"), PositionExitState.TRAILING,
                NOW.minusSeconds(10), 0, TICK, NOW, ProfitProtectionConfig.defaults());

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isEqualTo(ExitAction.NO_OP);
        assertThat(decision.reason()).isEqualTo("STOP_AMEND_INTERVAL_NOT_ELAPSED");
    }

    @Test
    void rateLimitsAmendsWithinTheRollingHour() {
        ExitContext context = new ExitContext(
                TradePlanType.OPEN_LONG, new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("1.90"),
                excursion(new BigDecimal("1.90")),
                new BigDecimal("1.00"), PositionExitState.TRAILING,
                NOW.minusSeconds(3600), 20, TICK, NOW, ProfitProtectionConfig.defaults());

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isEqualTo(ExitAction.NO_OP);
        assertThat(decision.reason()).isEqualTo("STOP_AMEND_RATE_LIMITED");
    }

    @Test
    void promotesTheStateEvenWhenTheStopDoesNotNeedToMove() {
        ExitContext context = new ExitContext(
                TradePlanType.OPEN_LONG, new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("1.80"),
                excursion(new BigDecimal("1.90")),
                new BigDecimal("1.75"), PositionExitState.PROFIT_LOCKED,
                null, 0, TICK, NOW, ProfitProtectionConfig.defaults());

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isEqualTo(ExitAction.HOLD);
        assertThat(decision.fromState()).isEqualTo(PositionExitState.PROFIT_LOCKED);
        assertThat(decision.toState()).isEqualTo(PositionExitState.TRAILING);
    }

    @Test
    void disabledConfigNeverTouchesTheExchange() {
        ExitContext context = new ExitContext(
                TradePlanType.OPEN_LONG, new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("1.90"),
                excursion(new BigDecimal("1.90")),
                new BigDecimal("0.95"), PositionExitState.INITIAL_PROTECTION,
                null, 0, TICK, NOW,
                new ProfitProtectionConfig(false, new BigDecimal("0.75"), new BigDecimal("1.5"),
                        new BigDecimal("3.0"), new BigDecimal("35"), new BigDecimal("3.0"),
                        new BigDecimal("0.12"), new BigDecimal("0.05"), new BigDecimal("0.10"),
                        5, 60L, 20, false));

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isEqualTo(ExitAction.NO_OP);
        assertThat(decision.reason()).isEqualTo("PROFIT_PROTECTION_DISABLED");
    }

    @Test
    void doesNotInventProtectionWhenNoExchangeStopIsConfirmed() {
        ExitContext context = new ExitContext(
                TradePlanType.OPEN_LONG, new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("1.90"),
                excursion(new BigDecimal("1.90")),
                null, PositionExitState.INITIAL_PROTECTION,
                null, 0, TICK, NOW, ProfitProtectionConfig.defaults());

        ExitDecision decision = ProfitProtectionEngine.evaluate(context);

        assertThat(decision.action()).isNotEqualTo(ExitAction.TIGHTEN_STOP);
        assertThat(decision.reason()).isEqualTo("NO_APPLIED_PROTECTIVE_STOP");
        assertThat(decision.targetProtectiveStop()).isNull();
    }

    private static ExitContext longContext(BigDecimal markPrice, BigDecimal applied, PositionExitState state) {
        return new ExitContext(
                TradePlanType.OPEN_LONG, new BigDecimal("1.00"), new BigDecimal("0.95"), markPrice,
                excursion(markPrice), applied, state, null, 0, TICK, NOW,
                ProfitProtectionConfig.defaults());
    }

    private static ExitContext shortContext(BigDecimal markPrice, BigDecimal applied, PositionExitState state) {
        return new ExitContext(
                TradePlanType.OPEN_SHORT, new BigDecimal("1.00"), new BigDecimal("1.05"), markPrice,
                PositionExcursion.seed(PositionExcursion.TradeSide.SHORT, new BigDecimal("1.00"),
                        new BigDecimal("0.05"), markPrice, NOW),
                applied, state, null, 0, TICK, NOW, ProfitProtectionConfig.defaults());
    }

    private static PositionExcursion excursion(BigDecimal price) {
        return PositionExcursion.seed(PositionExcursion.TradeSide.LONG, new BigDecimal("1.00"),
                new BigDecimal("0.05"), price, NOW);
    }
}

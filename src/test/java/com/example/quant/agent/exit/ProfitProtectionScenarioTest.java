package com.example.quant.agent.exit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Price-path replays against the pure engine. No Spring, no exchange: these are
 * the same code paths the live lifecycle uses, driven by a synthetic tape.
 */
class ProfitProtectionScenarioTest {

    private static final Instant T0 = Instant.parse("2026-09-17T00:00:00Z");
    private static final BigDecimal TICK = new BigDecimal("0.01");

    @Test
    void protectsProfitAfterARiseAndThenAPullback() {
        Replay replay = Replay.longTrade(new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("0.95"));

        replay.step("1.00");
        replay.step("1.10");
        replay.step("1.30");
        replay.step("1.50");
        ExitDecision afterPullback = replay.step("1.20");

        assertThat(replay.state()).isEqualTo(PositionExitState.TRAILING);
        assertThat(replay.applied()).isEqualByComparingTo("1.35");
        assertThat(replay.applied()).isGreaterThan(new BigDecimal("0.95"));
        assertThat(afterPullback.action()).isEqualTo(ExitAction.NO_OP);
    }

    @Test
    void protectiveStopOnlyEverMovesTowardProfit() {
        Replay replay = Replay.longTrade(new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("0.95"));
        List<BigDecimal> stops = new ArrayList<>();

        for (int i = 0; i <= 18; i++) {
            replay.step(new BigDecimal("1.00").add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(i)))
                    .toPlainString());
            stops.add(replay.applied());
        }

        for (int i = 1; i < stops.size(); i++) {
            assertThat(stops.get(i)).as("stop must never regress at step %d", i)
                    .isGreaterThanOrEqualTo(stops.get(i - 1));
        }
        assertThat(stops.get(stops.size() - 1)).isEqualByComparingTo("1.75");
    }

    @Test
    void aChoppyHighPlateauDoesNotOscillateTheStateOrResendAmends() {
        Replay replay = Replay.longTrade(new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("0.95"));
        replay.step("1.90");
        int amendsAfterPeak = replay.amends();
        PositionExitState stateAfterPeak = replay.state();

        replay.step("1.88");
        replay.step("1.90");
        replay.step("1.86");
        replay.step("1.89");
        replay.step("1.87");

        assertThat(replay.state()).isEqualTo(stateAfterPeak);
        assertThat(replay.amends()).isEqualTo(amendsAfterPeak);
        assertThat(replay.applied()).isEqualByComparingTo("1.75");
    }

    @Test
    void aHugeFavourableExcursionNeverFallsBackToTheOriginalStop() {
        Replay replay = Replay.longTrade(new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("0.95"));

        replay.step("1.10");
        replay.step("1.30");
        replay.step("1.50");
        replay.step("1.90");
        replay.step("1.40");
        replay.step("1.10");
        replay.step("0.95");

        assertThat(replay.excursion().mfeR()).isEqualByComparingTo("18");
        assertThat(replay.applied()).isEqualByComparingTo("1.75");
        assertThat(replay.applied()).isGreaterThan(new BigDecimal("0.95"));
        assertThat(replay.state()).isEqualTo(PositionExitState.TRAILING);
    }

    @Test
    void shortSideBehavesAsTheMirrorImage() {
        Replay replay = Replay.shortTrade(new BigDecimal("1.00"), new BigDecimal("1.05"),
                new BigDecimal("1.05"));

        replay.step("0.90");
        replay.step("0.70");
        replay.step("0.50");
        replay.step("0.10");
        replay.step("0.60");
        replay.step("1.10");

        assertThat(replay.excursion().mfeR()).isEqualByComparingTo("18");
        assertThat(replay.applied()).isEqualByComparingTo("0.25");
        assertThat(replay.applied()).isLessThan(new BigDecimal("1.05"));
        assertThat(replay.state()).isEqualTo(PositionExitState.TRAILING);
    }

    @Test
    void shortStopsOnlyEverMoveDown() {
        Replay replay = Replay.shortTrade(new BigDecimal("1.00"), new BigDecimal("1.05"),
                new BigDecimal("1.05"));
        List<BigDecimal> stops = new ArrayList<>();

        for (int i = 0; i <= 18; i++) {
            replay.step(new BigDecimal("1.00").subtract(new BigDecimal("0.05").multiply(BigDecimal.valueOf(i)))
                    .toPlainString());
            stops.add(replay.applied());
        }

        for (int i = 1; i < stops.size(); i++) {
            assertThat(stops.get(i)).as("short stop must never regress at step %d", i)
                    .isLessThanOrEqualTo(stops.get(i - 1));
        }
    }

    @Test
    void aSingleContractPositionStillGetsDeterministicProtection() {
        // The engine is quantity agnostic; minimum size is enforced by the
        // canonical compiler and the exposure guard. What matters here is that a
        // tiny position still yields a valid, expressible stop and never throws.
        Replay replay = Replay.longTrade(new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("0.95"));

        ExitDecision decision = replay.step("1.90");

        assertThat(decision.targetProtectiveStop()).isEqualByComparingTo("1.75");
        assertThat(decision.targetProtectiveStop().remainder(TICK)).isEqualByComparingTo("0");
        assertThat(decision.targetProtectiveStop().signum()).isPositive();
    }

    @Test
    void breakEvenAlreadyCoversFeesSoItIsNotCountedAsProfitByAccident() {
        Replay replay = Replay.longTrade(new BigDecimal("1.00"), new BigDecimal("0.95"),
                new BigDecimal("0.95"));

        replay.step("1.0375");

        assertThat(replay.applied()).isGreaterThan(new BigDecimal("1.00"));
        assertThat(replay.state()).isEqualTo(PositionExitState.BREAK_EVEN_ARMED);
    }

    /** Minimal deterministic tape driver for one synthetic position. */
    private static final class Replay {
        private final TradePlanType action;
        private final BigDecimal entry;
        private final BigDecimal initialStop;
        private final BigDecimal risk;
        private final PositionExcursion.TradeSide side;
        private PositionExcursion excursion;
        private BigDecimal applied;
        private PositionExitState state = PositionExitState.NEW;
        private Instant lastChange;
        private int amends;
        private Instant clock = T0;

        private Replay(TradePlanType action, BigDecimal entry, BigDecimal initialStop, BigDecimal applied) {
            this.action = action;
            this.entry = entry;
            this.initialStop = initialStop;
            this.applied = applied;
            this.risk = entry.subtract(initialStop).abs();
            this.side = PositionExcursion.TradeSide.from(action);
            this.excursion = null;
        }

        static Replay longTrade(BigDecimal entry, BigDecimal initialStop, BigDecimal applied) {
            return new Replay(TradePlanType.OPEN_LONG, entry, initialStop, applied);
        }

        static Replay shortTrade(BigDecimal entry, BigDecimal initialStop, BigDecimal applied) {
            return new Replay(TradePlanType.OPEN_SHORT, entry, initialStop, applied);
        }

        ExitDecision step(String price) {
            BigDecimal value = new BigDecimal(price);
            clock = clock.plusSeconds(61);
            excursion = excursion == null
                    ? PositionExcursion.seed(side, entry, risk, value, clock)
                    : excursion.observe(side, entry, risk, value, clock);

            ExitContext context = new ExitContext(action, entry, initialStop, value, excursion,
                    applied, state, lastChange, amends, TICK, clock, ProfitProtectionConfig.defaults());
            ExitDecision decision = ProfitProtectionEngine.evaluate(context);
            if (decision.tightens()) {
                applied = decision.targetProtectiveStop();
                lastChange = clock;
                amends++;
            }
            if (decision.toState() != null && decision.toState() != state) {
                state = decision.toState();
            }
            return decision;
        }

        BigDecimal applied() {
            return applied;
        }

        PositionExcursion excursion() {
            return excursion;
        }

        PositionExitState state() {
            return state;
        }

        int amends() {
            return amends;
        }
    }
}

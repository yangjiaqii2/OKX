package com.example.quant.agent.exit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PositionExitStateTest {

    @Test
    void movesForwardThroughTheProfitStages() {
        assertThat(PositionExitState.NEW.canTransitionTo(PositionExitState.INITIAL_PROTECTION)).isTrue();
        assertThat(PositionExitState.INITIAL_PROTECTION.canTransitionTo(PositionExitState.BREAK_EVEN_ARMED)).isTrue();
        assertThat(PositionExitState.BREAK_EVEN_ARMED.canTransitionTo(PositionExitState.PROFIT_LOCKED)).isTrue();
        assertThat(PositionExitState.PROFIT_LOCKED.canTransitionTo(PositionExitState.TRAILING)).isTrue();
    }

    @Test
    void neverDemotesOnAPullback() {
        assertThat(PositionExitState.TRAILING.canTransitionTo(PositionExitState.PROFIT_LOCKED)).isFalse();
        assertThat(PositionExitState.PROFIT_LOCKED.canTransitionTo(PositionExitState.BREAK_EVEN_ARMED)).isFalse();
        assertThat(PositionExitState.BREAK_EVEN_ARMED.canTransitionTo(PositionExitState.INITIAL_PROTECTION)).isFalse();
        assertThat(PositionExitState.TRAILING.canTransitionTo(PositionExitState.NEW)).isFalse();
    }

    @Test
    void terminalStatesAreReachableFromAnywhere() {
        for (PositionExitState state : PositionExitState.values()) {
            if (state.isExitingOrClosed()) {
                continue;
            }
            assertThat(state.canTransitionTo(PositionExitState.EXITING)).isTrue();
            assertThat(state.canTransitionTo(PositionExitState.CLOSED)).isTrue();
        }
    }

    @Test
    void selfTransitionIsNotAChange() {
        assertThat(PositionExitState.TRAILING.canTransitionTo(PositionExitState.TRAILING)).isFalse();
        assertThat(PositionExitState.TRAILING.canTransitionTo(null)).isFalse();
    }

    @Test
    void closedIsTerminal() {
        assertThat(PositionExitState.CLOSED.isTerminal()).isTrue();
        assertThat(PositionExitState.CLOSED.isExitingOrClosed()).isTrue();
        assertThat(PositionExitState.EXITING.isExitingOrClosed()).isTrue();
        assertThat(PositionExitState.TRAILING.isTerminal()).isFalse();
    }

    @Test
    void highestPreservesTheMostAdvancedStageAfterRecovery() {
        assertThat(PositionExitState.INITIAL_PROTECTION.highest(PositionExitState.TRAILING))
                .isEqualTo(PositionExitState.TRAILING);
        assertThat(PositionExitState.TRAILING.highest(PositionExitState.PROFIT_LOCKED))
                .isEqualTo(PositionExitState.TRAILING);
        assertThat(PositionExitState.TRAILING.highest(null)).isEqualTo(PositionExitState.TRAILING);
    }
}

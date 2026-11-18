package com.example.quant.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyEvaluationServiceTest {
    @Test
    void noDataReturnsNaInsteadOfSyntheticZeros() {
        ShadowSelectionRepository shadows = mock(ShadowSelectionRepository.class);
        CounterfactualExitRepository exits = mock(CounterfactualExitRepository.class);
        when(shadows.findByUserNameOrderByCreatedAtAsc("local-admin")).thenReturn(List.of());
        when(exits.findByUserNameOrderByUpdatedAtAsc("local-admin")).thenReturn(List.of());

        StrategyEvaluationService.Summary summary =
                new StrategyEvaluationService(shadows, exits).summary("RULE_FIRST_AI_RANK", "S1_V2");

        assertThat(summary.scanCount()).isZero();
        assertThat(summary.coverage()).isNull();
        assertThat(summary.tradeCount()).isZero();
        assertThat(summary.gross()).isNull();
        assertThat(summary.fee()).isNull();
        assertThat(summary.net()).isNull();
        assertThat(summary.expectancy()).isNull();
        assertThat(summary.dataQuality()).isEqualTo("N/A");
    }
}

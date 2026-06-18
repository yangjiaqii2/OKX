package com.example.quant.crypto.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractScoreBreakdownTest {

    @Test
    void usesConfiguredBusinessWeightsForWeightedTotal() {
        ContractScoreBreakdown score = new ContractScoreBreakdown(
                100,
                80,
                60,
                40,
                20,
                0,
                0
        );

        assertThat(score.weightedTotal()).isEqualTo(62);
        assertThat(score.weightedFactorScore()).isEqualTo(new ContractFactorScore(23, 18, 4, 15, 2, 0, 0));
    }
}

package com.example.quant.crypto.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractScoreBreakdownTest {

    @Test
    void usesConfiguredBusinessWeightsForWeightedTotal() {
        ContractScoreBreakdown score = new ContractScoreBreakdown(
                100,
                0,
                60,
                40,
                20,
                0,
                0
        );

        assertThat(score.weightedTotal()).isEqualTo(46);
        assertThat(score.weightedFactorScore()).isEqualTo(new ContractFactorScore(25, 0, 4, 15, 2, 0, 0));
    }

    @Test
    void newsWeightedFactorIsBoundedToFiveEvenAtFullRawSafetyScore() {
        ContractScoreBreakdown score = new ContractScoreBreakdown(100, 100, 100, 100, 100, 100, 100);

        assertThat(score.weightedFactorScore().volumeScore()).isEqualTo(20);
        assertThat(score.weightedFactorScore().newsRiskScore()).isEqualTo(5);
    }
}

package com.example.quant.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesPositionQualityTest {

    @Test
    void defaultsBothScoreThresholdsTo60RegardlessOfOpenPositionCount() {
        AgentProperties.PositionQuality positionQuality = new AgentProperties.PositionQuality();

        assertThat(positionQuality.minScoreForOpenPositions(0)).isEqualTo(60);
        assertThat(positionQuality.minScoreForOpenPositions(1)).isEqualTo(60);
        assertThat(positionQuality.minScoreForOpenPositions(2)).isEqualTo(60);
        assertThat(positionQuality.minFinalRankScoreForOpenPositions(0)).isEqualTo(60);
        assertThat(positionQuality.minFinalRankScoreForOpenPositions(1)).isEqualTo(60);
        assertThat(positionQuality.minFinalRankScoreForOpenPositions(2)).isEqualTo(60);
    }
}

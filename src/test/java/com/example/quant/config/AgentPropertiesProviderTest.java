package com.example.quant.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentPropertiesProviderTest {

    @Test
    void marketCapFreshTtlDefaultsToTenMinutes() {
        AgentProperties properties = new AgentProperties();

        assertThat(properties.selection().providers().marketCapFreshTtlSeconds()).isEqualTo(600);
    }

    @Test
    void minimumOrderBookDepthDefaultsToFiveThousandUsdt() {
        AgentProperties properties = new AgentProperties();

        assertThat(properties.market().minDepthUsdt()).isEqualByComparingTo("5000");
    }
}

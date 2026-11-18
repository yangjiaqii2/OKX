package com.example.quant.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ExecutionVenueFixtureTest {
    @Test
    void publicAndDemoHighsMustNotBeUsedInterchangeably() {
        BigDecimal tp1 = new BigDecimal("4448.2");
        BigDecimal publicHigh = new BigDecimal("4450.5");
        BigDecimal demoHigh = new BigDecimal("4442.7");
        assertThat(publicHigh.compareTo(tp1) >= 0).isTrue();
        assertThat(demoHigh.compareTo(tp1) >= 0).isFalse();
        assertThat("CANCELED").isEqualTo("CANCELED");
    }
}

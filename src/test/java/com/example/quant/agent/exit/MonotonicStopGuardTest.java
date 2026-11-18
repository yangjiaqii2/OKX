package com.example.quant.agent.exit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.exit.PositionExcursion.TradeSide;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MonotonicStopGuardTest {

    @Test
    void longStopMayOnlyMoveUp() {
        BigDecimal current = new BigDecimal("1.20");

        assertThat(MonotonicStopGuard.improves(new BigDecimal("1.35"), current, TradeSide.LONG)).isTrue();
        assertThat(MonotonicStopGuard.improves(new BigDecimal("1.20"), current, TradeSide.LONG)).isTrue();
        assertThat(MonotonicStopGuard.improves(new BigDecimal("1.19"), current, TradeSide.LONG)).isFalse();
    }

    @Test
    void shortStopMayOnlyMoveDown() {
        BigDecimal current = new BigDecimal("1.20");

        assertThat(MonotonicStopGuard.improves(new BigDecimal("1.05"), current, TradeSide.SHORT)).isTrue();
        assertThat(MonotonicStopGuard.improves(new BigDecimal("1.20"), current, TradeSide.SHORT)).isTrue();
        assertThat(MonotonicStopGuard.improves(new BigDecimal("1.21"), current, TradeSide.SHORT)).isFalse();
    }

    @Test
    void firstStopIsAlwaysAnImprovement() {
        assertThat(MonotonicStopGuard.improves(new BigDecimal("1.35"), null, TradeSide.LONG)).isTrue();
        assertThat(MonotonicStopGuard.improves(new BigDecimal("1.35"), BigDecimal.ZERO, TradeSide.LONG)).isTrue();
    }

    @Test
    void invalidCandidatesAreRejected() {
        assertThat(MonotonicStopGuard.improves(null, new BigDecimal("1.20"), TradeSide.LONG)).isFalse();
        assertThat(MonotonicStopGuard.improves(BigDecimal.ZERO, new BigDecimal("1.20"), TradeSide.LONG)).isFalse();
    }

    @Test
    void regressionDetectionIsSymmetric() {
        assertThat(MonotonicStopGuard.isRegression(new BigDecimal("1.19"), new BigDecimal("1.20"), TradeSide.LONG))
                .isTrue();
        assertThat(MonotonicStopGuard.isRegression(new BigDecimal("1.21"), new BigDecimal("1.20"), TradeSide.SHORT))
                .isTrue();
        assertThat(MonotonicStopGuard.isRegression(new BigDecimal("1.25"), new BigDecimal("1.20"), TradeSide.LONG))
                .isFalse();
    }

    @Test
    void regressionIsNotReportedWhenEitherSideIsMissing() {
        assertThat(MonotonicStopGuard.isRegression(null, new BigDecimal("1.20"), TradeSide.LONG)).isFalse();
        assertThat(MonotonicStopGuard.isRegression(new BigDecimal("1.19"), null, TradeSide.LONG)).isFalse();
    }

    @Test
    void improvementIsMeasuredInBasisPoints() {
        assertThat(MonotonicStopGuard.improvementBps(new BigDecimal("1.10"), new BigDecimal("1.00")))
                .isEqualTo(1000L);
        assertThat(MonotonicStopGuard.improvementBps(new BigDecimal("1.0005"), new BigDecimal("1.00")))
                .isEqualTo(5L);
    }

    @Test
    void improvementWithoutAReferenceIsUnbounded() {
        assertThat(MonotonicStopGuard.improvementBps(new BigDecimal("1.10"), null))
                .isEqualTo(Long.MAX_VALUE);
    }
}

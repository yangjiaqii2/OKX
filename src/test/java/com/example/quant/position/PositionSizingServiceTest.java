package com.example.quant.position;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PositionSizingServiceTest {

    @Test
    void shrinksPositionWhenMarginExceedsConfiguredLimit() {
        PositionSizingService service = new PositionSizingService();

        PositionSizingResult result = service.calculate(new PositionSizingRequest(
                BigDecimal.valueOf(10000),
                BigDecimal.valueOf(9000),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(99),
                BigDecimal.valueOf(102),
                2,
                BigDecimal.valueOf(0.01),
                BigDecimal.valueOf(0.05),
                BigDecimal.valueOf(1.5),
                BigDecimal.valueOf(0.02),
                RiskLevel.MEDIUM
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.suggestedMargin()).isLessThanOrEqualTo(BigDecimal.valueOf(500));
        assertThat(result.warningList()).contains("保证金超过单笔上限，已自动缩小仓位");
    }

    @Test
    void rejectsWhenRiskRewardIsTooLow() {
        PositionSizingService service = new PositionSizingService();

        PositionSizingResult result = service.calculate(new PositionSizingRequest(
                BigDecimal.valueOf(10000),
                BigDecimal.valueOf(9000),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(99),
                BigDecimal.valueOf(100.5),
                2,
                BigDecimal.valueOf(0.01),
                BigDecimal.valueOf(0.05),
                BigDecimal.valueOf(1.5),
                BigDecimal.valueOf(0.02),
                RiskLevel.MEDIUM
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).contains("盈亏比");
    }
}

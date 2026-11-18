package com.example.quant.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.config.TradingProperties;
import com.example.quant.system.SystemControlService;
import org.junit.jupiter.api.Test;

class RiskControllerTest {

    @Test
    void disabledTradingIsReportedAsBlockedInsteadOfUsingSyntheticSafeDefaults() {
        TradingProperties trading = new TradingProperties("SEMI_AUTO", true, false, 120, false);
        RiskController controller = new RiskController(new SystemControlService(trading), trading);

        RiskController.RiskRuntimeStatus status = controller.status();

        assertThat(status.passed()).isFalse();
        assertThat(status.riskLevel()).isEqualTo("BLOCKED");
        assertThat(status.rejectCode()).isEqualTo("TRADING_DISABLED");
    }

    @Test
    void liveModeWithoutAnOrderIsExplicitlyNotEvaluated() {
        TradingProperties trading = new TradingProperties("SEMI_AUTO", true, true, 120, false);
        RiskController controller = new RiskController(new SystemControlService(trading), trading);

        RiskController.RiskRuntimeStatus status = controller.status();

        assertThat(status.passed()).isNull();
        assertThat(status.riskLevel()).isEqualTo("NOT_EVALUATED");
        assertThat(status.rejectCode()).isEqualTo("ORDER_CONTEXT_REQUIRED");
    }
}

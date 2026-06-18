package com.example.quant.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SystemControlServiceTest {

    @Test
    void defaultsAutoTradeRiskModeToStrict() {
        SystemControlService service = new SystemControlService(tradingProperties());

        SystemControlService.SystemStatus status = service.enableAutoTrade(BigDecimal.valueOf(50));

        assertThat(status.autoTradeRiskMode()).isEqualTo(AutoTradeRiskMode.STRICT);
    }

    @Test
    void enablesNoRiskModeWhenUserSelectsIt() {
        SystemControlService service = new SystemControlService(tradingProperties());

        SystemControlService.SystemStatus status = service.enableAutoTrade(BigDecimal.valueOf(50), AutoTradeRiskMode.NO_RISK);

        assertThat(status.autoTradeRiskMode()).isEqualTo(AutoTradeRiskMode.NO_RISK);
        assertThat(service.autoTradeRiskMode()).isEqualTo(AutoTradeRiskMode.NO_RISK);
    }

    @Test
    void storesUserSelectedNoRiskMinimumScore() {
        SystemControlService service = new SystemControlService(tradingProperties());

        SystemControlService.SystemStatus status = service.enableAutoTrade(
                BigDecimal.valueOf(50),
                AutoTradeRiskMode.NO_RISK,
                60
        );

        assertThat(status.noRiskMinScore()).isEqualTo(60);
        assertThat(service.noRiskMinScore()).isEqualTo(60);
    }

    @Test
    void storesUserSelectedAutoTradeMinimumLeverage() {
        SystemControlService service = new SystemControlService(tradingProperties());

        SystemControlService.SystemStatus status = service.enableAutoTrade(
                BigDecimal.valueOf(50),
                AutoTradeRiskMode.NO_RISK,
                69,
                5
        );

        assertThat(status.autoTradeMinLeverage()).isEqualTo(5);
        assertThat(service.autoTradeMinLeverage()).isEqualTo(5);
    }

    @Test
    void remembersUserWhoEnabledAutoTrade() {
        SystemControlService service = new SystemControlService(tradingProperties());

        SystemControlService.SystemStatus status = AuthUserContext.callAs("alice", () ->
                service.enableAutoTrade(BigDecimal.valueOf(50), AutoTradeRiskMode.NO_RISK, 65, 3));

        assertThat(status.autoTradeOwnerUsername()).isEqualTo("alice");
        assertThat(service.autoTradeOwnerUsername()).isEqualTo("alice");
    }

    @Test
    void rejectsNullRiskModeWhenExplicitlyProvided() {
        SystemControlService service = new SystemControlService(tradingProperties());

        assertThatThrownBy(() -> service.enableAutoTrade(BigDecimal.valueOf(50), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自动交易风险模式不能为空");
    }

    private static TradingProperties tradingProperties() {
        return new TradingProperties("SEMI_AUTO", true, true, 120, false);
    }
}

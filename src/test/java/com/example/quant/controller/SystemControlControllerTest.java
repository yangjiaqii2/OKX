package com.example.quant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.OkxAccountVerificationResult;
import com.example.quant.config.SystemFxRateProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.system.AutoTradeRiskMode;
import com.example.quant.system.FxRateResponse;
import com.example.quant.system.FxRateService;
import com.example.quant.system.SystemControlService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SystemControlControllerTest {

    @Test
    void enableAutoTradeRejectsWhenOkxAccountIsUnboundOrInvalid() {
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        AccountSnapshotService accountSnapshotService = new FixedAccountSnapshotService(new OkxAccountVerificationResult(
                false,
                "OKX_UNBOUND",
                "尚未绑定OKX API。",
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));
        SystemControlController controller = new SystemControlController(systemControlService, accountSnapshotService);

        assertThatThrownBy(() -> controller.enableAutoTrade(BigDecimal.TEN, AutoTradeRiskMode.STRICT, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未绑定OKX API");
        assertThat(systemControlService.status().autoTradeEnabled()).isFalse();
    }

    @Test
    void enableAutoTradeSucceedsOnlyAfterOkxAccountVerificationPasses() {
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        AccountSnapshotService accountSnapshotService = new FixedAccountSnapshotService(new OkxAccountVerificationResult(
                true,
                "OKX_REAL",
                "OKX接口验证通过",
                new BigDecimal("100"),
                new BigDecimal("80")
        ));
        SystemControlController controller = new SystemControlController(systemControlService, accountSnapshotService);

        Object status = controller.enableAutoTrade(BigDecimal.TEN, AutoTradeRiskMode.NO_RISK, 65, 2);

        assertThat(status).isInstanceOf(SystemControlService.SystemStatus.class);
        assertThat(((SystemControlService.SystemStatus) status).autoTradeEnabled()).isTrue();
    }

    @Test
    void returnsConfiguredUsdCnyFxRate() {
        SystemFxRateProperties properties = new SystemFxRateProperties();
        properties.setRate(new BigDecimal("7.31"));
        properties.setSource("TEST_CONFIG");
        FxRateService fxRateService = new FxRateService(
                properties,
                Clock.fixed(Instant.parse("2026-06-19T08:00:00Z"), ZoneOffset.UTC)
        );
        SystemControlController controller = new SystemControlController(
                new SystemControlService(tradingProperties()),
                new FixedAccountSnapshotService(null),
                fxRateService
        );

        FxRateResponse response = (FxRateResponse) controller.fxRate("USD", "CNY");

        assertThat(response.base()).isEqualTo("USD");
        assertThat(response.quote()).isEqualTo("CNY");
        assertThat(response.rate()).isEqualByComparingTo("7.31");
        assertThat(response.source()).isEqualTo("TEST_CONFIG");
        assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-06-19T08:00:00Z"));
    }

    private static TradingProperties tradingProperties() {
        return new TradingProperties("SEMI_AUTO", true, true, 120, false);
    }

    private static class FixedAccountSnapshotService extends AccountSnapshotService {
        private final OkxAccountVerificationResult verificationResult;

        FixedAccountSnapshotService(OkxAccountVerificationResult verificationResult) {
            super(null);
            this.verificationResult = verificationResult;
        }

        @Override
        public OkxAccountVerificationResult verifyOkx() {
            return verificationResult;
        }
    }
}

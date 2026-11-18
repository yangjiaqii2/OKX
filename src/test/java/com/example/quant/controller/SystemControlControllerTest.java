package com.example.quant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.OkxAccountVerificationResult;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.SystemFxRateProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.OkxWebSocketService;
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
    void runtimeStatusDoesNotClaimAutoEnabledWhenConfigGuardIsDisabled() {
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.TEN);
        SystemControlController controller = new SystemControlController(
                systemControlService,
                new FixedAccountSnapshotService(new OkxAccountVerificationResult(
                        true, "OKX_REAL", "ok", BigDecimal.TEN, BigDecimal.TEN)),
                new FxRateService(null),
                tradingProperties(),
                new OkxWebSocketService()
        );
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.autoTrade().setEnabled(false);
        controller.configureAgentProperties(agentProperties);

        SystemControlController.RuntimeStatus status = controller.runtimeStatus();

        assertThat(status.strategyEngine()).isEqualTo("AUTO_DISABLED_BY_CONFIG");
        assertThat(status.tradingEngine()).isEqualTo("MANUAL_CONFIRM");
        assertThat(status.tradingReason()).isEqualTo("AUTO_TRADE_DISABLED_BY_CONFIG");
    }

    @Test
    void runtimeStatusLabelsNoRiskModeAsShadowInsteadOfExecutableAutoTrade() {
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.TEN, AutoTradeRiskMode.NO_RISK);
        SystemControlController controller = new SystemControlController(
                systemControlService,
                new FixedAccountSnapshotService(new OkxAccountVerificationResult(
                        true, "OKX_REAL", "ok", BigDecimal.TEN, BigDecimal.TEN)),
                new FxRateService(null),
                tradingProperties(),
                new OkxWebSocketService()
        );

        SystemControlController.RuntimeStatus status = controller.runtimeStatus();

        assertThat(status.strategyEngine()).isEqualTo("SHADOW_RUNNING");
        assertThat(status.tradingEngine()).isEqualTo("SHADOW_ONLY");
        assertThat(status.tradingReason()).isEqualTo("NO_RISK_MODE_DOES_NOT_SUBMIT_ORDERS");
    }

    @Test
    void runtimeStatusNeverClaimsUnimplementedWebSocketIsConnected() {
        SystemControlController controller = new SystemControlController(
                new SystemControlService(tradingProperties()),
                new FixedAccountSnapshotService(new OkxAccountVerificationResult(
                        true, "OKX_REAL", "ok", BigDecimal.TEN, BigDecimal.TEN)),
                new FxRateService(null),
                tradingProperties(),
                new OkxWebSocketService()
        );

        SystemControlController.RuntimeStatus status = controller.runtimeStatus();

        assertThat(status.tradingMode()).isEqualTo("LIVE");
        assertThat(status.okxRest()).isEqualTo("CONNECTED");
        assertThat(status.publicWebSocket()).isEqualTo("NOT_IMPLEMENTED");
        assertThat(status.privateWebSocket()).isEqualTo("DISCONNECTED");
        assertThat(status.privateWebSocketReason()).isEqualTo("NOT_CONNECTED");
        assertThat(status.tradingReason()).isEqualTo("READINESS_NOT_CONFIGURED");
        assertThat(status.strategyEngine()).isEqualTo("MONITORING_ONLY");
        assertThat(status.tradingEngine()).isEqualTo("MANUAL_CONFIRM");
    }

    @Test
    void disabledModeReportsDisabledAsTheTradingReason() {
        TradingProperties disabled = new TradingProperties("DISABLED", true, false, 120, false);
        SystemControlController controller = new SystemControlController(
                new SystemControlService(disabled),
                new FixedAccountSnapshotService(null),
                new FxRateService(null),
                disabled,
                new OkxWebSocketService()
        );

        SystemControlController.RuntimeStatus status = controller.runtimeStatus();

        assertThat(status.tradingEngine()).isEqualTo("DISABLED");
        assertThat(status.tradingReason()).isEqualTo("TRADING_MODE_DISABLED");
    }

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

        @Override
        public AccountSummary summary() {
            if (verificationResult == null) {
                return new AccountSummary(BigDecimal.ZERO, BigDecimal.ZERO, "OKX_UNBOUND", "unbound");
            }
            return new AccountSummary(verificationResult.equity(), verificationResult.availableBalance(),
                    verificationResult.mode(), verificationResult.message());
        }
    }
}

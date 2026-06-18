package com.example.quant.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingProperties;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemControlServiceTest {

    @Test
    void persistsAutoTradeRuntimeStateAndRestoresItAfterRestart() {
        SystemControlRepository repository = mock(SystemControlRepository.class);
        when(repository.findById("GLOBAL")).thenReturn(Optional.empty());
        when(repository.save(any(SystemControlEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SystemControlService service = new SystemControlService(tradingProperties(), repository);

        SystemControlService.SystemStatus enabled = AuthUserContext.callAs("alice", () ->
                service.enableAutoTrade(BigDecimal.valueOf(50), AutoTradeRiskMode.NO_RISK, 65, 3));

        ArgumentCaptor<SystemControlEntity> captor = ArgumentCaptor.forClass(SystemControlEntity.class);
        verify(repository).save(captor.capture());
        SystemControlEntity saved = captor.getValue();
        assertThat(enabled.autoTradeEnabled()).isTrue();
        assertThat(saved.isAutoTradeEnabled()).isTrue();
        assertThat(saved.getAutoTradeOwnerUsername()).isEqualTo("alice");
        assertThat(saved.getAutoTradeRiskMode()).isEqualTo("NO_RISK");
        assertThat(saved.getAutoTradeMarginUsdt()).isEqualByComparingTo("50");
        assertThat(saved.getNoRiskMinScore()).isEqualTo(65);
        assertThat(saved.getAutoTradeMinLeverage()).isEqualTo(3);

        SystemControlRepository restartedRepository = mock(SystemControlRepository.class);
        when(restartedRepository.findById("GLOBAL")).thenReturn(Optional.of(saved));
        SystemControlService restarted = new SystemControlService(tradingProperties(), restartedRepository);

        SystemControlService.SystemStatus restored = restarted.status();
        assertThat(restored.autoTradeEnabled()).isTrue();
        assertThat(restored.autoTradeOwnerUsername()).isEqualTo("alice");
        assertThat(restored.autoTradeRiskMode()).isEqualTo(AutoTradeRiskMode.NO_RISK);
        assertThat(restored.autoTradeMarginUsdt()).isEqualByComparingTo("50");
        assertThat(restored.noRiskMinScore()).isEqualTo(65);
        assertThat(restored.autoTradeMinLeverage()).isEqualTo(3);
    }

    @Test
    void persistsDisableAndEmergencyStopState() {
        SystemControlRepository repository = mock(SystemControlRepository.class);
        when(repository.findById("GLOBAL")).thenReturn(Optional.empty());
        when(repository.save(any(SystemControlEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SystemControlService service = new SystemControlService(tradingProperties(), repository);

        service.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.STRICT);
        service.disableAutoTrade();
        service.emergencyStop();

        ArgumentCaptor<SystemControlEntity> captor = ArgumentCaptor.forClass(SystemControlEntity.class);
        verify(repository, org.mockito.Mockito.atLeast(3)).save(captor.capture());
        SystemControlEntity saved = captor.getValue();
        assertThat(saved.isEmergencyStop()).isTrue();
        assertThat(saved.isAutoTradeEnabled()).isFalse();
    }

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

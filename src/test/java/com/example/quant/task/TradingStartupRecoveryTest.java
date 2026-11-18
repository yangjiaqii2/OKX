package com.example.quant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.system.TradingEngineState;
import com.example.quant.system.TradingReadinessService;
import com.example.quant.system.OkxRuntimeReconciliationService;
import com.example.quant.system.SystemControlService;
import com.example.quant.account.OkxAccountBoundEvent;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.Test;

class TradingStartupRecoveryTest {
    @Test
    void blocksTradingWhenExchangeReconciliationDidNotComplete() {
        AutoTradeRecoveryTask task = mock(AutoTradeRecoveryTask.class);
        when(task.runOnce()).thenReturn(new AutoTradeRecoveryTask.RecoveryResult(0, 0, 0, 0, false));
        TradingReadinessService readiness = new TradingReadinessService();

        new TradingStartupRecovery(task, readiness).recover();

        assertThat(readiness.snapshot().state()).isEqualTo(TradingEngineState.BLOCKED);
        assertThat(readiness.ready()).isFalse();
    }

    @Test
    void blocksV34StartupWhenLegacyActiveLifecycleHasNoCanonicalPlan() {
        AutoTradeRecoveryTask task = mock(AutoTradeRecoveryTask.class);
        when(task.legacyActiveLifecycleCount()).thenReturn(1);
        TradingReadinessService readiness = new TradingReadinessService();

        new TradingStartupRecovery(task, readiness).recover();

        assertThat(readiness.snapshot().state()).isEqualTo(TradingEngineState.BLOCKED);
        assertThat(readiness.snapshot().reason())
                .isEqualTo("LEGACY_ACTIVE_LIFECYCLE_REQUIRES_FLAT_V34_BOUNDARY");
        org.mockito.Mockito.verify(task, org.mockito.Mockito.never()).runOnce();
    }

    @Test
    void becomesReadyOnlyAfterCleanExchangeRecovery() {
        AutoTradeRecoveryTask task = mock(AutoTradeRecoveryTask.class);
        when(task.runOnce()).thenReturn(new AutoTradeRecoveryTask.RecoveryResult(0, 0, 0, 1, true));
        TradingReadinessService readiness = new TradingReadinessService();

        new TradingStartupRecovery(task, readiness).recover();

        assertThat(readiness.snapshot().state()).isEqualTo(TradingEngineState.READY);
    }

    @Test
    void bindingAfterStartupRunsRecoveryForBoundOwner() {
        AutoTradeRecoveryTask task = mock(AutoTradeRecoveryTask.class);
        when(task.runOnceForOwner("alice"))
                .thenReturn(new AutoTradeRecoveryTask.RecoveryResult(0, 0, 0, 0, true));
        OkxRuntimeReconciliationService exchange = mock(OkxRuntimeReconciliationService.class);
        when(exchange.reconcile("alice"))
                .thenReturn(new OkxRuntimeReconciliationService.Result(true, "EXCHANGE_RECONCILED", 0));
        when(exchange.startupSafety("alice"))
                .thenReturn(new OkxRuntimeReconciliationService.SafetyReport(true, true, true, true, true,
                        "EXCHANGE_FLAT"));
        TradingReadinessService readiness = new TradingReadinessService();
        SystemControlService control = mock(SystemControlService.class);
        TradingStartupRecovery recovery = new TradingStartupRecovery(task, readiness, exchange, control);

        recovery.recoverAfterBinding(new OkxAccountBoundEvent("alice"));

        verify(task).runOnceForOwner("alice");
        verify(exchange).reconcile("alice");
        verify(control).selectTradingOwner("alice");
        assertThat(readiness.snapshot().state()).isEqualTo(TradingEngineState.READY);
    }

    @Test
    void demoRecoveryWaitsForPrivateWebSocketBeforeTradingIsReady() {
        AutoTradeRecoveryTask task = mock(AutoTradeRecoveryTask.class);
        when(task.runOnceForOwner("alice"))
                .thenReturn(new AutoTradeRecoveryTask.RecoveryResult(0, 0, 0, 0, true));
        OkxRuntimeReconciliationService exchange = mock(OkxRuntimeReconciliationService.class);
        when(exchange.reconcile("alice"))
                .thenReturn(new OkxRuntimeReconciliationService.Result(true, "EXCHANGE_RECONCILED", 0));
        when(exchange.startupSafety("alice"))
                .thenReturn(new OkxRuntimeReconciliationService.SafetyReport(true, true, true, true, true,
                        "EXCHANGE_FLAT"));
        TradingReadinessService readiness = new TradingReadinessService();
        TradingProperties trading = new TradingProperties(TradingMode.DEMO, true, 120, false);
        TradingStartupRecovery recovery = new TradingStartupRecovery(task, readiness, exchange, null, trading);

        recovery.recoverAfterBinding(new OkxAccountBoundEvent("alice"));

        assertThat(readiness.snapshot().state()).isEqualTo(TradingEngineState.DEGRADED);
        assertThat(readiness.snapshot().reason()).isEqualTo("PRIVATE_WS_RECONCILIATION_PENDING");
    }

    @Test
    void blocksStartupWhenExchangeHasOrphanPositionEvenIfDbRecoveryIsClean() {
        AutoTradeRecoveryTask task = mock(AutoTradeRecoveryTask.class);
        when(task.runOnce()).thenReturn(new AutoTradeRecoveryTask.RecoveryResult(0, 0, 0, 0, true));
        OkxRuntimeReconciliationService exchange = mock(OkxRuntimeReconciliationService.class);
        when(exchange.reconcile("local-admin"))
                .thenReturn(new OkxRuntimeReconciliationService.Result(true, "EXCHANGE_RECONCILED", 0));
        when(exchange.startupSafety("local-admin"))
                .thenReturn(new OkxRuntimeReconciliationService.SafetyReport(
                        false, true, true, false, false,
                        "EXCHANGE_ORPHAN_POSITION"));
        TradingReadinessService readiness = new TradingReadinessService();

        new TradingStartupRecovery(task, readiness, exchange, null, null).recover();

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.snapshot().reason()).isEqualTo("EXCHANGE_ORPHAN_POSITION");
    }

    @Test
    void blocksV34StartupWhenDatabaseStillHasUnresolvedLifecycle() {
        AutoTradeRecoveryTask task = mock(AutoTradeRecoveryTask.class);
        when(task.unresolvedPendingOrderCount()).thenReturn(1);
        TradingReadinessService readiness = new TradingReadinessService();

        new TradingStartupRecovery(task, readiness).recover();

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.snapshot().reason())
                .isEqualTo("V34_DEPLOYMENT_REQUIRES_NO_UNRESOLVED_LIFECYCLE");
        org.mockito.Mockito.verify(task, org.mockito.Mockito.never()).runOnce();
    }
}

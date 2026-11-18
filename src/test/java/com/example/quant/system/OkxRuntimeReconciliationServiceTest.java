package com.example.quant.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.account.PositionReconciliationService;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.okxtrade.OkxCurrentOrderSyncService;
import com.example.quant.okxtrade.OkxCurrentOrderService;
import com.example.quant.okxtrade.OkxCurrentOrderView;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.ledger.AccountBillLedgerService;
import java.util.List;
import org.junit.jupiter.api.Test;

class OkxRuntimeReconciliationServiceTest {
    @Test
    void blocksWhenExchangeContainsUnmanagedPosition() {
        OkxCurrentOrderSyncService orders = mock(OkxCurrentOrderSyncService.class);
        PositionReconciliationService positions = mock(PositionReconciliationService.class);
        when(orders.syncOnce()).thenReturn(new OkxCurrentOrderSyncService.SyncResult(0, 0, 0, false, null, List.of()));
        when(positions.reconcile()).thenReturn(new PositionReconciliationService.Result(1, 1, false, null));
        OkxRuntimeReconciliationService service = new OkxRuntimeReconciliationService(orders, positions);

        OkxRuntimeReconciliationService.Result result = service.reconcile("alice");

        assertThat(result.ready()).isFalse();
        assertThat(result.reason()).isEqualTo("UNMANAGED_POSITIONS:1");
        assertThat(AuthUserContext.currentUsername()).isEmpty();
    }

    @Test
    void becomesReadyOnlyWhenOrdersAndPositionsAreReconciled() {
        OkxCurrentOrderSyncService orders = mock(OkxCurrentOrderSyncService.class);
        PositionReconciliationService positions = mock(PositionReconciliationService.class);
        when(orders.syncOnce()).thenReturn(new OkxCurrentOrderSyncService.SyncResult(1, 1, 2, false, null, List.of()));
        when(positions.reconcile()).thenReturn(new PositionReconciliationService.Result(1, 0, false, null));
        OkxRuntimeReconciliationService service = new OkxRuntimeReconciliationService(orders, positions);

        OkxRuntimeReconciliationService.Result result = service.reconcile("alice");

        assertThat(result.ready()).isTrue();
        assertThat(result.reason()).isEqualTo("EXCHANGE_RECONCILED");
    }

    @Test
    void blocksWhenRecentBillsCannotBeSynchronized() {
        OkxCurrentOrderSyncService orders = mock(OkxCurrentOrderSyncService.class);
        PositionReconciliationService positions = mock(PositionReconciliationService.class);
        AccountBillLedgerService bills = mock(AccountBillLedgerService.class);
        when(orders.syncOnce()).thenReturn(new OkxCurrentOrderSyncService.SyncResult(0, 0, 0, false, null, List.of()));
        when(positions.reconcile()).thenReturn(new PositionReconciliationService.Result(0, 0, false, null));
        when(bills.syncRecent()).thenReturn(new AccountBillLedgerService.SyncResult("FAILED", 0, "timeout"));
        OkxRuntimeReconciliationService service = new OkxRuntimeReconciliationService(orders, positions);
        service.configureBillLedger(bills);

        OkxRuntimeReconciliationService.Result result = service.reconcile("alice");

        assertThat(result.ready()).isFalse();
        assertThat(result.reason()).isEqualTo("LEDGER_RECONCILIATION_FAILED");
    }

    @Test
    void recognizesAutoNamespaceAsSystemOwnedAtStartup() {
        OkxCurrentOrderSyncService orders = mock(OkxCurrentOrderSyncService.class);
        PositionReconciliationService positions = mock(PositionReconciliationService.class);
        OkxCurrentOrderService currentOrders = mock(OkxCurrentOrderService.class);
        PositionSnapshotService snapshots = mock(PositionSnapshotService.class);
        when(currentOrders.currentOrders()).thenReturn(List.of());
        when(currentOrders.currentAlgoOrders()).thenReturn(List.of(new OkxCurrentOrderView(
                "BTC-USDT-SWAP", null, "algo-1", null, "AUTOplanorderSL",
                "STOP_LOSS", "sell", "long", "conditional", true,
                new java.math.BigDecimal("1"), java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("90"), "live", null, null)));
        when(snapshots.positions()).thenReturn(List.of());

        OkxRuntimeReconciliationService service = new OkxRuntimeReconciliationService(
                orders, positions, currentOrders, snapshots);

        OkxRuntimeReconciliationService.SafetyReport report = service.startupSafety("alice");

        assertThat(report.reason()).isEqualTo("EXCHANGE_SYSTEM_OWNED_ALGO_ACTIVE");
        assertThat(report.legacyCanonicalSafe()).isFalse();
    }
}

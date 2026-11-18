package com.example.quant.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PositionReconciliationServiceTest {
    @Test
    void ignoresHistoricalLifecycleResidualsWhenMatchingCurrentManagedPosition() {
        PositionSnapshotService snapshots = mock(PositionSnapshotService.class);
        when(snapshots.positions()).thenReturn(List.of(position("1")));
        TradeFillEntity currentFill = fill("buy", "long", "1", true);
        currentFill.setPendingOrderId("078abc78-25e9-45b8-9470-0f4bd99b755f");
        TradeFillEntity historicalResidual = fill("buy", "long", "717", true);
        historicalResidual.setPendingOrderId("ce4e94e5-b095-499e-9c2d-e353c21bb40f");
        TradeFillRepository fills = mock(TradeFillRepository.class);
        when(fills.findByUserName("alice")).thenReturn(List.of(historicalResidual, currentFill));
        PendingOrder activeOrder = mock(PendingOrder.class);
        when(activeOrder.id()).thenReturn(UUID.fromString("078abc78-25e9-45b8-9470-0f4bd99b755f"));
        PendingOrderService pendingOrders = mock(PendingOrderService.class);
        when(pendingOrders.allOrders()).thenReturn(List.of(activeOrder));
        ExchangePositionRepository positions = mock(ExchangePositionRepository.class);
        when(positions.findByUserNameAndInstIdAndPosSide("alice", "BTC-USDT-SWAP", "long"))
                .thenReturn(Optional.empty());
        when(positions.findByUserName("alice")).thenReturn(List.of());
        when(positions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PositionReconciliationService service = new PositionReconciliationService(
                snapshots, fills, positions, pendingOrders);

        PositionReconciliationService.Result result = AuthUserContext.callAs("alice", service::reconcile);

        assertThat(result.unmanagedPositions()).isZero();
        ArgumentCaptor<ExchangePositionEntity> saved = ArgumentCaptor.forClass(ExchangePositionEntity.class);
        org.mockito.Mockito.verify(positions).save(saved.capture());
        assertThat(saved.getValue().getOwnership()).isEqualTo("MANAGED");
        assertThat(saved.getValue().getReason()).isEqualTo("ACTIVE_LOCAL_FILL_CHAIN_MATCHED");
    }

    @Test
    void marksPositionManagedOnlyWhenExchangeSizeMatchesTraceableLocalFills() {
        PositionSnapshotService snapshots = mock(PositionSnapshotService.class);
        when(snapshots.positions()).thenReturn(List.of(position("2")));
        TradeFillRepository fills = mock(TradeFillRepository.class);
        when(fills.findByUserName("alice")).thenReturn(List.of(fill("buy", "long", "2", true)));
        ExchangePositionRepository positions = mock(ExchangePositionRepository.class);
        when(positions.findByUserNameAndInstIdAndPosSide("alice", "BTC-USDT-SWAP", "long"))
                .thenReturn(Optional.empty());
        when(positions.findByUserName("alice")).thenReturn(List.of());
        when(positions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PositionReconciliationService service = new PositionReconciliationService(snapshots, fills, positions);

        PositionReconciliationService.Result result = AuthUserContext.callAs("alice", service::reconcile);

        assertThat(result.failed()).isFalse();
        assertThat(result.unmanagedPositions()).isZero();
        ArgumentCaptor<ExchangePositionEntity> saved = ArgumentCaptor.forClass(ExchangePositionEntity.class);
        org.mockito.Mockito.verify(positions).save(saved.capture());
        assertThat(saved.getValue().getOwnership()).isEqualTo("MANAGED");
        assertThat(saved.getValue().getReason()).isEqualTo("LOCAL_FILL_CHAIN_MATCHED");
    }

    @Test
    void persistsExternalOrMismatchedPositionAsUnmanaged() {
        PositionSnapshotService snapshots = mock(PositionSnapshotService.class);
        when(snapshots.positions()).thenReturn(List.of(position("3")));
        TradeFillRepository fills = mock(TradeFillRepository.class);
        when(fills.findByUserName("alice")).thenReturn(List.of(fill("buy", "long", "2", true)));
        ExchangePositionRepository positions = mock(ExchangePositionRepository.class);
        when(positions.findByUserNameAndInstIdAndPosSide("alice", "BTC-USDT-SWAP", "long"))
                .thenReturn(Optional.empty());
        when(positions.findByUserName("alice")).thenReturn(List.of());
        when(positions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PositionReconciliationService service = new PositionReconciliationService(snapshots, fills, positions);

        PositionReconciliationService.Result result = AuthUserContext.callAs("alice", service::reconcile);

        assertThat(result.unmanagedPositions()).isEqualTo(1);
        ArgumentCaptor<ExchangePositionEntity> saved = ArgumentCaptor.forClass(ExchangePositionEntity.class);
        org.mockito.Mockito.verify(positions).save(saved.capture());
        assertThat(saved.getValue().getOwnership()).isEqualTo("UNMANAGED");
        assertThat(saved.getValue().getReason()).isEqualTo("EXCHANGE_LOCAL_SIZE_MISMATCH");
    }

    @Test
    void fillWithoutStrategyOrPendingOrderDoesNotProveOwnership() {
        PositionSnapshotService snapshots = mock(PositionSnapshotService.class);
        when(snapshots.positions()).thenReturn(List.of(position("2")));
        TradeFillRepository fills = mock(TradeFillRepository.class);
        when(fills.findByUserName("alice")).thenReturn(List.of(fill("buy", "long", "2", false)));
        ExchangePositionRepository positions = mock(ExchangePositionRepository.class);
        when(positions.findByUserNameAndInstIdAndPosSide("alice", "BTC-USDT-SWAP", "long"))
                .thenReturn(Optional.empty());
        when(positions.findByUserName("alice")).thenReturn(List.of());
        when(positions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PositionReconciliationService service = new PositionReconciliationService(snapshots, fills, positions);

        PositionReconciliationService.Result result = AuthUserContext.callAs("alice", service::reconcile);

        assertThat(result.unmanagedPositions()).isEqualTo(1);
    }

    private static PositionSummary position(String size) {
        return new PositionSummary("BTC-USDT-SWAP", "long", size, new BigDecimal("100"),
                BigDecimal.ZERO, "OKX_REAL");
    }

    private static TradeFillEntity fill(String side, String posSide, String size, boolean traceable) {
        TradeFillEntity fill = new TradeFillEntity();
        fill.setUserName("alice");
        fill.setInstId("BTC-USDT-SWAP");
        fill.setSide(side);
        fill.setPosSide(posSide);
        fill.setFillSize(new BigDecimal(size));
        if (traceable) {
            fill.setPendingOrderId("pending-1");
        }
        return fill;
    }
}

package com.example.quant.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxCurrentOrderSyncService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PositionCapacityScanGateTest {

    @Test
    void fullExchangePositionCapacitySkipsExpensiveCandidateScan() {
        AgentProperties properties = properties(2);
        PositionSnapshotService positions = mock(PositionSnapshotService.class);
        when(positions.positions()).thenReturn(List.of(
                position("BTC-USDT-SWAP"),
                position("ETH-USDT-SWAP")));
        OkxCurrentOrderSyncService orders = mock(OkxCurrentOrderSyncService.class);
        when(orders.syncOnce()).thenReturn(new OkxCurrentOrderSyncService.SyncResult(
                0, 0, 0, false, null, List.of()));
        TradeOrderRecordService records = mock(TradeOrderRecordService.class);
        when(records.activeEntryInstIds()).thenReturn(Set.of());

        PositionCapacityScanGate gate = new PositionCapacityScanGate(properties, positions, orders, records);

        PositionCapacityScanGate.Decision decision = gate.evaluate();

        assertThat(decision.skipScan()).isTrue();
        assertThat(decision.reason()).isEqualTo("POSITION_CAPACITY_FULL");
        assertThat(decision.openOrPendingCount()).isEqualTo(2);
        assertThat(decision.maxOpenPositions()).isEqualTo(2);
    }

    @Test
    void closedPositionFreesCapacityAndResumesCandidateScan() {
        AgentProperties properties = properties(2);
        PositionSnapshotService positions = mock(PositionSnapshotService.class);
        when(positions.positions()).thenReturn(List.of(position("BTC-USDT-SWAP")));
        OkxCurrentOrderSyncService orders = mock(OkxCurrentOrderSyncService.class);
        when(orders.syncOnce()).thenReturn(new OkxCurrentOrderSyncService.SyncResult(
                0, 0, 0, false, null, List.of()));
        TradeOrderRecordService records = mock(TradeOrderRecordService.class);
        when(records.activeEntryInstIds()).thenReturn(Set.of());

        PositionCapacityScanGate.Decision decision = new PositionCapacityScanGate(
                properties, positions, orders, records).evaluate();

        assertThat(decision.skipScan()).isFalse();
        assertThat(decision.reason()).isEqualTo("POSITION_CAPACITY_AVAILABLE");
        assertThat(decision.openOrPendingCount()).isEqualTo(1);
    }

    @Test
    void unavailableExchangeStateDoesNotPretendCapacityIsFull() {
        AgentProperties properties = properties(2);
        PositionSnapshotService positions = mock(PositionSnapshotService.class);
        when(positions.positions()).thenThrow(new IllegalStateException("exchange unavailable"));
        OkxCurrentOrderSyncService orders = mock(OkxCurrentOrderSyncService.class);
        TradeOrderRecordService records = mock(TradeOrderRecordService.class);

        PositionCapacityScanGate.Decision decision = new PositionCapacityScanGate(
                properties, positions, orders, records).evaluate();

        assertThat(decision.skipScan()).isFalse();
        assertThat(decision.reason()).isEqualTo("POSITION_CAPACITY_UNKNOWN");
    }

    private static AgentProperties properties(int maxOpenPositions) {
        AgentProperties properties = new AgentProperties();
        properties.autoTrade().setEnabled(true);
        properties.autoTrade().setMaxOpenPositions(maxOpenPositions);
        return properties;
    }

    private static PositionSummary position(String instId) {
        return new PositionSummary(instId, "net", "1", BigDecimal.ONE,
                BigDecimal.ZERO, "OKX_REAL");
    }
}

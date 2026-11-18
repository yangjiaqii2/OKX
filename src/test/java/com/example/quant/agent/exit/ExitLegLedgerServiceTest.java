package com.example.quant.agent.exit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExitLegLedgerServiceTest {
    private final ExitLegLedgerRepository ledger = mock(ExitLegLedgerRepository.class);
    private final TradeOrderRepository orders = mock(TradeOrderRepository.class);
    private final TradeFillRepository fills = mock(TradeFillRepository.class);
    private final ExitLegLedgerService service = new ExitLegLedgerService(ledger, orders, fills);

    @Test
    void filledLegIsNotRecreatedAfterRepeatedReconciliation() {
        List<CanonicalExecutablePlan.ExitLeg> plan = plan();
        TradeOrderEntity tp1Order = order("TP1", "tp1-cl", "tp1-algo", "FILLED");
        TradeFillEntity tp1Fill = fill("fill-1", "tp1-algo", "tp1-cl", "3");
        when(ledger.findByUserNameAndPendingOrderIdOrderByExitLegId("u", "p"))
                .thenReturn(List.of());
        when(ledger.findByUserNameAndPendingOrderIdAndExitLegId("u", "p", "TP1"))
                .thenReturn(Optional.empty());
        when(ledger.findByUserNameAndPendingOrderIdAndExitLegId("u", "p", "TP2"))
                .thenReturn(Optional.empty());
        when(ledger.findByUserNameAndPendingOrderIdAndExitLegId("u", "p", "TP3"))
                .thenReturn(Optional.empty());
        when(orders.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc("u", "p"))
                .thenReturn(List.of(tp1Order));
        when(fills.findByUserNameAndPendingOrderId("u", "p"))
                .thenReturn(List.of(tp1Fill));
        when(ledger.save(org.mockito.ArgumentMatchers.any(ExitLegLedgerEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExitLegLedgerService.Reconciliation first = service.reconcile("u", "p", "plan", "BTC-USDT-SWAP",
                plan, new BigDecimal("7"), List.of(
                        new ExitLegLedgerService.ActiveProtection("tp2", "TP2", new BigDecimal("4")),
                        new ExitLegLedgerService.ActiveProtection("tp3", "TP3", new BigDecimal("3"))),
                Instant.parse("2026-09-21T00:00:00Z"));

        assertEquals(BigDecimal.ZERO, first.submitQuantities().getOrDefault("TP1", BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, first.submitQuantities().getOrDefault("TP2", BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, first.submitQuantities().getOrDefault("TP3", BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, first.legs().stream().filter(row -> row.getExitLegId().equals("TP1"))
                .findFirst().orElseThrow().getRemainingQuantity());
    }

    @Test
    void partialFillsUseOriginalOutstandingQuantityInsteadOfRescalingCurrentPosition() {
        List<CanonicalExecutablePlan.ExitLeg> plan = plan();
        ExitLegLedgerEntity tp1 = existing("TP1", "3", "3", "FILLED");
        ExitLegLedgerEntity tp2 = existing("TP2", "4", "1", "PARTIALLY_FILLED");
        ExitLegLedgerEntity tp3 = existing("TP3", "3", "0", "SUBMITTED");
        when(ledger.findByUserNameAndPendingOrderIdOrderByExitLegId("u", "p"))
                .thenReturn(List.of(tp1, tp2, tp3));
        when(orders.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc("u", "p"))
                .thenReturn(List.of());
        when(fills.findByUserNameAndPendingOrderId("u", "p")).thenReturn(List.of());
        when(ledger.save(org.mockito.ArgumentMatchers.any(ExitLegLedgerEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExitLegLedgerService.Reconciliation reconciliation = service.reconcile("u", "p", "plan", "BTC-USDT-SWAP",
                plan, new BigDecimal("6"), List.of(
                        new ExitLegLedgerService.ActiveProtection("tp2", "TP2", new BigDecimal("3")),
                        new ExitLegLedgerService.ActiveProtection("tp3", "TP3", new BigDecimal("3"))),
                Instant.parse("2026-09-21T00:00:00Z"));

        assertEquals(BigDecimal.ZERO, reconciliation.submitQuantities().getOrDefault("TP1", BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, reconciliation.submitQuantities().getOrDefault("TP2", BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, reconciliation.submitQuantities().getOrDefault("TP3", BigDecimal.ZERO));
        assertEquals(0, reconciliation.cancelRoles().size());
    }

    @Test
    void duplicateFillReplayIsCountedOnce() {
        ExitLegLedgerEntity tp1 = existing("TP1", "3", "0", "SUBMITTED");
        when(ledger.findByUserNameAndPendingOrderIdOrderByExitLegId("u", "p"))
                .thenReturn(List.of(tp1));
        when(orders.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc("u", "p"))
                .thenReturn(List.of(order("TP1", "tp1-cl", "tp1-algo", "LIVE")));
        TradeFillEntity fill = fill("same-fill", "tp1-algo", "tp1-cl", "1");
        when(fills.findByUserNameAndPendingOrderId("u", "p")).thenReturn(List.of(fill, fill));
        when(ledger.save(org.mockito.ArgumentMatchers.any(ExitLegLedgerEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExitLegLedgerService.Reconciliation reconciliation = service.reconcile("u", "p", "plan", "BTC-USDT-SWAP",
                plan(), new BigDecimal("9"), List.of(
                        new ExitLegLedgerService.ActiveProtection("tp1", "TP1", new BigDecimal("2"))),
                Instant.parse("2026-09-21T00:00:00Z"));

        assertEquals(BigDecimal.ZERO, reconciliation.submitQuantities().getOrDefault("TP1", BigDecimal.ZERO));
        assertEquals(new BigDecimal("1"), reconciliation.legs().stream()
                .filter(row -> row.getExitLegId().equals("TP1"))
                .findFirst().orElseThrow().getFilledQuantity());
    }

    @Test
    void outstandingProtectionAbovePositionFailsClosed() {
        when(ledger.findByUserNameAndPendingOrderIdOrderByExitLegId("u", "p")).thenReturn(List.of());
        when(ledger.findByUserNameAndPendingOrderIdAndExitLegId("u", "p", "TP1"))
                .thenReturn(Optional.empty());
        when(ledger.findByUserNameAndPendingOrderIdAndExitLegId("u", "p", "TP2"))
                .thenReturn(Optional.empty());
        when(ledger.findByUserNameAndPendingOrderIdAndExitLegId("u", "p", "TP3"))
                .thenReturn(Optional.empty());
        when(orders.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc("u", "p")).thenReturn(List.of());
        when(fills.findByUserNameAndPendingOrderId("u", "p")).thenReturn(List.of());
        when(ledger.save(org.mockito.ArgumentMatchers.any(ExitLegLedgerEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(IllegalStateException.class, () -> service.reconcile("u", "p", "plan", "BTC-USDT-SWAP",
                plan(), new BigDecimal("9"), List.of(), Instant.parse("2026-09-21T00:00:00Z")));
    }

    @Test
    void flatPositionObsoletesAllLegs() {
        ExitLegLedgerEntity tp1 = existing("TP1", "3", "3", "FILLED");
        ExitLegLedgerEntity tp2 = existing("TP2", "4", "0", "SUBMITTED");
        when(ledger.findByUserNameAndPendingOrderIdOrderByExitLegId("u", "p"))
                .thenReturn(List.of(tp1, tp2));
        when(ledger.save(org.mockito.ArgumentMatchers.any(ExitLegLedgerEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.markFlat("u", "p", Instant.parse("2026-09-21T00:00:00Z"));

        assertEquals(ExitLegLedgerStatus.OBSOLETE, tp1.getStatus());
        assertEquals(ExitLegLedgerStatus.OBSOLETE, tp2.getStatus());
    }

    private static List<CanonicalExecutablePlan.ExitLeg> plan() {
        return List.of(
                leg("TP1", "1"), leg("TP2", "2"), leg("TP3", "3"));
    }

    private static CanonicalExecutablePlan.ExitLeg leg(String id, String targetR) {
        return new CanonicalExecutablePlan.ExitLeg(id, new BigDecimal(targetR), new BigDecimal(targetR),
                new BigDecimal(id.equals("TP1") ? "0.3" : id.equals("TP2") ? "0.4" : "0.3"),
                new BigDecimal(id.equals("TP1") ? "3" : id.equals("TP2") ? "4" : "3"),
                new BigDecimal(targetR), "TEST", "NONE");
    }

    private static ExitLegLedgerEntity existing(String id, String planned, String filled, String status) {
        ExitLegLedgerEntity row = new ExitLegLedgerEntity();
        row.setUserName("u");
        row.setPendingOrderId("p");
        row.setExitLegId(id);
        row.setPlannedQuantity(new BigDecimal(planned));
        row.setFilledQuantity(new BigDecimal(filled));
        row.setRemainingQuantity(new BigDecimal(planned).subtract(new BigDecimal(filled)));
        row.setStatus(ExitLegLedgerStatus.valueOf(status));
        return row;
    }

    private static TradeOrderEntity order(String role, String clOrdId, String ordId, String status) {
        TradeOrderEntity row = new TradeOrderEntity();
        row.setOrderRole(role);
        row.setClOrdId(clOrdId);
        row.setOkxOrdId(ordId);
        row.setStatus(status);
        row.setReduceOnly(true);
        return row;
    }

    private static TradeFillEntity fill(String tradeId, String ordId, String clOrdId, String size) {
        TradeFillEntity fill = new TradeFillEntity();
        fill.setTradeId(tradeId);
        fill.setOrdId(ordId);
        fill.setClOrdId(clOrdId);
        fill.setFillSize(new BigDecimal(size));
        return fill;
    }
}

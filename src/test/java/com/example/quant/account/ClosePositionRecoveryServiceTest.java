package com.example.quant.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetAllocationRequest;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.budget.BudgetReservationStatus;
import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.ledger.TradeFillLedgerService;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.okxtrade.OkxCurrentOrderService;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.risk.DailyRiskService;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClosePositionRecoveryServiceTest {

    @Test
    void reconcilesCloseOrderAndFillBeforeMarkingPositionClosed() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markCloseSubmitted("close-placeholder", "MANUAL_CLOSE_SUBMITTED");
        ClosePositionRecordEntity record = closeRecord(order);
        record.setCloseOrderId("BTC-USDT-SWAP:long");
        record.setCloseClOrdId("CLOSE123");
        OkxCurrentOrderService currentOrders = mock(OkxCurrentOrderService.class);
        TradeFillLedgerService fills = mock(TradeFillLedgerService.class);
        DailyRiskService dailyRisk = mock(DailyRiskService.class);
        OkxCurrentOrderService.Fill fill = new OkxCurrentOrderService.Fill(
                "trade-close-1", "sell", "long", new BigDecimal("101"), BigDecimal.ONE,
                new BigDecimal("-0.02"), "USDT", new BigDecimal("-1.00"),
                Instant.parse("2026-06-18T00:09:00Z"));
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED"))
                .thenReturn(List.of(record));
        when(closeRepository.save(any(ClosePositionRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(currentOrders.orderDetail("BTC-USDT-SWAP", "", "CLOSE123"))
                .thenReturn(new OkxCurrentOrderService.OrderDetail(
                        "close-ord-1", "CLOSE123", "filled", BigDecimal.ONE, BigDecimal.ONE,
                        new BigDecimal("101"), Instant.parse("2026-06-18T00:09:00Z")));
        when(currentOrders.fills("BTC-USDT-SWAP", "close-ord-1")).thenReturn(List.of(fill));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository, tradeOrderRepository, pendingOrderService, budgetService,
                new FixedPositionSnapshotService(List.of()));
        service.configureCloseLedger(currentOrders, fills);
        service.configureDailyRisk(dailyRisk);

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(
                Instant.parse("2026-06-18T00:10:00Z"));

        assertThat(result.closed()).isEqualTo(1);
        assertThat(record.getStatus()).isEqualTo("CLOSED");
        assertThat(record.getCloseOrderId()).isEqualTo("close-ord-1");
        assertThat(record.getAvgPx()).isEqualByComparingTo("101");
        assertThat(record.getRealizedPnl()).isEqualByComparingTo("-1.00");
        assertThat(record.getFee()).isEqualByComparingTo("-0.02");
        verify(fills).record(record, "close-ord-1", fill);
        verify(dailyRisk).recordReconciledOutcome(
                "local-admin", order.id().toString(), new BigDecimal("-1.02"),
                Instant.parse("2026-06-18T00:10:00Z"));
    }

    @Test
    void closesPostEntryOrderAndReleasesBudgetWhenPositionDisappears() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of());
        when(closeRepository.findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
                "local-admin", order.id().toString())).thenReturn(List.of());
        when(closeRepository.save(any(ClosePositionRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                pendingOrderService,
                budgetService,
                new FixedPositionSnapshotService(List.of())
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(
                Instant.parse("2026-06-18T00:10:00Z")
        );

        assertThat(result.closed()).isZero();
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED_RECONCILING);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RELEASED);
    }

    @Test
    void closesNaturalPositionOnlyAfterChildFillLedgerIsComplete() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        TradeOrderEntity entry = protection(order.id(), "ENTRY");
        entry.setReduceOnly(false);
        entry.setFilledSize(new BigDecimal("717"));
        TradeOrderEntity child = protection(order.id(), "STOP_LOSS");
        child.setOkxOrdId("child-xau");
        child.setPnlCompleteness("COMPLETE");
        List<TradeFillEntity> fills = List.of(
                fillRow("child-xau", "xau-1", "300", "-10.0000", "-0.650000"),
                fillRow("child-xau", "xau-2", "417", "-13.2472", "-0.921664"));
        when(closeRepository.findByUserNameAndStatus(any(), any())).thenReturn(List.of());
        when(closeRepository.findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
                "local-admin", order.id().toString())).thenReturn(List.of());
        when(closeRepository.save(any(ClosePositionRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.findByPendingOrderIdOrderByCreatedAtAsc(order.id().toString()))
                .thenReturn(List.of(entry, child));
        TradeFillLedgerService ledger = mock(TradeFillLedgerService.class);
        when(ledger.fillsForPendingOrder("local-admin", order.id().toString())).thenReturn(fills);
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository, tradeOrderRepository, pendingOrderService, budgetService,
                new FixedPositionSnapshotService(List.of()));
        service.configureCloseLedger(null, ledger);

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(
                Instant.parse("2026-09-06T02:00:00Z"));

        assertThat(result.closed()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
        ArgumentCaptor<ClosePositionRecordEntity> captor = ArgumentCaptor.forClass(ClosePositionRecordEntity.class);
        verify(closeRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        ClosePositionRecordEntity saved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(saved.getStatus()).isEqualTo("CLOSED");
        assertThat(saved.getPnlCompleteness()).isEqualTo("COMPLETE");
        assertThat(saved.getRealizedPnl()).isEqualByComparingTo("-23.2472");
        assertThat(saved.getFee()).isEqualByComparingTo("-1.571664");
    }

    @Test
    void adoptsUniqueCompleteOrphanManualCloseForQaLifecycle() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, samplePlan());
        order.setExecutableContractQuantity(BigDecimal.ONE);
        order.markSubmitted(Instant.parse("2026-06-18T00:01:00Z"), "entry-qa-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");

        ClosePositionRecordEntity lifecycleRecord = new ClosePositionRecordEntity();
        lifecycleRecord.setUserName("local-admin");
        lifecycleRecord.setInstId(order.instId());
        lifecycleRecord.setPosSide(order.posSide());
        lifecycleRecord.setMarginMode(order.tdMode());
        lifecycleRecord.setPendingOrderId(order.id().toString());
        lifecycleRecord.setStatus("CLOSED_RECONCILING");
        lifecycleRecord.setPnlCompleteness("PARTIAL");
        lifecycleRecord.setSource("POSITION_GONE");
        lifecycleRecord.setCreatedAt(Instant.parse("2026-06-18T00:10:00Z"));
        lifecycleRecord.setUpdatedAt(Instant.parse("2026-06-18T00:10:00Z"));

        ClosePositionRecordEntity manualClose = new ClosePositionRecordEntity();
        manualClose.setUserName("local-admin");
        manualClose.setInstId(order.instId());
        manualClose.setPosSide(order.posSide());
        manualClose.setMarginMode(order.tdMode());
        manualClose.setCloseOrderId("close-qa-1");
        manualClose.setCloseClOrdId("CLOSEQA1");
        manualClose.setSize(BigDecimal.ONE);
        manualClose.setAvgPx(new BigDecimal("101"));
        manualClose.setRealizedPnl(new BigDecimal("-1.00"));
        manualClose.setFee(new BigDecimal("-0.02"));
        manualClose.setStatus("CLOSED");
        manualClose.setPnlCompleteness("COMPLETE");
        manualClose.setSource("MANUAL");
        manualClose.setRequestSent(true);
        manualClose.setCreatedAt(Instant.parse("2026-06-18T00:09:00Z"));
        manualClose.setUpdatedAt(Instant.parse("2026-06-18T00:09:30Z"));

        when(closeRepository.findByUserNameAndStatus(any(), any())).thenReturn(List.of());
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSED"))
                .thenReturn(List.of(manualClose));
        when(closeRepository.findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
                "local-admin", order.id().toString())).thenReturn(List.of(lifecycleRecord));
        when(closeRepository.save(any(ClosePositionRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.findByPendingOrderIdOrderByCreatedAtAsc(order.id().toString()))
                .thenReturn(List.of());
        when(tradeOrderRepository.findByInstIdAndReduceOnlyTrueAndStatusIn(any(), any()))
                .thenReturn(List.of());
        when(tradeOrderRepository.findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(any(), any()))
                .thenReturn(List.of());
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository, tradeOrderRepository, pendingOrderService, budgetService,
                new FixedPositionSnapshotService(List.of()));

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(
                Instant.parse("2026-06-18T00:11:00Z"));

        assertThat(result.closed()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(lifecycleRecord.getStatus()).isEqualTo("CLOSED");
        assertThat(lifecycleRecord.getCloseOrderId()).isEqualTo("close-qa-1");
        assertThat(lifecycleRecord.getRealizedPnl()).isEqualByComparingTo("-1.00");
        assertThat(lifecycleRecord.getFee()).isEqualByComparingTo("-0.02");
        assertThat(lifecycleRecord.getPnlCompleteness()).isEqualTo("COMPLETE");
        assertThat(lifecycleRecord.getRecoveryResult()).isEqualTo("ORPHAN_MANUAL_CLOSE_FACTS_MATCHED");
        assertThat(manualClose.getPendingOrderId()).isNull();
    }

    @Test
    void keepsQaLifecycleReconcilingWhenOrphanManualCloseIsAmbiguous() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, samplePlan());
        order.setExecutableContractQuantity(BigDecimal.ONE);
        order.markSubmitted(Instant.parse("2026-06-18T00:01:00Z"), "entry-qa-1");
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");

        ClosePositionRecordEntity lifecycleRecord = closeRecord(order);
        lifecycleRecord.setStatus("CLOSED_RECONCILING");
        lifecycleRecord.setPnlCompleteness("PARTIAL");
        lifecycleRecord.setSource("POSITION_GONE");
        lifecycleRecord.setCreatedAt(Instant.parse("2026-06-18T00:10:00Z"));
        lifecycleRecord.setUpdatedAt(Instant.parse("2026-06-18T00:10:00Z"));
        ClosePositionRecordEntity first = completeOrphanManualClose(order, "close-qa-1", "CLOSEQA1");
        ClosePositionRecordEntity second = completeOrphanManualClose(order, "close-qa-2", "CLOSEQA2");

        when(closeRepository.findByUserNameAndStatus(any(), any())).thenReturn(List.of());
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSED"))
                .thenReturn(List.of(first, second));
        when(closeRepository.findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
                "local-admin", order.id().toString())).thenReturn(List.of(lifecycleRecord));
        when(closeRepository.save(any(ClosePositionRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.findByPendingOrderIdOrderByCreatedAtAsc(order.id().toString()))
                .thenReturn(List.of());
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository, tradeOrderRepository, pendingOrderService, budgetService,
                new FixedPositionSnapshotService(List.of()));

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(
                Instant.parse("2026-06-18T00:11:00Z"));

        assertThat(result.closed()).isZero();
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED_RECONCILING);
        assertThat(lifecycleRecord.getStatus()).isEqualTo("CLOSED_RECONCILING");
        assertThat(lifecycleRecord.getRecoveryResult()).isNotEqualTo("ORPHAN_MANUAL_CLOSE_FACTS_MATCHED");
    }

    @Test
    void doesNotCloseSubmittedEntryWhenPositionIsAbsent() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-1");
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of());
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                pendingOrderService,
                budgetService,
                new FixedPositionSnapshotService(List.of())
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(
                Instant.parse("2026-06-18T00:10:00Z")
        );

        assertThat(result.closed()).isZero();
        assertThat(order.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
    }

    @Test
    void cancelsOnlyBoundProtectionsAndInvalidatesEntryWhenPositionDisappears() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        TradeOrderEntity entry = protection(order.id(), "ENTRY");
        entry.setReduceOnly(false);
        entry.setStatus("ACTIVE");
        TradeOrderEntity boundProtection = protection(order.id(), "STOP_LOSS");
        boundProtection.setOkxOrdId("algo-bound");
        TradeOrderEntity orphanProtection = protection(UUID.randomUUID(), "TP1");
        orphanProtection.setPendingOrderId(null);
        orphanProtection.setClOrdId("algo-orphan-cl");
        TradeOrderEntity oppositeProtection = protection(UUID.randomUUID(), "STOP_LOSS");
        oppositeProtection.setPendingOrderId(null);
        oppositeProtection.setPosSide("short");
        oppositeProtection.setOkxOrdId("algo-short");
        CapturingOkxGateway gateway = new CapturingOkxGateway();
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of());
        when(closeRepository.findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
                "local-admin", order.id().toString())).thenReturn(List.of());
        when(closeRepository.save(any(ClosePositionRecordEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.findByInstIdAndReduceOnlyTrueAndStatusIn(
                "BTC-USDT-SWAP", List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of(boundProtection, orphanProtection, oppositeProtection));
        when(tradeOrderRepository.findByPendingOrderIdOrderByCreatedAtAsc(order.id().toString()))
                .thenReturn(List.of(entry, boundProtection));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                pendingOrderService,
                budgetService,
                new FixedPositionSnapshotService(List.of()),
                gateway
        );

        service.runOnce(Instant.parse("2026-06-18T00:10:00Z"));

        assertThat(gateway.cancelAlgoPayloads).containsExactly(
                Map.of("instId", "BTC-USDT-SWAP", "algoId", "algo-bound"));
        assertThat(boundProtection.getStatus()).isEqualTo("INVALID");
        assertThat(orphanProtection.getStatus()).isEqualTo("PROTECTION_SUBMITTED");
        assertThat(oppositeProtection.getStatus()).isEqualTo("PROTECTION_SUBMITTED");
        assertThat(entry.getStatus()).isEqualTo("INACTIVE");
        assertThat(entry.getErrorMessage()).isEqualTo("POSITION_CLOSED");
    }

    @Test
    void marksClosedReleasesBudgetAndInvalidatesProtectionOrdersWhenPositionIsGone() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markCloseSubmitted("close-1", "MAX_HOLD_TIMEOUT_CLOSE_SUBMITTED");
        ClosePositionRecordEntity record = closeRecord(order);
        TradeOrderEntity stopLoss = protection(order.id(), "STOP_LOSS");
        TradeOrderEntity tp1 = protection(order.id(), "TP1");
        stopLoss.setOkxOrdId("algo-sl");
        tp1.setClOrdId("algo-tp1-cl");
        CapturingOkxGateway gateway = new CapturingOkxGateway();
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of(record));
        when(closeRepository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(
                order.id().toString(),
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of(stopLoss, tp1));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                pendingOrderService,
                budgetService,
                new FixedPositionSnapshotService(List.of()),
                gateway
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(Instant.parse("2026-06-18T00:10:00Z"));

        assertThat(result.closed()).isEqualTo(1);
        assertThat(record.getStatus()).isEqualTo("CLOSED");
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RELEASED);
        assertThat(stopLoss.getStatus()).isEqualTo("INVALID");
        assertThat(tp1.getStatus()).isEqualTo("INVALID");
        assertThat(gateway.cancelAlgoPayloads)
                .contains(
                        Map.of("instId", "BTC-USDT-SWAP", "algoId", "algo-sl"),
                        Map.of("instId", "BTC-USDT-SWAP", "algoClOrdId", "algo-tp1-cl")
                );
        verify(closeRepository).save(record);
        verify(tradeOrderRepository).saveAll(List.of(stopLoss, tp1));
    }

    @Test
    void positionsQueryFailureDoesNotCloseRecordReleaseBudgetOrInvalidateProtectionOrders() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markCloseSubmitted("close-1", "MAX_HOLD_TIMEOUT_CLOSE_SUBMITTED");
        ClosePositionRecordEntity record = closeRecord(order);
        TradeOrderEntity stopLoss = protection(order.id(), "STOP_LOSS");
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of(record));
        when(closeRepository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(
                order.id().toString(),
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of(stopLoss));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                pendingOrderService,
                budgetService,
                new FailingPositionSnapshotService()
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(Instant.parse("2026-06-18T00:10:00Z"));

        assertThat(result.closed()).isZero();
        assertThat(record.getStatus()).isEqualTo("CLOSE_SUBMITTED");
        assertThat(record.getErrorMessage()).contains("POSITION_QUERY_FAILED");
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
        assertThat(stopLoss.getStatus()).isEqualTo("PROTECTION_SUBMITTED");
        verify(closeRepository).save(record);
    }

    @Test
    void recoversOnlyCurrentOwnersCloseSubmittedRecords() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        ClosePositionRecordEntity userARecord = closeRecord(autoOrder(pendingOrderService, budgetService));
        userARecord.setUserName("userA");
        ClosePositionRecordEntity userBRecord = closeRecord(autoOrder(pendingOrderService, budgetService));
        userBRecord.setUserName("userB");
        when(closeRepository.findByUserNameAndStatus("userA", "CLOSE_SUBMITTED")).thenReturn(List.of(userARecord));
        when(closeRepository.findByUserNameAndStatus("userB", "CLOSE_SUBMITTED")).thenReturn(List.of(userBRecord));
        when(closeRepository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                pendingOrderService,
                budgetService,
                new FixedPositionSnapshotService(List.of())
        );

        ClosePositionRecoveryService.CloseRecoveryResult result =
                AuthUserContext.callAs("userA", () -> service.runOnce(Instant.parse("2026-06-18T00:10:00Z")));

        assertThat(result.closed()).isEqualTo(1);
        assertThat(userARecord.getStatus()).isEqualTo("CLOSED");
        assertThat(userBRecord.getStatus()).isEqualTo("CLOSE_SUBMITTED");
        verify(closeRepository).findByUserNameAndStatus("userA", "CLOSE_SUBMITTED");
    }

    @Test
    void protectionCancelFailureIsPersistedForManualAttention() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markCloseSubmitted("close-1", "MANUAL_CLOSE_SUBMITTED");
        ClosePositionRecordEntity record = closeRecord(order);
        TradeOrderEntity tp1 = protection(order.id(), "TP1");
        tp1.setClOrdId("algo-tp1-cl");
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of(record));
        when(closeRepository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tradeOrderRepository.findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(
                order.id().toString(),
                List.of("SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE")
        )).thenReturn(List.of(tp1));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                pendingOrderService,
                budgetService,
                new FixedPositionSnapshotService(List.of()),
                new ThrowingCancelGateway()
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(Instant.parse("2026-06-18T00:10:00Z"));

        assertThat(result.closed()).isEqualTo(1);
        assertThat(tp1.getStatus()).isEqualTo("PROTECTION_CANCEL_FAILED");
        assertThat(tp1.getOkxState()).isEqualTo("CANCEL_FAILED");
        assertThat(tp1.getErrorMessage()).contains("PROTECTION_CANCEL_FAILED").contains("OKX cancel rejected");
        verify(tradeOrderRepository).saveAll(List.of(tp1));
    }

    @Test
    void retriesFailedProtectionCancelAndInvalidatesWhenOkxAccepts() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        TradeOrderEntity tp1 = protection(UUID.randomUUID(), "TP1");
        tp1.setClOrdId("algo-tp1-cl");
        tp1.setStatus("PROTECTION_CANCEL_FAILED");
        tp1.setErrorMessage("PROTECTION_CANCEL_FAILED: cancelRetry=1");
        CapturingOkxGateway gateway = new CapturingOkxGateway();
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of());
        when(tradeOrderRepository.findByStatus("PROTECTION_CANCEL_FAILED")).thenReturn(List.of(tp1));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                new PendingOrderService(120),
                new AutoTradeBudgetService(new AgentProperties()),
                new FixedPositionSnapshotService(List.of()),
                gateway
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(Instant.parse("2026-06-18T00:20:00Z"));

        assertThat(result.protectionCancelRetried()).isEqualTo(1);
        assertThat(tp1.getStatus()).isEqualTo("INVALID");
        assertThat(tp1.getOkxState()).isEqualTo("CANCELLED");
        assertThat(tp1.getErrorMessage()).contains("PROTECTION_CANCEL_RETRY_SUCCEEDED");
        assertThat(gateway.cancelAlgoPayloads).contains(Map.of("instId", "BTC-USDT-SWAP", "algoClOrdId", "algo-tp1-cl"));
        verify(tradeOrderRepository).saveAll(List.of(tp1));
    }

    @Test
    void escalatesProtectionCancelFailureAfterRetryLimit() {
        ClosePositionRecordRepository closeRepository = mock(ClosePositionRecordRepository.class);
        TradeOrderRepository tradeOrderRepository = mock(TradeOrderRepository.class);
        TradeOrderEntity tp1 = protection(UUID.randomUUID(), "TP1");
        tp1.setClOrdId("algo-tp1-cl");
        tp1.setStatus("PROTECTION_CANCEL_FAILED");
        tp1.setErrorMessage("PROTECTION_CANCEL_FAILED: cancelRetry=2");
        when(closeRepository.findByUserNameAndStatus("local-admin", "CLOSE_SUBMITTED")).thenReturn(List.of());
        when(tradeOrderRepository.findByStatus("PROTECTION_CANCEL_FAILED")).thenReturn(List.of(tp1));
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository,
                tradeOrderRepository,
                new PendingOrderService(120),
                new AutoTradeBudgetService(new AgentProperties()),
                new FixedPositionSnapshotService(List.of()),
                new ThrowingCancelGateway()
        );

        ClosePositionRecoveryService.CloseRecoveryResult result = service.runOnce(Instant.parse("2026-06-18T00:20:00Z"));

        assertThat(result.protectionCancelAttentionRequired()).isEqualTo(1);
        assertThat(tp1.getStatus()).isEqualTo("EMERGENCY_ATTENTION_REQUIRED");
        assertThat(tp1.getOkxState()).isEqualTo("CANCEL_FAILED");
        assertThat(tp1.getErrorMessage()).contains("PROTECTION_CANCEL_RETRY_LIMIT").contains("cancelRetry=3");
        verify(tradeOrderRepository).saveAll(List.of(tp1));
    }


    private static PendingOrder autoOrder(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService) {
        TradePlan plan = samplePlan();
        BudgetAllocation allocation = budgetService.allocate(new BudgetAllocationRequest(
                new BigDecimal("50"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1,
                new BigDecimal("92"),
                new BigDecimal("30"),
                new BigDecimal("2"),
                "LOW",
                "BULLISH",
                BigDecimal.ZERO,
                new BigDecimal("2.2")
        ));
        UUID pendingOrderId = UUID.randomUUID();
        BudgetReservation reservation = budgetService.reserveBudget(
                plan.id(),
                pendingOrderId,
                plan.instId(),
                allocation.finalOrderMarginUsdt(),
                new BigDecimal("50")
        );
        PendingOrder order = pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                plan,
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                reservation.reservationId(),
                allocation,
                "AUTO" + pendingOrderId.toString().replace("-", "").substring(0, 12)
        );
        budgetService.markUsed(order.budgetReservationId());
        return order;
    }

    private static ClosePositionRecordEntity closeRecord(PendingOrder order) {
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName("local-admin");
        record.setInstId(order.instId());
        record.setPosSide(order.posSide());
        record.setMarginMode(order.tdMode());
        record.setCloseOrderId("close-1");
        record.setCloseClOrdId("CLOSE123");
        record.setPendingOrderId(order.id().toString());
        record.setSource("AUTO");
        record.setStatus("CLOSE_SUBMITTED");
        record.setCreatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        return record;
    }

    private static TradeOrderEntity protection(UUID pendingOrderId, String role) {
        TradeOrderEntity entity = new TradeOrderEntity();
        entity.setPendingOrderId(pendingOrderId.toString());
        entity.setOrderRole(role);
        entity.setInstId("BTC-USDT-SWAP");
        entity.setSide("sell");
        entity.setPosSide("long");
        entity.setOrdType("conditional");
        entity.setTdMode("cross");
        entity.setReduceOnly(true);
        entity.setStatus("PROTECTION_SUBMITTED");
        entity.setOkxState("live");
        entity.setCreatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        return entity;
    }

    private static TradeFillEntity fillRow(String ordId, String tradeId, String size, String pnl, String fee) {
        TradeFillEntity row = new TradeFillEntity();
        row.setUserName("local-admin");
        row.setPendingOrderId("pending-xau");
        row.setInstId("BTC-USDT-SWAP");
        row.setOrdId(ordId);
        row.setTradeId(tradeId);
        row.setFillSize(new BigDecimal(size));
        row.setFillPrice(new BigDecimal("4416.4"));
        row.setFillPnl(new BigDecimal(pnl));
        row.setFee(new BigDecimal(fee));
        return row;
    }

    private static ClosePositionRecordEntity completeOrphanManualClose(PendingOrder order,
                                                                        String closeOrderId,
                                                                        String closeClOrdId) {
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName("local-admin");
        record.setInstId(order.instId());
        record.setPosSide(order.posSide());
        record.setMarginMode(order.tdMode());
        record.setCloseOrderId(closeOrderId);
        record.setCloseClOrdId(closeClOrdId);
        record.setSize(BigDecimal.ONE);
        record.setAvgPx(new BigDecimal("101"));
        record.setRealizedPnl(new BigDecimal("-1.00"));
        record.setFee(new BigDecimal("-0.02"));
        record.setStatus("CLOSED");
        record.setPnlCompleteness("COMPLETE");
        record.setSource("MANUAL");
        record.setRequestSent(true);
        record.setCreatedAt(Instant.parse("2026-06-18T00:09:00Z"));
        record.setUpdatedAt(Instant.parse("2026-06-18T00:09:30Z"));
        return record;
    }

    private static class CapturingOkxGateway implements OkxOrderGateway {
        private final List<Map<String, String>> cancelAlgoPayloads = new ArrayList<>();

        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode cancelAlgoOrder(Map<String, String> payload) {
            cancelAlgoPayloads.add(Map.copyOf(payload));
            var root = new ObjectMapper().createObjectNode().put("code", "0");
            root.putArray("data").addObject().put("sCode", "0");
            return root;
        }
    }

    private static class ThrowingCancelGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode cancelAlgoOrder(Map<String, String> payload) {
            throw new IllegalStateException("OKX cancel rejected");
        }
    }

    private static TradePlan samplePlan() {
        return new TradePlan(
                UUID.randomUUID(),
                "BTC-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "LIMIT",
                DirectionBias.BULLISH,
                BigDecimal.ONE,
                new BigDecimal("100"),
                new BigDecimal("98"),
                new BigDecimal("106"),
                2,
                BigDecimal.ONE,
                new BigDecimal("22.5"),
                new BigDecimal("100"),
                new BigDecimal("2"),
                90,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                new BigDecimal("100000000"),
                "cross",
                List.of("test"),
                List.of(),
                "",
                true,
                Instant.now().plusSeconds(120)
        );
    }

    private static class FixedPositionSnapshotService extends PositionSnapshotService {
        private final List<PositionSummary> positions;

        FixedPositionSnapshotService(List<PositionSummary> positions) {
            super(null);
            this.positions = new ArrayList<>(positions);
        }

        @Override
        public List<PositionSummary> positions() {
            return positions;
        }
    }

    private static class FailingPositionSnapshotService extends PositionSnapshotService {
        FailingPositionSnapshotService() {
            super(null);
        }

        @Override
        public List<PositionSummary> positions() {
            throw new IllegalStateException("OKX positions unavailable");
        }
    }
}

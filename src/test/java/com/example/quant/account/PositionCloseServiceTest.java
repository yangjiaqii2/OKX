package com.example.quant.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetAllocationRequest;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxPositionMode;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class PositionCloseServiceTest {

    @ParameterizedTest
    @CsvSource({"long,2", "short,-2", "net,2", "net,-2"})
    void netCloseBindsTheCorrectLocalDirectionAndUsesExchangeMode(String requestedSide, String signedSize) {
        boolean shortSide = signedSize.startsWith("-");
        PendingOrder matching = netAutoOrder(shortSide);
        PendingOrder opposite = netAutoOrder(!shortSide);
        PendingOrderService orders = mock(PendingOrderService.class);
        when(orders.allOrders()).thenReturn(List.of(opposite, matching));
        when(orders.get(matching.id())).thenReturn(matching);
        PositionSnapshotService snapshots = mock(PositionSnapshotService.class);
        when(snapshots.positions()).thenReturn(List.of(netPosition(signedSize)));
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        AtomicReference<ClosePositionRecordEntity> saved = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            ClosePositionRecordEntity record = invocation.getArgument(0);
            saved.set(record);
            return record;
        });
        CloseGateway gateway = new CloseGateway();
        PositionCloseService service = new PositionCloseService(snapshots,
                new OkxTradeAdapter(gateway, OkxInstrumentRules::defaultFor, () -> OkxPositionMode.NET),
                repository, orders, null);

        AtomicReference<OrderExecutionResult> submitted = new AtomicReference<>();
        org.assertj.core.api.Assertions.assertThatCode(() ->
                submitted.set(service.closePosition("BTC-USDT-SWAP", requestedSide, "cross")))
                .doesNotThrowAnyException();

        assertThat(submitted.get().submitted()).isTrue();
        assertThat(saved.get().getPendingOrderId()).isEqualTo(matching.id().toString());
        assertThat(saved.get().getPosSide()).isEqualTo(matching.posSide());
        assertThat(matching.status()).isEqualTo(com.example.quant.order.OrderStatus.CLOSE_SUBMITTED);
        assertThat(opposite.status()).isEqualTo(com.example.quant.order.OrderStatus.PROTECTION_SUBMITTED);
        assertThat(gateway.closePayload).doesNotContainKey("posSide");

        TradeOrderRepository protections = mock(TradeOrderRepository.class);
        com.example.quant.agent.execution.TradeOrderEntity logicalStop = closeProtection(matching, matching.posSide(), "logicalSL");
        com.example.quant.agent.execution.TradeOrderEntity netStop = closeProtection(matching, "net", "netSL");
        com.example.quant.agent.execution.TradeOrderEntity oppositeStop = closeProtection(opposite, "net", "oppositeSL");
        when(protections.findByInstIdAndReduceOnlyTrueAndStatusIn(org.mockito.ArgumentMatchers.eq("BTC-USDT-SWAP"), any()))
                .thenReturn(List.of(logicalStop, netStop, oppositeStop));
        when(protections.findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(org.mockito.ArgumentMatchers.eq(matching.id().toString()), any()))
                .thenReturn(List.of(logicalStop, netStop));
        when(repository.findByUserNameAndStatus(OkxCredentialStore.SYSTEM_USER, "CLOSE_SUBMITTED"))
                .thenReturn(List.of(saved.get()));
        when(snapshots.positions()).thenReturn(List.of());
        when(orders.allOrders()).thenReturn(List.of(matching));
        OkxOrderGateway cancelGateway = mock(OkxOrderGateway.class);
        ObjectNode cancelled = new ObjectMapper().createObjectNode();
        cancelled.put("code", "0");
        cancelled.putArray("data").addObject().put("sCode", "0");
        when(cancelGateway.cancelAlgoOrder(any())).thenReturn(cancelled);
        ObjectNode terminalEntry = new ObjectMapper().createObjectNode();
        terminalEntry.putArray("data").addObject().put("state", "filled");
        when(cancelGateway.queryOrder(any())).thenReturn(terminalEntry);
        ClosePositionRecoveryService recovery = new ClosePositionRecoveryService(repository, protections, orders,
                new AutoTradeBudgetService(new AgentProperties()), snapshots, cancelGateway);

        assertThat(recovery.runOnce().closed()).isEqualTo(1);
        assertThat(matching.status()).isEqualTo(com.example.quant.order.OrderStatus.CLOSED);
        assertThat(logicalStop.getStatus()).isEqualTo("INVALID");
        assertThat(netStop.getStatus()).isEqualTo("INVALID");
        assertThat(oppositeStop.getStatus()).isEqualTo("PROTECTION_SUBMITTED");
        org.mockito.Mockito.verify(cancelGateway, org.mockito.Mockito.times(2)).cancelAlgoOrder(any());
    }

    @Test
    void unresolvedLogicalCloseCannotBeResubmittedThroughNetAlias() {
        PositionSnapshotService snapshots = mock(PositionSnapshotService.class);
        when(snapshots.positions()).thenReturn(List.of(netPosition("2")));
        ClosePositionRecordEntity unresolved = new ClosePositionRecordEntity();
        unresolved.setInstId("BTC-USDT-SWAP");
        unresolved.setPosSide("long");
        unresolved.setStatus("CLOSE_SUBMIT_UNKNOWN");
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        when(repository.findByUserNameAndStatus(OkxCredentialStore.SYSTEM_USER, "CLOSE_SUBMIT_UNKNOWN"))
                .thenReturn(List.of(unresolved));
        CloseGateway gateway = new CloseGateway();
        PositionCloseService service = new PositionCloseService(snapshots,
                new OkxTradeAdapter(gateway, OkxInstrumentRules::defaultFor, () -> OkxPositionMode.NET), repository);

        assertThatThrownBy(() -> service.closePosition("BTC-USDT-SWAP", "net", "cross"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("待确认平仓");
        assertThat(gateway.calls).isZero();
    }

    private static PositionSummary netPosition(String size) {
        return new PositionSummary("BTC-USDT-SWAP", "net", size, new BigDecimal("100"), BigDecimal.ZERO, "OKX_REAL");
    }

    private static com.example.quant.agent.execution.TradeOrderEntity closeProtection(PendingOrder order, String posSide, String clientId) {
        var protection = new com.example.quant.agent.execution.TradeOrderEntity();
        protection.setUserName(OkxCredentialStore.SYSTEM_USER);
        protection.setPendingOrderId(order.id().toString());
        protection.setInstId(order.instId());
        protection.setPosSide(posSide);
        protection.setSide("buy".equals(order.side()) ? "sell" : "buy");
        protection.setReduceOnly(true);
        protection.setClOrdId(clientId);
        protection.setStatus("PROTECTION_SUBMITTED");
        return protection;
    }

    private static PendingOrder netAutoOrder(boolean shortSide) {
        Instant now = Instant.now();
        PendingOrder order = new PendingOrder(UUID.randomUUID(), MarketType.OKX_SWAP, "BTC-USDT-SWAP",
                shortSide ? TradePlanType.OPEN_SHORT : TradePlanType.OPEN_LONG,
                shortSide ? "sell" : "buy", shortSide ? "short" : "long", "LIMIT",
                new BigDecimal("100"), BigDecimal.ONE, 2, "cross",
                new BigDecimal(shortSide ? "102" : "98"), new BigDecimal(shortSide ? "96" : "104"),
                BigDecimal.TEN, new BigDecimal("2"), 80, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100000000"), UUID.randomUUID(), "test", now, now.plusSeconds(120));
        order.applyBudgetReservation(BigDecimal.TEN, UUID.randomUUID(), null,
                order.id().toString().replace("-", ""));
        order.markSubmitted(now, "entry-" + order.id());
        order.markProtectionSubmitted("test active position");
        return order;
    }

    @Test
    void closePositionPersistsCloseSubmittedRecordBeforeReturning() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        CloseGateway gateway = new CloseGateway();
        when(repository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(),
                new OkxTradeAdapter(gateway),
                repository
        );

        OrderExecutionResult result = service.closePosition("BTC-USDT-SWAP", "long", "cross");

        assertThat(result.executed()).isTrue();
        ArgumentCaptor<ClosePositionRecordEntity> captor = ArgumentCaptor.forClass(ClosePositionRecordEntity.class);
        verify(repository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        ClosePositionRecordEntity record = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(record.getStatus()).isEqualTo("CLOSE_SUBMITTED");
        assertThat(record.getInstId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(record.getPosSide()).isEqualTo("long");
        assertThat(record.getMarginMode()).isEqualTo("cross");
        assertThat(record.getCloseOrderId()).isEqualTo("close-ord-1");
        assertThat(record.getCloseClOrdId()).isNotBlank();
        assertThat(gateway.closePayload).containsEntry("clOrdId", record.getCloseClOrdId());
        assertThat(record.getSource()).isEqualTo("MANUAL");
    }

    @Test
    void closeTimeoutPersistsUnknownIntentAndBlocksDuplicateClose() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        AtomicReference<ClosePositionRecordEntity> saved = new AtomicReference<>();
        when(repository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> {
            ClosePositionRecordEntity record = invocation.getArgument(0);
            saved.set(record);
            return record;
        });
        when(repository.findByUserNameAndStatus(OkxCredentialStore.SYSTEM_USER, "CLOSE_SUBMIT_UNKNOWN"))
                .thenAnswer(invocation -> saved.get() == null ? List.of() : List.of(saved.get()));
        TimeoutCloseGateway gateway = new TimeoutCloseGateway();
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(),
                new OkxTradeAdapter(gateway),
                repository
        );

        assertThatThrownBy(() -> service.closePosition("BTC-USDT-SWAP", "long", "cross"))
                .hasMessageContaining("timeout");

        ArgumentCaptor<ClosePositionRecordEntity> captor = ArgumentCaptor.forClass(ClosePositionRecordEntity.class);
        verify(repository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        ClosePositionRecordEntity record = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(record.getStatus()).isEqualTo("CLOSE_SUBMIT_UNKNOWN");
        assertThat(record.getCloseClOrdId()).isNotBlank();
        assertThat(record.getErrorMessage()).contains("timeout");

        assertThatThrownBy(() -> service.closePosition("BTC-USDT-SWAP", "long", "cross"))
                .hasMessageContaining("待确认平仓");
        assertThat(gateway.calls).isEqualTo(1);
    }

    @Test
    void safetyCloseUsesStableReasonAndDoesNotDuplicateSubmission() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        AtomicReference<ClosePositionRecordEntity> saved = new AtomicReference<>();
        when(repository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> {
            ClosePositionRecordEntity record = invocation.getArgument(0);
            saved.set(record);
            return record;
        });
        when(repository.findByUserNameAndCloseClOrdId(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        CloseGateway gateway = new CloseGateway();
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(), new OkxTradeAdapter(gateway), repository);
        PendingOrder order = autoOrder(new PendingOrderService(120),
                new AutoTradeBudgetService(new AgentProperties()));
        PositionSummary position = new FixedPositionSnapshotService().positions().get(0);

        OrderExecutionResult first = service.closePositionForSafety(order, position,
                "CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY");
        OrderExecutionResult second = service.closePositionForSafety(order, position,
                "CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY");

        assertThat(first.submitted()).isTrue();
        assertThat(second.submitted()).isTrue();
        assertThat(saved.get().getSource()).isEqualTo("SAFETY");
        assertThat(saved.get().getErrorMessage()).contains("CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY");
        assertThat(saved.get().getCloseClOrdId()).startsWith("SAFE");
        assertThat(gateway.calls).isEqualTo(1);
    }

    @Test
    void safetyCloseTimeoutRetainsUnknownIntentAndStableClientId() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        AtomicReference<ClosePositionRecordEntity> saved = new AtomicReference<>();
        when(repository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> {
            ClosePositionRecordEntity record = invocation.getArgument(0);
            saved.set(record);
            return record;
        });
        when(repository.findByUserNameAndCloseClOrdId(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        TimeoutCloseGateway gateway = new TimeoutCloseGateway();
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(), new OkxTradeAdapter(gateway), repository);
        PendingOrder order = autoOrder(new PendingOrderService(120),
                new AutoTradeBudgetService(new AgentProperties()));
        PositionSummary position = new FixedPositionSnapshotService().positions().get(0);

        assertThatThrownBy(() -> service.closePositionForSafety(order, position,
                "CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY"))
                .hasMessageContaining("timeout");

        assertThat(saved.get().getStatus()).isEqualTo("CLOSE_SUBMIT_UNKNOWN");
        assertThat(saved.get().getSource()).isEqualTo("SAFETY");
        assertThat(saved.get().getCloseClOrdId()).startsWith("SAFE");
        assertThat(gateway.calls).isEqualTo(1);
    }

    @Test
    void safetyCloseBeforeSendIsBoundedAndKeepsIncidentActive() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        AtomicReference<ClosePositionRecordEntity> saved = new AtomicReference<>();
        when(repository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> {
            ClosePositionRecordEntity record = invocation.getArgument(0);
            saved.set(record);
            return record;
        });
        when(repository.findByUserNameAndCloseClOrdId(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        CloseGateway gateway = new CloseGateway() {
            @Override
            public JsonNode closePosition(Map<String, String> payload) {
                throw new IllegalStateException("POSITION_NOT_CONFIRMED; OKX写请求未发送");
            }
        };
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(), new OkxTradeAdapter(gateway), repository);
        PendingOrder order = autoOrder(new PendingOrderService(120),
                new AutoTradeBudgetService(new AgentProperties()));
        PositionSummary position = new FixedPositionSnapshotService().positions().get(0);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.closePositionForSafety(order, position,
                    "CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY"))
                    .isInstanceOf(RuntimeException.class);
        }

        assertThat(saved.get().getStatus()).isEqualTo("EMERGENCY_ATTENTION_REQUIRED");
        assertThat(saved.get().getRetryCount()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({
            "disabled, CLOSE_FAILED_BEFORE_SEND, LOCAL_VALIDATION, PROTECTION_SUBMITTED, 0",
            "51000, CLOSE_REJECTED, EXCHANGE_REJECTED, PROTECTION_SUBMITTED, 1",
            "50004, CLOSE_SUBMIT_UNKNOWN, UNKNOWN_AFTER_SEND, CLOSE_SUBMITTED, 1",
            "OKX HTTP 429, CLOSE_SUBMIT_UNKNOWN, UNKNOWN_AFTER_SEND, CLOSE_SUBMITTED, 1",
            "ACK lost, CLOSE_SUBMIT_UNKNOWN, UNKNOWN_AFTER_SEND, CLOSE_SUBMITTED, 1"
    })
    void boundManualCloseOnlyMovesLifecycleWhenSubmitIsUnknown(String failure, String closeStatus,
                                                                String errorClass, OrderStatus orderStatus, int posts) {
        PendingOrder order = netAutoOrder(false);
        String entryOrderId = order.externalOrderId();
        PendingOrderService orders = mock(PendingOrderService.class);
        when(orders.allOrders()).thenReturn(List.of(order));
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        when(repository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        ObjectNode terminalEntry = new ObjectMapper().createObjectNode();
        terminalEntry.putArray("data").addObject().put("state", "filled");
        when(gateway.queryOrder(any())).thenReturn(terminalEntry);
        if ("51000".equals(failure) || "50004".equals(failure)) {
            ObjectNode errorResponse = new ObjectMapper().createObjectNode().put("code", "0");
            errorResponse.putArray("data").addObject().put("sCode", failure);
            when(gateway.closePosition(any(), any())).thenReturn(errorResponse);
        } else if (posts > 0) {
            when(gateway.closePosition(any(), any())).thenThrow(new IllegalStateException(failure));
        }
        TradingMode mode = "disabled".equals(failure) ? TradingMode.DISABLED : TradingMode.LIVE;
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway, null, OkxInstrumentRules::defaultFor,
                () -> OkxPositionMode.LONG_SHORT, new AgentProperties(), new TradingProperties(mode, true, 120, false));
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(), adapter, repository, orders, null);

        assertThatThrownBy(() -> service.closePosition("BTC-USDT-SWAP", "long", "cross"))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<ClosePositionRecordEntity> captor = ArgumentCaptor.forClass(ClosePositionRecordEntity.class);
        verify(repository, times(2)).save(captor.capture());
        ClosePositionRecordEntity record = captor.getValue();
        assertThat(record.getPendingOrderId()).isEqualTo(order.id().toString());
        assertThat(record.getStatus()).isEqualTo(closeStatus);
        assertThat(record.getRequestSent()).isEqualTo(posts != 0);
        assertThat(record.getErrorClass()).isEqualTo(errorClass);
        assertThat(record.getCloseOrderId()).isNull();
        assertThat(order.status()).isEqualTo(orderStatus);
        assertThat(order.externalOrderId()).isEqualTo(orderStatus == OrderStatus.CLOSE_SUBMITTED ? null : entryOrderId);
        verify(gateway, times(posts)).closePosition(any(), any());
    }

    @Test
    void manualCloseBindsMatchingAutoPendingOrder() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        when(repository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        PendingOrder order = autoOrder(pendingOrderService, budgetService);
        order.markProtectionSubmitted("PROTECTION_SUBMITTED");
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(),
                new OkxTradeAdapter(new CloseGateway()),
                repository,
                pendingOrderService,
                null
        );

        service.closePosition("BTC-USDT-SWAP", "long", "cross");

        ArgumentCaptor<ClosePositionRecordEntity> captor = ArgumentCaptor.forClass(ClosePositionRecordEntity.class);
        verify(repository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        ClosePositionRecordEntity record = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(record.getSource()).isEqualTo("MANUAL");
        assertThat(record.getPendingOrderId()).isEqualTo(order.id().toString());
    }

    @Test
    void manualCloseAlsoBindsMatchingQaLifecycleWithoutBudgetReservation() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        when(repository.save(any(ClosePositionRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PendingOrderService pendingOrderService = mock(PendingOrderService.class);
        PendingOrder order = mock(PendingOrder.class);
        UUID orderId = UUID.randomUUID();
        when(order.id()).thenReturn(orderId);
        when(order.instId()).thenReturn("BTC-USDT-SWAP");
        when(order.posSide()).thenReturn("long");
        when(order.status()).thenReturn(com.example.quant.order.OrderStatus.PROTECTION_SUBMITTED);
        when(order.budgetReservationId()).thenReturn(null);
        when(pendingOrderService.allOrders()).thenReturn(List.of(order));
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(),
                new OkxTradeAdapter(new CloseGateway()),
                repository,
                pendingOrderService,
                null
        );

        service.closePosition("BTC-USDT-SWAP", "long", "cross");

        ArgumentCaptor<ClosePositionRecordEntity> captor = ArgumentCaptor.forClass(ClosePositionRecordEntity.class);
        verify(repository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        ClosePositionRecordEntity record = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(record.getPendingOrderId()).isEqualTo(orderId.toString());
    }

    @Test
    void closeRecordsUsesRepositoryCountForTotal() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName(OkxCredentialStore.SYSTEM_USER);
        record.setInstId("BTC-USDT-SWAP");
        record.setStatus("CLOSE_SUBMITTED");
        record.setSource("MANUAL");
        record.setCreatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-06-18T00:00:00Z"));
        when(repository.findByUserNameOrderByCreatedAtDesc(any(), any())).thenReturn(List.of(record));
        when(repository.countByUserName(OkxCredentialStore.SYSTEM_USER)).thenReturn(42L);
        PositionCloseService service = new PositionCloseService(
                new FixedPositionSnapshotService(),
                new OkxTradeAdapter(new CloseGateway()),
                repository
        );

        assertThat(service.closeRecords(0, 10).total()).isEqualTo(42);
    }

    private static PendingOrder autoOrder(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService) {
        TradePlan plan = new TradePlan(
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
        return pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                plan,
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                UUID.randomUUID(),
                allocation,
                "AUTO" + pendingOrderId.toString().replace("-", "").substring(0, 12)
        );
    }

    private static class FixedPositionSnapshotService extends PositionSnapshotService {
        FixedPositionSnapshotService() {
            super(null);
        }

        @Override
        public List<PositionSummary> positions() {
            return List.of(new PositionSummary(
                    "BTC-USDT-SWAP",
                    "long",
                    "2",
                    BigDecimal.valueOf(100),
                    BigDecimal.ZERO,
                    BigDecimal.TEN,
                    BigDecimal.TEN,
                    "cross",
                    BigDecimal.valueOf(2),
                    BigDecimal.ZERO,
                    BigDecimal.valueOf(200),
                    BigDecimal.ZERO,
                    "OKX_REAL"
            ));
        }
    }

    private static class CloseGateway implements OkxOrderGateway {
        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            ObjectNode result = new ObjectMapper().createObjectNode();
            result.putArray("data").addObject().put("state", "filled");
            return result;
        }

        private Map<String, String> closePayload;
        private int calls;

        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode closePosition(Map<String, String> payload) {
            calls++;
            this.closePayload = payload;
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject().put("ordId", "close-ord-1");
            return root;
        }
    }

    private static class TimeoutCloseGateway extends CloseGateway {
        private int calls;

        @Override
        public JsonNode closePosition(Map<String, String> payload) {
            calls++;
            throw new IllegalStateException("timeout after submit");
        }
    }
}

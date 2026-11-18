package com.example.quant.order;

import com.example.quant.account.*;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.execution.*;
import com.example.quant.agent.lifecycle.AutoTradeLifecycleService;
import com.example.quant.agent.plan.TradePlanRecordService;
import com.example.quant.config.AgentProperties;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.*;
import com.example.quant.risk.*;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TradingSafetyRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void netLongAndShortAreVisibleOnlyToTheirOwnDirection() throws Exception {
        PendingOrder longOrder = order(false), shortOrder = order(true);
        AutoTradeLifecycleService service = lifecycle(mock(OkxOrderGateway.class), null);
        PositionSummary positive = position("net", "5"), negative = position("net", "-5");
        assertThat(invoke(service, "matchingPosition", longOrder, List.of(positive))).isEqualTo(positive);
        assertThat(invoke(service, "matchingPosition", shortOrder, List.of(negative))).isEqualTo(negative);
        assertThat(invoke(service, "matchingPosition", longOrder, List.of(negative))).isNull();
        assertThat(invoke(service, "matchingPosition", shortOrder, List.of(positive))).isNull();
    }

    @Test
    void netPositionIsBoundToPendingOrderForOrphanDetection() throws Exception {
        PendingOrder pending = order(false);
        PendingOrderService orders = mock(PendingOrderService.class);
        when(orders.allOrders()).thenReturn(List.of(pending));
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(orders,
                mock(AutoTradeBudgetService.class), new AgentProperties(), mock(OkxOrderGateway.class),
                mock(PositionSnapshotService.class));
        assertThat(invoke(service, "hasMatchingPendingOrder", position("net", "5"))).isEqualTo(true);
    }

    @Test
    void recoveryDoesNotReleaseBudgetForLiveNetPosition() {
        PendingOrder pending = order(false);
        pending.markSubmitted(Instant.now(), "entry-1");
        pending.markProtectionSubmitted("active");
        PendingOrderService orders = mock(PendingOrderService.class);
        when(orders.allOrders()).thenReturn(List.of(pending));
        PositionSnapshotService positions = mock(PositionSnapshotService.class);
        when(positions.positions()).thenReturn(List.of(position("net", "5")));
        AutoTradeBudgetService budget = mock(AutoTradeBudgetService.class);
        ClosePositionRecoveryService service = new ClosePositionRecoveryService(
                closeRepository(), mock(TradeOrderRepository.class), orders, budget, positions);
        assertThat(service.runOnce().closed()).isZero();
        assertThat(pending.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
        verifyNoInteractions(budget);
    }

    @Test
    void recoveryDoesNotCloseLiveNetPositionFromCloseRecord() throws Exception {
        ClosePositionRecoveryService service = recovery(mock(OkxOrderGateway.class));
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setInstId("BTC-USDT-SWAP"); record.setPosSide("long");
        assertThat(invoke(service, "hasOpenPosition", record, List.of(position("net", "5")))).isEqualTo(true);
        record.setPosSide("short");
        assertThat(invoke(service, "hasOpenPosition", record, List.of(position("net", "-5")))).isEqualTo(true);
    }

    @Test
    void positionGoneCleanupCancelsOnlyOwnedDirectionAndIncludesInstrument() throws Exception {
        PendingOrder pending = order(false);
        String ownId = OkxTradeAdapter.attachedStopLossClientOrderId(pending);
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.currentAlgoOrders(any())).thenReturn(JSON.readTree("""
                {"code":"0","data":[
                  {"instId":"BTC-USDT-SWAP","algoClOrdId":"manualOtherSL","posSide":"short","side":"buy","slTriggerPx":"110","sz":"5","state":"live"},
                  {"instId":"BTC-USDT-SWAP","algoClOrdId":"otherStrategySL","posSide":"long","side":"sell","slTriggerPx":"98","sz":"5","state":"live"},
                  {"instId":"BTC-USDT-SWAP","algoClOrdId":"OWN","posSide":"net","side":"sell","slTriggerPx":"98","sz":"5","state":"live"}]}
                """.replace("OWN", ownId)));
        when(gateway.cancelAlgoOrder(any())).thenReturn(cancelResponse("0"));
        invoke(lifecycle(gateway, null), "reconcilePositionGone", pending, Instant.now());
        verify(gateway, times(1)).cancelAlgoOrder(argThat(p ->
                ownId.equals(p.get("algoClOrdId")) && pending.instId().equals(p.get("instId"))));
    }

    @Test
    void sameIdentityWithWrongSideIsNeverCancelled() throws Exception {
        PendingOrder pending = order(false);
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.currentAlgoOrders(any())).thenReturn(JSON.readTree("""
                {"code":"0","data":[{"instId":"BTC-USDT-SWAP","algoClOrdId":"OWN",
                "posSide":"short","side":"buy","slTriggerPx":"110","sz":"5","state":"live"}]}
                """.replace("OWN", OkxTradeAdapter.attachedStopLossClientOrderId(pending))));
        invoke(lifecycle(gateway, null), "reconcilePositionGone", pending, Instant.now());
        verify(gateway, never()).cancelAlgoOrder(any());
    }

    @Test
    void legacyProtectionRequiresMatchingOwnerOrderAndInstrument() throws Exception {
        PendingOrder pending = order(false);
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        TradeOrderEntity own = protection();
        own.setClOrdId("legacyOwnTP1"); own.setUserName("local-admin");
        own.setPendingOrderId(pending.id().toString()); own.setReduceOnly(true);
        own.setSide("sell"); own.setPosSide("long");
        TradeOrderEntity foreign = protection();
        foreign.setClOrdId("foreignTP1"); foreign.setUserName("another-user");
        foreign.setPendingOrderId(pending.id().toString()); foreign.setReduceOnly(true);
        foreign.setSide("sell"); foreign.setPosSide("long");
        when(repository.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc("local-admin", pending.id().toString()))
                .thenReturn(List.of(own, foreign));
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.currentAlgoOrders(any())).thenReturn(JSON.readTree("""
                {"code":"0","data":[
                  {"instId":"BTC-USDT-SWAP","algoClOrdId":"legacyOwnTP1","posSide":"long","side":"sell","tpTriggerPx":"104","sz":"5","state":"live"},
                  {"instId":"BTC-USDT-SWAP","algoClOrdId":"foreignTP1","posSide":"long","side":"sell","tpTriggerPx":"104","sz":"5","state":"live"},
                  {"instId":"ETH-USDT-SWAP","algoClOrdId":"legacyOwnTP1","posSide":"long","side":"sell","tpTriggerPx":"104","sz":"5","state":"live"}]}
                """));
        when(gateway.cancelAlgoOrder(any())).thenReturn(cancelResponse("0"));
        invoke(lifecycle(gateway, repository), "reconcilePositionGone", pending, Instant.now());
        verify(gateway, times(1)).cancelAlgoOrder(argThat(p -> "legacyOwnTP1".equals(p.get("algoClOrdId"))));
    }

    @Test
    void newProfitIdentityCanBeScopedAfterRestartWithoutLocalRecord() throws Exception {
        PendingOrder pending = order(false);
        Method ids = AutoTradeLifecycleService.class.getDeclaredMethod("profitAlgoClientId", PendingOrder.class, String.class, BigDecimal.class);
        ids.setAccessible(true);
        String clientId = (String) ids.invoke(null, pending, "TP1", new BigDecimal("5"));
        assertThat(clientId.length()).isLessThanOrEqualTo(32);
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.currentAlgoOrders(any())).thenReturn(JSON.readTree("""
                {"code":"0","data":[{"instId":"BTC-USDT-SWAP","algoClOrdId":"OWN",
                "posSide":"long","side":"sell","tpTriggerPx":"104","sz":"5","state":"live"}]}
                """.replace("OWN", clientId)));
        when(gateway.cancelAlgoOrder(any())).thenReturn(cancelResponse("0"));
        invoke(lifecycle(gateway, null), "reconcilePositionGone", pending, Instant.now());
        verify(gateway).cancelAlgoOrder(argThat(p -> clientId.equals(p.get("algoClOrdId"))));
    }

    @Test
    void lifecycleCancelFailureRemainsAttentionRequired() throws Exception {
        PendingOrder pending = order(false);
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.currentAlgoOrders(any())).thenReturn(JSON.readTree("""
                {"code":"0","data":[{"instId":"BTC-USDT-SWAP","algoClOrdId":"OWN",
                "posSide":"long","side":"sell","slTriggerPx":"98","sz":"5","state":"live"}]}
                """.replace("OWN", OkxTradeAdapter.attachedStopLossClientOrderId(pending))));
        when(gateway.cancelAlgoOrder(any())).thenReturn(cancelResponse("51400"));
        invoke(lifecycle(gateway, null), "reconcilePositionGone", pending, Instant.now());
        assertThat(pending.status()).isEqualTo(OrderStatus.EMERGENCY_ATTENTION_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"51400", ""})
    void recoveryRejectsFailedOrMissingPerItemCancelCode(String code) throws Exception {
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.cancelAlgoOrder(any())).thenReturn(cancelResponse(code));
        TradeOrderEntity protection = protection();
        assertThat(invoke(recovery(gateway), "cancelProtectionOrder", protection)).isEqualTo(false);
        assertThat(protection.getOkxState()).isEqualTo("CANCEL_FAILED");
    }

    @Test
    void missingGatewayCannotProveCancellation() throws Exception {
        assertThat(invoke(recovery(null), "cancelProtectionOrder", protection())).isEqualTo(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"code\":\"0\",\"data\":[]}", "{\"code\":\"1\",\"data\":[{\"sCode\":\"0\"}]}",
            "{\"code\":\"0\",\"data\":[{\"sCode\":\"0\"},{\"sCode\":\"51400\"}]}"})
    void cancellationRequiresEveryResponseItem(String response) throws Exception {
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.cancelAlgoOrder(any())).thenReturn(JSON.readTree(response));
        assertThat(invoke(recovery(gateway), "cancelProtectionOrder", protection())).isEqualTo(false);
    }

    @Test
    void connectionResetKeepsUnknownStatusAndBudget() {
        PendingOrder pending = order(false);
        AutoTradeBudgetService budget = mock(AutoTradeBudgetService.class);
        pending.applyBudgetReservation(new BigDecimal("100"), UUID.randomUUID(), null, "safety-reset");
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.placeOrder(any())).thenThrow(new IllegalStateException("Connection reset"));
        assertThatThrownBy(() -> confirm(pending, new OkxTradeAdapter(gateway), null, budget)
                .confirm(pending.id(), new BigDecimal("100"), ContractRiskRequest.safeDefaults()))
                .isInstanceOf(OrderSubmitStatusUnknownException.class);
        assertThat(pending.status()).isEqualTo(OrderStatus.UNKNOWN_SUBMIT_STATUS);
        verify(budget, never()).release(any(), anyString());
    }

    @Test
    void connectionResetQueriesExchangeAndRetainsRecoveredAcknowledgement() throws Exception {
        PendingOrder pending = order(false);
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.placeOrder(any())).thenThrow(new IllegalStateException("Connection reset"));
        when(gateway.queryOrder(any())).thenReturn(JSON.readTree("""
                {"code":"0","data":[{"ordId":"ACK1","clOrdId":"CLIENT","state":"live","accFillSz":"0"}]}
                """.replace("CLIENT", pending.clientOrderId())));
        OrderExecutionResult result = confirm(pending, new OkxTradeAdapter(gateway), null, null)
                .confirm(pending.id(), new BigDecimal("100"), ContractRiskRequest.safeDefaults());
        assertThat(result.submitted()).isTrue();
        assertThat(pending.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(pending.externalOrderId()).isEqualTo("ACK1");
    }

    @Test
    void acceptedOrderSurvivesPlanPersistenceFailure() throws Exception {
        PendingOrder pending = order(false);
        TradePlanRecordService plans = mock(TradePlanRecordService.class);
        doThrow(new IllegalStateException("database unavailable")).when(plans).markOrderSubmitted(any(), anyString());
        AutoTradeBudgetService budget = mock(AutoTradeBudgetService.class);
        OrderExecutionResult result = confirm(pending, ackAdapter(null), plans, budget)
                .confirm(pending.id(), new BigDecimal("100"), ContractRiskRequest.safeDefaults());
        assertThat(result.externalOrderId()).isEqualTo("ACK1");
        assertThat(pending.status()).isEqualTo(OrderStatus.SUBMITTED);
        verify(budget, never()).release(any(), anyString());
    }

    @Test
    void acceptedOrderSurvivesTradeRecordPersistenceFailure() throws Exception {
        TradeOrderRecordService records = mock(TradeOrderRecordService.class);
        doThrow(new IllegalStateException("database unavailable")).when(records).recordSubmitted(any(), any());
        PendingOrder pending = order(false);
        OrderExecutionResult result = confirm(pending, ackAdapter(records), null, null)
                .confirm(pending.id(), new BigDecimal("100"), ContractRiskRequest.safeDefaults());
        assertThat(result.submitted()).isTrue();
        assertThat(pending.externalOrderId()).isEqualTo("ACK1");
        assertThat(pending.status()).isEqualTo(OrderStatus.SUBMITTED);
        verify(records, never()).recordFailed(any(), any(), anyString());
    }

    @Test
    void interruptedSubmittingOrderIsRestoredForReconciliation() {
        PendingOrder pending = order(false);
        pending.markConfirmed(Instant.now());
        pending.transition(OrderStatus.CONFIRMED, OrderStatus.SUBMITTING);
        PendingOrderRepository repository = mock(PendingOrderRepository.class);
        when(repository.findAll()).thenReturn(List.of(PendingOrderEntity.from(pending, "local-admin")));
        PendingOrder restored = new PendingOrderService(120, repository).get(pending.id());
        assertThat(restored.status()).isEqualTo(OrderStatus.UNKNOWN_SUBMIT_STATUS);
        verify(repository).save(argThat(row -> row.toDomain().status() == OrderStatus.UNKNOWN_SUBMIT_STATUS));
    }

    @Test
    void legacyRejectedOrderWithAcknowledgementIsRestored() {
        PendingOrder pending = order(false);
        pending.markSubmitted(Instant.now(), "ACK1");
        pending.markRejected("local database failed after ACK");
        PendingOrderRepository repository = mock(PendingOrderRepository.class);
        when(repository.findAll()).thenReturn(List.of(PendingOrderEntity.from(pending, "local-admin")));
        PendingOrderService restoredService = new PendingOrderService(120, repository);
        assertThat(restoredService.allOrders()).hasSize(1);
        PendingOrder restored = restoredService.get(pending.id());
        assertThat(restored.status()).isEqualTo(OrderStatus.UNKNOWN_SUBMIT_STATUS);
        assertThat(restored.externalOrderId()).isEqualTo("ACK1");
        assertThat(restored.rejectReason()).contains("LEGACY_ACK_ACCOUNTING_RECONCILIATION_REQUIRED");
    }

    @Test
    void legacyAcknowledgementCannotSkipAccountingRecovery() throws Exception {
        PendingOrder pending = order(false);
        pending.markSubmitted(Instant.now(), "ACK1");
        pending.markUnknownSubmitStatus("LEGACY_ACK_ACCOUNTING_RECONCILIATION_REQUIRED");
        OkxTradeAdapter adapter = mock(OkxTradeAdapter.class);
        when(adapter.recoverUnknownSubmitStatus(pending)).thenReturn(OrderExecutionResult.submitted("ACK1", "accepted"));
        assertThat(confirm(pending, adapter, null, null).resolveUnknownSubmit(pending.id()).unknown()).isTrue();
        PendingOrderService orders = mock(PendingOrderService.class);
        com.example.quant.task.AutoTradeRecoveryTask task = new com.example.quant.task.AutoTradeRecoveryTask(
                orders, mock(AutoTradeBudgetService.class), new AgentProperties(), adapter);
        invoke(task, "recoverUnknownSubmit", pending, Instant.now());
        assertThat(pending.status()).isEqualTo(OrderStatus.UNKNOWN_SUBMIT_STATUS);
        verify(adapter, never()).recoverUnknownSubmitStatus(any());
    }

    @Test
    void lifecycleCannotEraseLegacyAccountingHoldForAgedLivePosition() {
        PendingOrder pending = order(false);
        pending.markSubmitted(Instant.now().minusSeconds(14400), "ACK1");
        pending.markUnknownSubmitStatus("LEGACY_ACK_ACCOUNTING_RECONCILIATION_REQUIRED");
        PendingOrderService orders = mock(PendingOrderService.class);
        when(orders.allOrders()).thenReturn(List.of(pending));
        PositionSnapshotService positions = mock(PositionSnapshotService.class);
        when(positions.positions()).thenReturn(List.of(position("net", "5")));
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(orders,
                mock(AutoTradeBudgetService.class), new AgentProperties(), gateway, positions);
        service.runOnce();
        assertThat(pending.status()).isEqualTo(OrderStatus.UNKNOWN_SUBMIT_STATUS);
        assertThat(pending.requiresLegacyAccountingReconciliation()).isTrue();
        verify(gateway, never()).closePosition(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"code\":\"50004\",\"msg\":\"endpoint timeout\",\"data\":[]}",
            "{\"code\":\"1\",\"data\":[{\"sCode\":\"50004\",\"sMsg\":\"timeout\"}]}",
            "{\"code\":\"0\",\"data\":[{\"sCode\":\"50004\",\"sMsg\":\"timeout\"}]}"})
    void exchangeTimeoutCodeKeepsUnknownAndBudget(String response) throws Exception {
        PendingOrder pending = order(false);
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.placeOrder(any())).thenReturn(JSON.readTree(response));
        AutoTradeBudgetService budget = mock(AutoTradeBudgetService.class);
        assertThatThrownBy(() -> confirm(pending, new OkxTradeAdapter(gateway), null, budget)
                .confirm(pending.id(), new BigDecimal("100"), ContractRiskRequest.safeDefaults()))
                .isInstanceOf(OrderSubmitStatusUnknownException.class);
        assertThat(pending.status()).isEqualTo(OrderStatus.UNKNOWN_SUBMIT_STATUS);
        verify(budget, never()).release(any(), anyString());
        verify(gateway).queryOrder(any());
    }

    @Test
    void pendingPersistenceFailureAfterAckPreservesStatusAndBudget() throws Exception {
        PendingOrder pending = order(false);
        pending.onChange(() -> {
            if (pending.status() == OrderStatus.SUBMITTED) throw new IllegalStateException("database down");
        });
        AutoTradeBudgetService budget = mock(AutoTradeBudgetService.class);
        OrderExecutionResult result = confirm(pending, ackAdapter(null), null, budget)
                .confirm(pending.id(), new BigDecimal("100"), ContractRiskRequest.safeDefaults());
        assertThat(result.submitted()).isTrue();
        assertThat(pending.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(pending.externalOrderId()).isEqualTo("ACK1");
        verify(budget, never()).release(any(), anyString());
    }

    @Test
    void explicitExchangeRejectionReleasesBudget() throws Exception {
        PendingOrder pending = order(false);
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.placeOrder(any())).thenReturn(JSON.readTree(
                "{\"code\":\"0\",\"data\":[{\"sCode\":\"51008\",\"sMsg\":\"insufficient balance\"}]}"));
        AutoTradeBudgetService budget = mock(AutoTradeBudgetService.class);
        assertThatThrownBy(() -> confirm(pending, new OkxTradeAdapter(gateway), null, budget)
                .confirm(pending.id(), new BigDecimal("100"), ContractRiskRequest.safeDefaults()))
                .isInstanceOf(OkxApiRejectedException.class);
        assertThat(pending.status()).isEqualTo(OrderStatus.REJECTED);
        verify(budget).release(pending.budgetReservationId(), "OKX_REJECTED");
        verify(gateway, never()).queryOrder(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRE_ORDER_SETUP_FAILED: trading engine not ready", "API_FAILED: ai plan failed",
            "DAILY_RISK_STATE_UNAVAILABLE", "OKX_SUBMIT_STATUS_UNKNOWN: stop_loss plan",
            "交易引擎尚未完成启动对账；新增风险操作已阻断"})
    void globalFailuresStopRoundEvenWhenReasonContainsPlanOrAi(String reason) {
        assertThat(new FailureReasonClassifier(new AgentProperties()).classify("PLAN_OR_CONFIRM", reason))
                .isEqualTo(FailureClassification.STOP_ROUND);
    }

    private static PendingOrder order(boolean shortSide) {
        Instant now = Instant.now();
        PendingOrder pending = new PendingOrder(UUID.randomUUID(), MarketType.OKX_SWAP, "BTC-USDT-SWAP",
                shortSide ? TradePlanType.OPEN_SHORT : TradePlanType.OPEN_LONG,
                shortSide ? "sell" : "buy", shortSide ? "short" : "long", "LIMIT",
                new BigDecimal("100"), new BigDecimal("5"), 2, "cross",
                new BigDecimal(shortSide ? "102" : "98"), new BigDecimal(shortSide ? "96" : "104"),
                BigDecimal.TEN, new BigDecimal("2"), 80, new BigDecimal("0.0001"),
                new BigDecimal("0.02"), new BigDecimal("100000000"), UUID.randomUUID(), "probe",
                now, now.plusSeconds(120));
        pending.applyBudgetReservation(new BigDecimal("100"), UUID.randomUUID(), null, "safety" + pending.id().toString().replace("-", "").substring(0, 12));
        return pending;
    }

    private static PositionSummary position(String side, String size) {
        return new PositionSummary("BTC-USDT-SWAP", side, size, new BigDecimal("100"), BigDecimal.ZERO, "LIVE");
    }

    private static AutoTradeLifecycleService lifecycle(OkxOrderGateway gateway, TradeOrderRepository repository) {
        return new AutoTradeLifecycleService(mock(PendingOrderService.class), mock(AutoTradeBudgetService.class),
                new AgentProperties(), gateway, mock(PositionSnapshotService.class), OkxInstrumentRules::defaultFor,
                null, repository);
    }

    private static ClosePositionRecoveryService recovery(OkxOrderGateway gateway) {
        return new ClosePositionRecoveryService(mock(ClosePositionRecordRepository.class),
                mock(TradeOrderRepository.class), mock(PendingOrderService.class), mock(AutoTradeBudgetService.class),
                mock(PositionSnapshotService.class), gateway);
    }

    private static ClosePositionRecordRepository closeRepository() {
        ClosePositionRecordRepository repository = mock(ClosePositionRecordRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return repository;
    }

    private static TradeOrderEntity protection() {
        TradeOrderEntity row = new TradeOrderEntity();
        row.setInstId("BTC-USDT-SWAP"); row.setOkxOrdId("algo-1");
        return row;
    }

    private static JsonNode cancelResponse(String code) throws Exception {
        return JSON.readTree("{\"code\":\"0\",\"data\":[{\"algoId\":\"algo-1\",\"sCode\":\"" + code + "\"}]}");
    }

    private static OkxTradeAdapter ackAdapter(TradeOrderRecordService records) throws Exception {
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.placeOrder(any())).thenReturn(JSON.readTree("{\"code\":\"0\",\"data\":[{\"ordId\":\"ACK1\",\"sCode\":\"0\"}]}"));
        return new OkxTradeAdapter(gateway, records, OkxInstrumentRules::defaultFor,
                () -> OkxPositionMode.LONG_SHORT, new AgentProperties());
    }

    private static OrderConfirmService confirm(PendingOrder pending, OkxTradeAdapter adapter,
                                               TradePlanRecordService plans, AutoTradeBudgetService budget) {
        pending.transition(OrderStatus.BUDGET_RESERVED, OrderStatus.PENDING_CONFIRM);
        PendingOrderService orders = mock(PendingOrderService.class);
        when(orders.get(pending.id())).thenReturn(pending);
        RiskService risk = mock(RiskService.class);
        when(risk.check(any(ContractRiskRequest.class))).thenReturn(new RiskCheckResult(true, RiskLevel.LOW,
                null, null, List.of(), null, null, null, Instant.now()));
        return new OrderConfirmService(orders, risk, adapter, null, null, null, null, plans, budget, Clock.systemUTC());
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Class<?>[] types = java.util.Arrays.stream(args).map(a -> a instanceof List ? List.class : a.getClass()).toArray(Class<?>[]::new);
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}

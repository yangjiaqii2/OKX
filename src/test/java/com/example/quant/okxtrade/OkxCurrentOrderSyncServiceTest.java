package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.ledger.TradeFillLedgerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class OkxCurrentOrderSyncServiceTest {

    @Test
    void backfillsAllExchangeFillsForRecentTerminalOrder() {
        TradeOrderEntity filled = new TradeOrderEntity();
        filled.setUserName("alice");
        filled.setOrderRole("ENTRY");
        filled.setInstId("XAU-USDT-SWAP");
        filled.setOkxOrdId("ord-filled");
        filled.setStatus("FILLED");
        filled.setFilledSize(new BigDecimal("717"));
        filled.setUpdatedAt(Instant.now());
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findByStatus("FILLED")).thenReturn(List.of(filled));
        when(repository.findByStatus("PARTIALLY_FILLED_CANCELED")).thenReturn(List.of());
        when(repository.findByStatus("RECONCILING")).thenReturn(List.of());
        OkxCurrentOrderService current = mock(OkxCurrentOrderService.class);
        when(current.hasCredentials()).thenReturn(true);
        when(current.currentOrders()).thenReturn(List.of());
        when(current.currentAlgoOrders()).thenReturn(List.of());
        when(current.fills("XAU-USDT-SWAP", "ord-filled")).thenReturn(List.of(
                fill("fill-1", "12"), fill("fill-2", "705")));
        TradeFillLedgerService ledger = mock(TradeFillLedgerService.class);
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(current, repository);
        service.configureFillLedger(ledger);

        OkxCurrentOrderSyncService.SyncResult result = AuthUserContext.callAs("alice", service::syncOnce);

        assertThat(result.failed()).isFalse();
        assertThat(result.errorMessage()).isNull();
        verify(current).fills("XAU-USDT-SWAP", "ord-filled");
        verify(ledger, org.mockito.Mockito.times(2)).record(
                org.mockito.ArgumentMatchers.eq(filled),
                org.mockito.ArgumentMatchers.any(OkxCurrentOrderService.Fill.class));
    }

    @Test
    void unboundAccountSkipsSyncWithoutInvalidatingLocalOrders() {
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(
                new OkxCurrentOrderService(new UnboundCurrentOrderGateway()),
                repository
        );

        OkxCurrentOrderSyncService.SyncResult result = service.syncOnce();

        assertThat(result.failed()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("OKX_ACCOUNT_UNBOUND");
        verifyNoInteractions(repository);
    }

    @Test
    void upsertsCurrentOkxNormalAndAlgoOrdersIntoTradeOrder() {
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findFirstByUserNameAndOkxOrdId(any(), any())).thenReturn(Optional.empty());
        when(repository.findFirstByUserNameAndClOrdId(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(
                new OkxCurrentOrderService(new CurrentOrderGateway()),
                repository
        );

        OkxCurrentOrderSyncService.SyncResult result = service.syncOnce();

        assertThat(result.failed()).isFalse();
        assertThat(result.normalOrders()).isEqualTo(1);
        assertThat(result.algoOrders()).isEqualTo(1);
        assertThat(result.activeInstIds()).containsExactly("BTC-USDT-SWAP");
        ArgumentCaptor<TradeOrderEntity> captor = ArgumentCaptor.forClass(TradeOrderEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TradeOrderEntity::getOrderRole)
                .containsExactly("ENTRY", "STOP_LOSS");
        assertThat(captor.getAllValues())
                .extracting(TradeOrderEntity::getStatus)
                .containsExactly("SUBMITTED", "PROTECTION_SUBMITTED");
        assertThat(captor.getAllValues())
                .extracting(TradeOrderEntity::getOkxState)
                .containsExactly("live", "live");
    }

    @Test
    void normalizesBlankClientOrderIdsToNullBeforeSaving() {
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findFirstByUserNameAndOkxOrdId(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(
                new OkxCurrentOrderService(new BlankClientIdCurrentOrderGateway()),
                repository
        );

        OkxCurrentOrderSyncService.SyncResult result = service.syncOnce();

        assertThat(result.failed()).isFalse();
        ArgumentCaptor<TradeOrderEntity> captor = ArgumentCaptor.forClass(TradeOrderEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TradeOrderEntity::getClOrdId)
                .containsExactly(null, null);
        assertThat(captor.getAllValues())
                .extracting(TradeOrderEntity::getOkxOrdId)
                .containsExactly("ord-blank-cl", "algo-blank-cl");
    }

    @Test
    void updatesExistingLocalOrderByClientOrderId() {
        TradeOrderEntity existing = new TradeOrderEntity();
        existing.setOrderRole("ENTRY");
        existing.setInstId("BTC-USDT-SWAP");
        existing.setSide("buy");
        existing.setPosSide("long");
        existing.setOrdType("limit");
        existing.setTdMode("cross");
        existing.setReduceOnly(false);
        existing.setClOrdId("AUTOentry1");
        existing.setStatus("UNKNOWN_SUBMIT_STATUS");
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findFirstByUserNameAndOkxOrdId(any(), any())).thenReturn(Optional.empty());
        when(repository.findFirstByUserNameAndClOrdId("local-admin", "AUTOentry1")).thenReturn(Optional.of(existing));
        when(repository.findFirstByUserNameAndClOrdId("local-admin", "qa123sl")).thenReturn(Optional.empty());
        when(repository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(
                new OkxCurrentOrderService(new CurrentOrderGateway()),
                repository
        );

        service.syncOnce();

        assertThat(existing.getOkxOrdId()).isEqualTo("ord-1");
        assertThat(existing.getStatus()).isEqualTo("SUBMITTED");
        assertThat(existing.getOkxState()).isEqualTo("live");
    }

    @Test
    void failedDetailQueryKeepsLastConfirmedOrderFactsAndRequiresReconciliation() {
        TradeOrderEntity stale = new TradeOrderEntity();
        stale.setOrderRole("ENTRY");
        stale.setInstId("ETH-USDT-SWAP");
        stale.setSide("buy");
        stale.setPosSide("long");
        stale.setOrdType("limit");
        stale.setTdMode("cross");
        stale.setReduceOnly(false);
        stale.setClOrdId("AUTOstale");
        stale.setOkxOrdId("ord-stale");
        stale.setStatus("PARTIALLY_FILLED");
        stale.setOkxState("partially_filled");
        stale.setFilledSize(new BigDecimal("4"));
        stale.setOkxUpdatedAt(Instant.ofEpochMilli(2000));
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findFirstByUserNameAndOkxOrdId(any(), any())).thenReturn(Optional.empty());
        when(repository.findFirstByUserNameAndClOrdId(any(), any())).thenReturn(Optional.empty());
        when(repository.findByStatus("PARTIALLY_FILLED")).thenReturn(List.of(stale));
        when(repository.findByStatus("PROTECTION_SUBMITTED")).thenReturn(List.of());
        when(repository.findByStatus("OPEN")).thenReturn(List.of());
        when(repository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(
                new OkxCurrentOrderService(new EmptyCurrentOrderGateway() {
                    @Override
                    public JsonNode queryOrder(Map<String, String> payload) {
                        throw new IllegalStateException("OKX HTTP 429");
                    }
                }),
                repository
        );

        OkxCurrentOrderSyncService.SyncResult result = service.syncOnce();

        assertThat(stale.getStatus()).isEqualTo("PARTIALLY_FILLED");
        assertThat(stale.getOkxState()).isEqualTo("partially_filled");
        assertThat(stale.getFilledSize()).isEqualByComparingTo("4");
        assertThat(stale.getOkxUpdatedAt()).isEqualTo(Instant.ofEpochMilli(2000));
        assertThat(stale.getErrorMessage()).contains("ORDER_DETAILS_UNAVAILABLE");
        assertThat(result.errorMessage()).isEqualTo("ORDER_RECONCILIATION_REQUIRED:1");
    }

    @ParameterizedTest
    @CsvSource({"filled, live, 1000", "filled, live, 3000", "partially_filled, partially_filled, 1000",
            "partially_filled, live, 3000", "partially_filled, partially_filled, ''"})
    void oldOrRegressiveRestSnapshotCannotOverwriteWsFacts(String wsState, String restState, String restTime)
            throws Exception {
        TradeOrderEntity order = new TradeOrderEntity();
        order.setUserName("alice");
        order.setInstId("BTC-USDT-SWAP");
        order.setOkxOrdId("ord-1");
        order.setStatus("SUBMITTED");
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findFirstByUserNameAndOkxOrdId("alice", "ord-1")).thenReturn(Optional.of(order));
        OkxOrderEventService ws = new OkxOrderEventService(repository, mock(TradeFillLedgerService.class));
        String filled = "filled".equals(wsState) ? "10" : "4";
        ObjectMapper json = new ObjectMapper();
        JsonNode event = json.readTree("""
                {"instId":"BTC-USDT-SWAP","ordId":"ord-1","state":"%s","sz":"10",
                 "accFillSz":"%s","avgPx":"100","uTime":"2000","tradeId":"fill-1","fillSz":"%s"}
                """.formatted(wsState, filled, filled));
        OkxOrderGateway gateway = mock(OkxOrderGateway.class);
        when(gateway.hasCredentials()).thenReturn(true);
        ObjectNode snapshot = json.createObjectNode();
        snapshot.putArray("data").addObject().put("instId", "BTC-USDT-SWAP").put("ordId", "ord-1")
                .put("state", restState).put("sz", "10")
                .put("accFillSz", "partially_filled".equals(restState) || ("3000".equals(restTime) && "filled".equals(wsState))
                        ? filled : "0")
                .put("uTime", restTime);
        when(gateway.currentOrders(any())).thenReturn(snapshot);
        when(gateway.currentAlgoOrders(any())).thenAnswer(call -> {
            assertThat(ws.apply(event)).isTrue();
            return null;
        });

        var sync = new OkxCurrentOrderSyncService(new OkxCurrentOrderService(gateway), repository);
        assertThat(AuthUserContext.callAs("alice", sync::syncOnce).failed()).isFalse();

        assertThat(order.getStatus()).isEqualTo("filled".equals(wsState) ? "FILLED" : "PARTIALLY_FILLED");
        assertThat(order.getOkxState()).isEqualTo(wsState);
        assertThat(order.getFilledSize()).isEqualByComparingTo(filled);
        assertThat(order.getOkxUpdatedAt()).isEqualTo(Instant.ofEpochMilli(2000));
        verify(repository).save(order);
        verify(gateway, never()).placeOrder(any());
    }

    @Test
    void reconcilesPartialFillThenCancelWithoutTreatingItAsFilled() {
        TradeOrderEntity stale = new TradeOrderEntity();
        stale.setUserName("bob");
        stale.setOrderRole("ENTRY");
        stale.setInstId("BTC-USDT-SWAP");
        stale.setSide("buy");
        stale.setPosSide("long");
        stale.setOrdType("limit");
        stale.setTdMode("cross");
        stale.setClOrdId("AUTOpartial");
        stale.setOkxOrdId("ord-partial");
        stale.setStatus("PARTIALLY_FILLED");
        stale.setOkxState("partially_filled");
        stale.setFilledSize(new BigDecimal("4"));
        stale.setOkxUpdatedAt(Instant.ofEpochMilli(1780000001000L));
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findByStatus("SUBMITTED")).thenReturn(List.of());
        when(repository.findByStatus("PROTECTION_SUBMITTED")).thenReturn(List.of());
        when(repository.findByStatus("PARTIALLY_FILLED")).thenReturn(List.of(stale));
        when(repository.findByStatus("OPEN")).thenReturn(List.of());
        when(repository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(
                new OkxCurrentOrderService(new PartialCanceledGateway()), repository);

        AuthUserContext.runAs("bob", service::syncOnce);

        assertThat(stale.getStatus()).isEqualTo("PARTIALLY_FILLED_CANCELED");
        assertThat(stale.getOkxState()).isEqualTo("canceled");
        assertThat(stale.getSize()).isEqualByComparingTo("10");
        assertThat(stale.getFilledSize()).isEqualByComparingTo("4");
        assertThat(stale.getRemainingSize()).isEqualByComparingTo("6");
        assertThat(stale.getAverageFillPrice()).isEqualByComparingTo("100.25");
    }

    @Test
    void reconcilesMissingAlgoOrderFromDedicatedAlgoDetailsEndpoint() {
        TradeOrderEntity staleProtection = new TradeOrderEntity();
        staleProtection.setOrderRole("STOP_LOSS");
        staleProtection.setInstId("ETH-USDT-SWAP");
        staleProtection.setSide("sell");
        staleProtection.setPosSide("long");
        staleProtection.setOrdType("conditional");
        staleProtection.setTdMode("cross");
        staleProtection.setReduceOnly(true);
        staleProtection.setClOrdId("algostale");
        staleProtection.setOkxOrdId("algo-stale");
        staleProtection.setStatus("PROTECTION_SUBMITTED");
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findByStatus("SUBMITTED")).thenReturn(List.of());
        when(repository.findByStatus("PROTECTION_SUBMITTED")).thenReturn(List.of(staleProtection));
        when(repository.findByStatus("PARTIALLY_FILLED")).thenReturn(List.of());
        when(repository.findByStatus("OPEN")).thenReturn(List.of());
        when(repository.findByStatus("RECONCILING")).thenReturn(List.of());
        when(repository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(
                new OkxCurrentOrderService(new EffectiveAlgoOrderGateway()),
                repository
        );

        OkxCurrentOrderSyncService.SyncResult result = service.syncOnce();

        assertThat(staleProtection.getStatus()).isEqualTo("ALGO_EFFECTIVE");
        assertThat(staleProtection.getOkxState()).isEqualTo("effective");
        assertThat(staleProtection.getFilledSize()).isEqualByComparingTo("5");
        assertThat(staleProtection.getAverageFillPrice()).isEqualByComparingTo("97.5");
        assertThat(staleProtection.getPnlCompleteness()).isEqualTo("PARTIAL");
        assertThat(staleProtection.getErrorMessage()).isEqualTo("ALGO_CHILD_ORDER_PENDING");
        assertThat(result.errorMessage()).isNull();
        verify(repository).save(staleProtection);
    }

    @Test
    void followsEffectiveAlgoIntoChildOrderAndAllChildFills() {
        TradeOrderEntity parent = new TradeOrderEntity();
        parent.setUserName("alice");
        parent.setOrderRole("STOP_LOSS");
        parent.setInstId("XAU-USDT-SWAP");
        parent.setSide("sell");
        parent.setPosSide("long");
        parent.setOrdType("conditional");
        parent.setTdMode("cross");
        parent.setReduceOnly(true);
        parent.setClOrdId("SLstable");
        parent.setOkxOrdId("algo-xau");
        parent.setStatus("PROTECTION_SUBMITTED");
        TradeOrderEntity child = null;
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findByStatus("SUBMITTED")).thenReturn(List.of());
        when(repository.findByStatus("PROTECTION_SUBMITTED")).thenReturn(List.of(parent));
        when(repository.findByStatus("PARTIALLY_FILLED")).thenReturn(List.of());
        when(repository.findByStatus("OPEN")).thenReturn(List.of());
        when(repository.findByStatus("RECONCILING")).thenReturn(List.of());
        when(repository.findByStatus("ALGO_EFFECTIVE")).thenReturn(List.of());
        when(repository.findByStatus("ALGO_PARTIALLY_EFFECTIVE")).thenReturn(List.of());
        when(repository.findFirstByUserNameAndOkxOrdId("alice", "child-xau"))
                .thenReturn(Optional.empty());
        when(repository.save(any(TradeOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OkxCurrentOrderService current = mock(OkxCurrentOrderService.class);
        when(current.hasCredentials()).thenReturn(true);
        when(current.currentOrders()).thenReturn(List.of());
        when(current.currentAlgoOrders()).thenReturn(List.of());
        when(current.algoOrderDetail("algo-xau", "SLstable"))
                .thenReturn(new OkxCurrentOrderService.AlgoOrderDetail(
                        "algo-xau", "effective", new BigDecimal("717"), new BigDecimal("717"),
                        new BigDecimal("4416.4"), Instant.now(), "", List.of("child-xau")));
        when(current.orderDetail("XAU-USDT-SWAP", "child-xau", ""))
                .thenReturn(new OkxCurrentOrderService.OrderDetail(
                        "child-xau", "", "filled", new BigDecimal("717"), new BigDecimal("717"),
                        new BigDecimal("4416.4"), Instant.now()));
        List<OkxCurrentOrderService.Fill> fills = List.of(
                fill("xau-1", "300"), fill("xau-2", "417"));
        when(current.fills("XAU-USDT-SWAP", "child-xau")).thenReturn(fills);
        TradeFillLedgerService ledger = mock(TradeFillLedgerService.class);
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(current, repository);
        service.configureFillLedger(ledger);

        OkxCurrentOrderSyncService.SyncResult result = AuthUserContext.callAs("alice", service::syncOnce);

        assertThat(result.errorMessage()).isNull();
        assertThat(parent.getPnlCompleteness()).isEqualTo("COMPLETE");
        ArgumentCaptor<TradeOrderEntity> captor = ArgumentCaptor.forClass(TradeOrderEntity.class);
        verify(repository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        TradeOrderEntity savedChild = captor.getAllValues().stream()
                .filter(row -> "child-xau".equals(row.getOkxOrdId())).findFirst().orElseThrow();
        assertThat(savedChild.getParentAlgoId()).isEqualTo("algo-xau");
        assertThat(savedChild.getPendingOrderId()).isEqualTo(parent.getPendingOrderId());
        assertThat(savedChild.getPnlCompleteness()).isEqualTo("COMPLETE");
        verify(ledger, org.mockito.Mockito.times(2)).record(
                org.mockito.ArgumentMatchers.eq(savedChild), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void currentAccountSyncDoesNotInvalidateAnotherUsersOrders() {
        TradeOrderEntity foreign = new TradeOrderEntity();
        foreign.setUserName("alice");
        foreign.setOrderRole("ENTRY");
        foreign.setInstId("BTC-USDT-SWAP");
        foreign.setSide("buy");
        foreign.setOrdType("limit");
        foreign.setTdMode("cross");
        foreign.setClOrdId("alice-order");
        foreign.setStatus("SUBMITTED");
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findByStatus("SUBMITTED")).thenReturn(List.of(foreign));
        when(repository.findByStatus("PROTECTION_SUBMITTED")).thenReturn(List.of());
        when(repository.findByStatus("PARTIALLY_FILLED")).thenReturn(List.of());
        when(repository.findByStatus("OPEN")).thenReturn(List.of());
        OkxCurrentOrderSyncService service = new OkxCurrentOrderSyncService(
                new OkxCurrentOrderService(new EmptyCurrentOrderGateway()), repository);

        AuthUserContext.runAs("bob", service::syncOnce);

        assertThat(foreign.getStatus()).isEqualTo("SUBMITTED");
        verify(repository, never()).save(foreign);
    }

    private static class CurrentOrderGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode currentOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("instId", "BTC-USDT-SWAP");
            item.put("ordId", "ord-1");
            item.put("clOrdId", "AUTOentry1");
            item.put("side", "buy");
            item.put("posSide", "long");
            item.put("ordType", "limit");
            item.put("tdMode", "cross");
            item.put("reduceOnly", "false");
            item.put("sz", "5");
            item.put("px", "100");
            item.put("state", "live");
            item.put("cTime", "1780000000000");
            return root;
        }

        @Override
        public JsonNode currentAlgoOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("instId", "BTC-USDT-SWAP");
            item.put("algoId", "algo-1");
            item.put("algoClOrdId", "qa123sl");
            item.put("side", "sell");
            item.put("posSide", "long");
            item.put("ordType", "conditional");
            item.put("tdMode", "cross");
            item.put("sz", "5");
            item.put("slTriggerPx", "98");
            item.put("state", "live");
            item.put("cTime", "1780000000000");
            return root;
        }
    }

    private static OkxCurrentOrderService.Fill fill(String tradeId, String size) {
        return new OkxCurrentOrderService.Fill(
                tradeId, "buy", "long", new BigDecimal("4416.4"), new BigDecimal(size),
                new BigDecimal("-0.01"), "USDT", BigDecimal.ZERO, Instant.now());
    }

    private static class BlankClientIdCurrentOrderGateway extends CurrentOrderGateway {
        @Override
        public JsonNode currentOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("instId", "BTC-USDT-SWAP");
            item.put("ordId", "ord-blank-cl");
            item.put("clOrdId", "");
            item.put("side", "buy");
            item.put("posSide", "long");
            item.put("ordType", "limit");
            item.put("tdMode", "cross");
            item.put("reduceOnly", "false");
            item.put("sz", "5");
            item.put("px", "100");
            item.put("state", "live");
            item.put("cTime", "1780000000000");
            return root;
        }

        @Override
        public JsonNode currentAlgoOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("instId", "BTC-USDT-SWAP");
            item.put("algoId", "algo-blank-cl");
            item.put("algoClOrdId", "");
            item.put("side", "sell");
            item.put("posSide", "long");
            item.put("ordType", "conditional");
            item.put("tdMode", "cross");
            item.put("sz", "5");
            item.put("slTriggerPx", "98");
            item.put("state", "live");
            item.put("cTime", "1780000000000");
            return root;
        }
    }

    private static class EmptyCurrentOrderGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode currentOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data");
            return root;
        }

        @Override
        public JsonNode currentAlgoOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data");
            return root;
        }
    }

    private static class UnboundCurrentOrderGateway extends EmptyCurrentOrderGateway {
        @Override
        public boolean hasCredentials() {
            return false;
        }

        @Override
        public JsonNode currentOrders(Map<String, String> payload) {
            throw new AssertionError("unbound account must not query private orders");
        }
    }

    private static final class PartialCanceledGateway extends EmptyCurrentOrderGateway {
        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("instId", "BTC-USDT-SWAP");
            item.put("ordId", "ord-partial");
            item.put("clOrdId", "AUTOpartial");
            item.put("state", "canceled");
            item.put("sz", "10");
            item.put("accFillSz", "4");
            item.put("avgPx", "100.25");
            item.put("uTime", "1780000001000");
            return root;
        }
    }

    private static final class EffectiveAlgoOrderGateway extends EmptyCurrentOrderGateway {
        @Override
        public JsonNode queryAlgoOrder(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("algoId", "algo-stale");
            item.put("algoClOrdId", "algostale");
            item.put("state", "effective");
            item.put("sz", "5");
            item.put("actualSz", "5");
            item.put("actualPx", "97.5");
            item.put("uTime", "1780000002000");
            return root;
        }
    }
}

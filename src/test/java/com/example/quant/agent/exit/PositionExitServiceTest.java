package com.example.quant.agent.exit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.TradingOperationContext;
import com.example.quant.order.PendingOrder;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PositionExitServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-17T00:00:00Z");
    private static final String INST_ID = "TEST-USDT-SWAP";

    @Test
    void movesTheProtectiveStopTowardProfitAndPersistsTheExcursion() {
        Store store = new Store();
        FakeGateway gateway = new FakeGateway(new BigDecimal("0.95"));
        PositionExitService service = service(store, gateway);

        PositionExitService.ExitRunResult result = service.reconcileAndProtect(
                longOrder(), position("1.00", "1.90"), rules(), stopView("0.95"), T0);

        assertThat(result.outcome()).isEqualTo(PositionExitService.ExitRunResult.Outcome.APPLIED);
        PositionExitStateEntity row = store.single();
        assertThat(row.getAppliedProtectiveStop()).isEqualByComparingTo("1.75");
        assertThat(row.getInitialStopLoss()).isEqualByComparingTo("0.95");
        assertThat(row.getMfeR()).isEqualByComparingTo("18");
        assertThat(row.getHighestSinceEntry()).isEqualByComparingTo("1.90");
        assertThat(row.getExitState()).isEqualTo(PositionExitState.TRAILING.name());
        assertThat(row.getObservedStage()).isEqualTo(PositionExitState.TRAILING.name());
        assertThat(row.getConfirmedProtectionStage()).isEqualTo(PositionExitState.TRAILING.name());
        assertThat(row.getStopAmendState()).isEqualTo("APPLIED");
        assertThat(gateway.amendPayloads).hasSize(1);
        assertThat(gateway.amendPayloads.get(0)).containsEntry("newSlTriggerPx", "1.75");
    }

    @Test
    void amendIsSkippedWhenTheExchangeAlreadyHasTheTargetStop() {
        Store store = new Store();
        FakeGateway gateway = new FakeGateway(new BigDecimal("1.75"));
        PositionExitService service = service(store, gateway);

        PositionExitService.ExitRunResult result = service.reconcileAndProtect(
                longOrder(), position("1.00", "1.90"), rules(), stopView("1.75"), T0);

        // The engine itself short-circuits: the exchange already holds the
        // target, so no request is sent at all.
        assertThat(result.outcome()).isEqualTo(PositionExitService.ExitRunResult.Outcome.NO_CHANGE);
        assertThat(result.reason()).isEqualTo("PROTECTIVE_STOP_ALREADY_AT_TARGET");
        assertThat(gateway.amendPayloads).isEmpty();
        assertThat(store.single().getAppliedProtectiveStop()).isEqualByComparingTo("1.75");
    }

    @Test
    void aTimeoutAfterSendDoesNotProduceASecondAmend() {
        Store store = new Store();
        FakeGateway gateway = new FakeGateway(new BigDecimal("0.95"));
        gateway.throwAfterApply = true;
        PositionExitService service = service(store, gateway);
        PendingOrder order = longOrder();

        service.reconcileAndProtect(order, position("1.00", "1.90"), rules(), stopView("0.95"), T0);

        assertThat(gateway.amendPayloads).hasSize(1);
        assertThat(gateway.stopPrice).isEqualByComparingTo("1.75");
        assertThat(store.single().getConfirmedProtectionStage()).isEqualTo(PositionExitState.NEW.name());
        assertThat(store.single().getStopAmendState()).isEqualTo("RECONCILIATION_REQUIRED");

        // Next scheduler pass: read the exchange back first, see the stop is
        // already where we wanted it, and do not send anything again.
        gateway.throwAfterApply = false;
        PositionExitService.ExitRunResult second = service.reconcileAndProtect(
                order, position("1.00", "1.90"), rules(), stopView("1.75"), T0.plusSeconds(120));

        assertThat(second.outcome()).isEqualTo(PositionExitService.ExitRunResult.Outcome.NO_CHANGE);
        assertThat(gateway.amendPayloads).hasSize(1);
        assertThat(store.single().getAppliedProtectiveStop()).isEqualByComparingTo("1.75");
    }

    @Test
    void restartRecoversTheProtectiveStopWithoutResendingAnything() {
        Store store = new Store();
        FakeGateway gateway = new FakeGateway(new BigDecimal("0.95"));
        PendingOrder order = longOrder();
        service(store, gateway).reconcileAndProtect(
                order, position("1.00", "1.90"), rules(), stopView("0.95"), T0);
        assertThat(gateway.amendPayloads).hasSize(1);

        // Simulate a JVM restart: a brand new service instance, same database,
        // exchange already carries the improved stop.
        FakeGateway freshGateway = new FakeGateway(new BigDecimal("1.75"));
        PositionExitService restarted = service(store, freshGateway);
        PositionExitService.ExitRunResult afterRestart = restarted.reconcileAndProtect(
                order, position("1.00", "1.88"), rules(), stopView("1.75"), T0.plusSeconds(600));

        assertThat(afterRestart.outcome())
                .isEqualTo(PositionExitService.ExitRunResult.Outcome.NO_CHANGE);
        assertThat(freshGateway.amendPayloads).isEmpty();
        PositionExitStateEntity row = store.single();
        assertThat(row.getAppliedProtectiveStop()).isEqualByComparingTo("1.75");
        assertThat(row.getMfeR()).isEqualByComparingTo("18");
        assertThat(row.getExitState()).isEqualTo(PositionExitState.TRAILING.name());
        assertThat(row.getConfirmedProtectionStage()).isEqualTo(PositionExitState.TRAILING.name());
    }

    @Test
    void doesNotCloseOrAmendWhenTheExchangeStopCannotBeRead() {
        Store store = new Store();
        FakeGateway gateway = new FakeGateway(new BigDecimal("0.95"));
        gateway.readbackUnavailable = true;
        PositionExitService service = service(store, gateway);

        PositionExitService.ExitRunResult result = service.reconcileAndProtect(
                longOrder(), position("1.00", "1.90"), rules(), stopView("0.95"), T0);

        assertThat(result.outcome())
                .isEqualTo(PositionExitService.ExitRunResult.Outcome.READBACK_UNAVAILABLE);
        assertThat(gateway.amendPayloads).isEmpty();
        assertThat(store.single().getDesiredProtectiveStop()).isEqualByComparingTo("1.75");
        assertThat(store.single().getPendingSince()).isEqualTo(T0);
        // The stop reported by the order sync is still the only confirmed one.
        assertThat(store.single().getAppliedProtectiveStop()).isEqualByComparingTo("0.95");
        assertThat(store.single().getObservedStage()).isEqualTo(PositionExitState.TRAILING.name());
        assertThat(store.single().getConfirmedProtectionStage()).isEqualTo(PositionExitState.NEW.name());
        assertThat(store.single().getStopAmendState()).isEqualTo("READBACK_PENDING");
    }

    @Test
    void adoptsAnExchangeStopThatIsAlreadyBetterThanLocal() {
        Store store = new Store();
        FakeGateway gateway = new FakeGateway(new BigDecimal("1.10"));
        PositionExitService service = service(store, gateway);

        service.reconcileAndProtect(longOrder(), position("1.00", "1.20"), rules(), stopView("1.10"), T0);

        // mfeR = 4 -> stage TRAILING -> stop at 4 - 1.4 = 2.6R -> 1.13
        assertThat(store.single().getAppliedProtectiveStop()).isEqualByComparingTo("1.13");
        assertThat(store.single().getAppliedProtectiveStop()).isGreaterThan(new BigDecimal("1.10"));
    }

    @Test
    void neverPullsAProtectedStopBackDownOnALaterObservation() {
        Store store = new Store();
        FakeGateway gateway = new FakeGateway(new BigDecimal("1.75"));
        PositionExitService service = service(store, gateway);

        PositionExitService.ExitRunResult result = service.reconcileAndProtect(
                longOrder(), position("1.00", "0.95"), rules(), stopView("1.75"), T0.plusSeconds(600));

        assertThat(result.decision()).isNotNull();
        assertThat(store.single().getAppliedProtectiveStop()).isEqualByComparingTo("1.75");
        assertThat(gateway.amendPayloads).isEmpty();
    }

    @Test
    void recordsGivebackWhenThePositionCloses() {
        Store store = new Store();
        FakeGateway gateway = new FakeGateway(new BigDecimal("0.95"));
        PositionExitService service = service(store, gateway);
        PendingOrder order = longOrder();
        service.reconcileAndProtect(order, position("1.00", "1.90"), rules(), stopView("0.95"), T0);

        service.recordClosure(order, new BigDecimal("0.2"), T0.plusSeconds(3600), "POSITION_GONE");

        PositionExitStateEntity row = store.single();
        assertThat(row.getExitState()).isEqualTo(PositionExitState.CLOSED.name());
        assertThat(row.getMfeRFinal()).isEqualByComparingTo("18");
        assertThat(row.getRealizedR()).isEqualByComparingTo("0.2");
        assertThat(row.getGivebackR()).isEqualByComparingTo("17.8");
        assertThat(row.getGivebackRatio()).isEqualByComparingTo("0.98888889");
        assertThat(row.getExitReason()).isEqualTo("POSITION_GONE");
    }

    @Test
    void closureIsIdempotent() {
        Store store = new Store();
        PositionExitService service = service(store, new FakeGateway(new BigDecimal("0.95")));
        PendingOrder order = longOrder();
        service.reconcileAndProtect(order, position("1.00", "1.90"), rules(), stopView("0.95"), T0);

        service.recordClosure(order, new BigDecimal("1"), T0.plusSeconds(10), "POSITION_GONE");
        service.recordClosure(order, new BigDecimal("5"), T0.plusSeconds(20), "MANUAL");

        assertThat(store.single().getRealizedR()).isEqualByComparingTo("1");
        assertThat(store.single().getExitReason()).isEqualTo("POSITION_GONE");
    }

    private static PositionExitService service(Store store, FakeGateway gateway) {
        return new PositionExitService(store.repository(), gateway, new AgentProperties());
    }

    private static OkxInstrumentRules rules() {
        return new OkxInstrumentRules(INST_ID, BigDecimal.ONE, "TEST", BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("0.01"));
    }

    private static ExchangeStopView stopView(String trigger) {
        return new ExchangeStopView("lcabc123SL", "algo-1", new BigDecimal(trigger), BigDecimal.TEN);
    }

    private static PositionSummary position(String avgPx, String markPx) {
        return new PositionSummary(INST_ID, "long", "10", new BigDecimal(avgPx), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "cross", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "OKX_REAL", "MANAGED", "OWNED",
                new BigDecimal(markPx), new BigDecimal(markPx));
    }

    private static PendingOrder longOrder() {
        PendingOrder order = mock(PendingOrder.class);
        when(order.id()).thenReturn(UUID.randomUUID());
        when(order.instId()).thenReturn(INST_ID);
        when(order.posSide()).thenReturn("long");
        when(order.side()).thenReturn("buy");
        when(order.action()).thenReturn(TradePlanType.OPEN_LONG);
        when(order.stopLossPrice()).thenReturn(new BigDecimal("0.95"));
        when(order.canonicalExecutablePlan()).thenReturn(null);
        return order;
    }

    /** Minimal in-memory stand-in for the JPA repository. */
    private static final class Store {
        private final Map<String, PositionExitStateEntity> rows = new LinkedHashMap<>();
        private final PositionExitStateRepository repository = mock(PositionExitStateRepository.class);

        Store() {
            when(repository.findByUserNameAndPendingOrderId(any(), any())).thenAnswer(invocation ->
                    Optional.ofNullable(rows.get(key(invocation.getArgument(0), invocation.getArgument(1)))));
            when(repository.save(any())).thenAnswer(invocation -> {
                PositionExitStateEntity entity = invocation.getArgument(0);
                rows.put(key(entity.getUserName(), entity.getPendingOrderId()), entity);
                return entity;
            });
        }

        PositionExitStateRepository repository() {
            return repository;
        }

        PositionExitStateEntity single() {
            assertThat(rows).hasSize(1);
            return rows.values().iterator().next();
        }

        private static String key(String user, String order) {
            return user + ":" + order;
        }
    }

    private static final class FakeGateway implements OkxOrderGateway {
        private BigDecimal stopPrice;
        private boolean throwAfterApply;
        private boolean readbackUnavailable;
        private final List<Map<String, String>> amendPayloads = new ArrayList<>();

        FakeGateway(BigDecimal stopPrice) {
            this.stopPrice = stopPrice;
        }

        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode queryAlgoOrder(Map<String, String> payload) {
            if (readbackUnavailable) {
                return null;
            }
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("algoId", "algo-1");
            item.put("algoClOrdId", payload.getOrDefault("algoClOrdId", ""));
            if (stopPrice != null) {
                item.put("slTriggerPx", stopPrice.toPlainString());
            }
            return root;
        }

        @Override
        public JsonNode amendAlgoOrder(Map<String, String> payload, TradingOperationContext context) {
            amendPayloads.add(Map.copyOf(payload));
            stopPrice = new BigDecimal(payload.get("newSlTriggerPx"));
            if (throwAfterApply) {
                throw new IllegalStateException("read timed out after request was sent");
            }
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject().put("algoId", "algo-1").put("sCode", "0");
            return root;
        }
    }
}

package com.example.quant.agent.exit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.lifecycle.AutoTradeLifecycleService;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.config.AgentProperties;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.okxtrade.TradingOperationContext;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import com.example.quant.tradeplan.CanonicalPlanCompiler;
import com.example.quant.tradeplan.TradeIntent;
import com.example.quant.tradeplan.TradePlan;
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

/**
 * End-to-end wiring test: the real lifecycle scheduler drives the real profit
 * protection service against a fake exchange.
 */
class ProfitProtectionLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-09-17T00:00:00Z");
    private static final String INST_ID = "TEST-USDT-SWAP";

    private static final OkxInstrumentRules RULES = new OkxInstrumentRules(
            INST_ID, BigDecimal.ONE, "TEST", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.01"));

    @Test
    void lifecycleMovesTheProtectiveStopAndSurvivesARestart() {
        Store store = new Store();
        AgentProperties properties = new AgentProperties();
        PendingOrder order = pendingOrder();
        FakeExchange exchange = new FakeExchange(new BigDecimal("98"), OkxTradeAdapter.attachedStopLossClientOrderId(order));

        AutoTradeLifecycleService lifecycle = lifecycle(order, exchange, properties, store, "118");
        lifecycle.runOnce(T0.plusSeconds(300));

        assertThat(exchange.amendPayloads).hasSize(1);
        assertThat(exchange.stopPrice).isEqualByComparingTo("112");
        assertThat(store.single().getExitState()).isEqualTo(PositionExitState.TRAILING.name());
        assertThat(store.single().getMfeR()).isEqualByComparingTo("9");

        // Restart: new service instances, same database and same exchange state.
        AutoTradeLifecycleService restarted = lifecycle(order, exchange, properties, store, "118");
        restarted.runOnce(T0.plusSeconds(600));

        assertThat(exchange.amendPayloads).hasSize(1);
        assertThat(store.single().getAppliedProtectiveStop()).isEqualByComparingTo("112");
        assertThat(store.single().getExitState()).isEqualTo(PositionExitState.TRAILING.name());
    }

    @Test
    void canonicalReconciliationDoesNotUndoLockedProfit() {
        Store store = new Store();
        PendingOrder order = pendingOrder();
        FakeExchange exchange = new FakeExchange(new BigDecimal("112"), OkxTradeAdapter.attachedStopLossClientOrderId(order));

        AutoTradeLifecycleService lifecycle = lifecycle(order, exchange, new AgentProperties(), store, "118");
        lifecycle.runOnce(T0.plusSeconds(300));

        // The canonical initial stop is 98. Reconciliation must leave the
        // profit-protected 112 in place and must not place a replacement.
        assertThat(exchange.stopPrice).isEqualByComparingTo("112");
        assertThat(exchange.stopLossPlacements()).isEmpty();
        assertThat(store.single().getAppliedProtectiveStop()).isEqualByComparingTo("112");
    }

    @Test
    void profitProtectionIsSkippedWhenThePositionHasNoConfirmedStop() {
        Store store = new Store();
        PendingOrder order = pendingOrder();
        FakeExchange exchange = new FakeExchange(null, OkxTradeAdapter.attachedStopLossClientOrderId(order));

        lifecycle(order, exchange, new AgentProperties(), store, "118").runOnce(T0.plusSeconds(300));

        // The row is still created so MFE/MAE start being recorded, but nothing
        // is sent to the exchange while no confirmed stop exists.
        assertThat(exchange.amendPayloads).isEmpty();
        assertThat(store.single().getAppliedProtectiveStop()).isNull();
        assertThat(store.single().getMfeR()).isEqualByComparingTo("9");
    }

    private AutoTradeLifecycleService lifecycle(PendingOrder order, FakeExchange exchange,
                                                AgentProperties properties, Store store, String markPx) {
        PendingOrderService pendingOrderService = mock(PendingOrderService.class);
        when(pendingOrderService.allOrders()).thenReturn(List.of(order));
        PositionSnapshotService positions = mock(PositionSnapshotService.class);
        when(positions.positions()).thenReturn(List.of(position(markPx)));

        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                new AutoTradeBudgetService(properties),
                properties,
                exchange,
                positions,
                instId -> RULES,
                null, null, null, null, null);
        service.setPositionExitService(new PositionExitService(store.repository(), exchange, properties));
        return service;
    }

    private static PendingOrder pendingOrder() {
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, plan());
        CanonicalExecutablePlan canonicalPlan = new CanonicalPlanCompiler().compile(
                TradeIntent.fromPendingOrder(order, new BigDecimal("0.30"), new BigDecimal("0.40"),
                        new BigDecimal("0.30"), "exit-lifecycle-v1"),
                RULES);
        assertThat(canonicalPlan.accepted()).as(canonicalPlan.decisions().toString()).isTrue();
        order.setCanonicalExecutablePlan(canonicalPlan);
        order.markSubmitted(T0, "entry-ord-1");
        order.markProtectionSubmitted("TEST_PROTECTION_SUBMITTED");
        return order;
    }

    private static TradePlan plan() {
        return new TradePlan(
                UUID.randomUUID(), INST_ID, TradePlanType.OPEN_LONG, "LIMIT", DirectionBias.BULLISH,
                new BigDecimal("0.9"), new BigDecimal("100"), new BigDecimal("98"), new BigDecimal("106"),
                2, new BigDecimal("10"), new BigDecimal("22.5"), new BigDecimal("100"), new BigDecimal("2"),
                90, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("100000000"), "cross",
                List.of("test"), List.of(), "", true, Instant.now().plusSeconds(120));
    }

    private static PositionSummary position(String markPx) {
        return new PositionSummary(INST_ID, "long", "10", new BigDecimal("100"), new BigDecimal("180"),
                BigDecimal.ZERO, BigDecimal.ZERO, "cross", BigDecimal.valueOf(2), BigDecimal.ZERO,
                new BigDecimal("1180"), BigDecimal.ZERO, "OKX_REAL", "MANAGED", "OWNED",
                new BigDecimal(markPx), new BigDecimal(markPx));
    }

    private static final class Store {
        private final Map<String, PositionExitStateEntity> rows = new LinkedHashMap<>();
        private final PositionExitStateRepository repository = mock(PositionExitStateRepository.class);

        Store() {
            when(repository.findByUserNameAndPendingOrderId(any(), any())).thenAnswer(invocation ->
                    Optional.ofNullable(rows.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))));
            when(repository.save(any())).thenAnswer(invocation -> {
                PositionExitStateEntity entity = invocation.getArgument(0);
                rows.put(entity.getUserName() + ":" + entity.getPendingOrderId(), entity);
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

        Map<String, PositionExitStateEntity> rows() {
            return rows;
        }
    }

    private static final class FakeExchange implements OkxOrderGateway {
        private final String slClientId;
        private BigDecimal stopPrice;
        private final Map<String, Map<String, String>> live = new LinkedHashMap<>();
        private final List<Map<String, String>> amendPayloads = new ArrayList<>();

        FakeExchange(BigDecimal initialStop, String slClientId) {
            this.slClientId = slClientId;
            this.stopPrice = initialStop;
            if (initialStop != null) {
                Map<String, String> sl = new LinkedHashMap<>();
                sl.put("algoClOrdId", slClientId);
                sl.put("instId", INST_ID);
                sl.put("side", "sell");
                sl.put("posSide", "long");
                sl.put("sz", "10");
                sl.put("slTriggerPx", initialStop.toPlainString());
                live.put(slClientId, sl);
            }
        }

        List<Map<String, String>> stopLossPlacements() {
            return live.values().stream()
                    .filter(item -> item.containsKey("slTriggerPx"))
                    .filter(item -> !slClientId.equals(item.get("algoClOrdId")))
                    .toList();
        }

        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode placeAlgoOrder(Map<String, String> payload, TradingOperationContext context) {
            live.put(payload.get("algoClOrdId"), Map.copyOf(payload));
            if (payload.get("algoClOrdId").equals(slClientId)) {
                stopPrice = new BigDecimal(payload.get("slTriggerPx"));
            }
            return new ObjectMapper().createObjectNode().putArray("data").addObject()
                    .put("algoId", "algo-" + payload.get("algoClOrdId"));
        }

        @Override
        public JsonNode currentAlgoOrders(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            var data = root.putArray("data");
            for (Map<String, String> item : live.values()) {
                ObjectNode node = data.addObject()
                        .put("algoClOrdId", item.getOrDefault("algoClOrdId", ""))
                        .put("instId", item.getOrDefault("instId", INST_ID))
                        .put("side", item.getOrDefault("side", "sell"))
                        .put("posSide", item.getOrDefault("posSide", "net"))
                        .put("state", "live")
                        .put("sz", item.getOrDefault("sz", "0"));
                if (item.containsKey("slTriggerPx")) {
                    node.put("slTriggerPx", item.get("slTriggerPx"));
                }
                if (item.containsKey("tpTriggerPx")) {
                    node.put("tpTriggerPx", item.get("tpTriggerPx"));
                }
            }
            return root;
        }

        @Override
        public JsonNode queryAlgoOrder(Map<String, String> payload) {
            String id = payload.get("algoClOrdId");
            if (!slClientId.equals(id) || stopPrice == null) {
                return new ObjectMapper().createObjectNode().putArray("data");
            }
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject()
                    .put("algoId", "algo-1")
                    .put("algoClOrdId", id)
                    .put("slTriggerPx", stopPrice.toPlainString());
            return root;
        }

        @Override
        public JsonNode amendAlgoOrder(Map<String, String> payload, TradingOperationContext context) {
            amendPayloads.add(Map.copyOf(payload));
            stopPrice = new BigDecimal(payload.get("newSlTriggerPx"));
            Map<String, String> updated = new LinkedHashMap<>(live.getOrDefault(slClientId, Map.of()));
            updated.put("slTriggerPx", stopPrice.toPlainString());
            live.put(slClientId, updated);
            return new ObjectMapper().createObjectNode().putArray("data").addObject()
                    .put("algoId", "algo-1").put("sCode", "0");
        }

        @Override
        public JsonNode cancelAlgoOrder(Map<String, String> payload) {
            live.remove(payload.get("algoClOrdId"));
            ObjectNode root = new ObjectMapper().createObjectNode().put("code", "0");
            root.putArray("data").addObject().put("sCode", "0");
            return root;
        }
    }
}

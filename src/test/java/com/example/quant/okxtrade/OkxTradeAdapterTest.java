package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.market.MarketType;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.tradeplan.TradePlanType;
import com.example.quant.tradeplan.CanonicalPlanCompiler;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import com.example.quant.tradeplan.TradeIntent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OkxTradeAdapterTest {
    @Test
    void canonicalProductionPathFailsClosedWhenSnapshotIsMissing() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = testAdapter(gateway);
        adapter.setCanonicalPlanCompiler(new CanonicalPlanCompiler());

        assertThatThrownBy(() -> adapter.placeOrder(order()))
                .isInstanceOf(OrderNotSubmittedException.class)
                .hasMessageContaining("CANONICAL_PLAN_MISSING");
        assertThat(gateway.payload).isNull();
    }

    @Test
    void canonicalEntryAndStopFactsAreSerializedWithoutReinterpretation() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = testAdapter(gateway);
        adapter.setCanonicalPlanCompiler(new CanonicalPlanCompiler());
        PendingOrder order = order();
        OkxInstrumentRules rules = new OkxInstrumentRules("BTC-USDT-SWAP", BigDecimal.ONE, "BTC",
                new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.1"));
        CanonicalExecutablePlan plan = new CanonicalPlanCompiler().compile(
                TradeIntent.fromPendingOrder(order, new BigDecimal("0.3"), new BigDecimal("0.4"),
                        new BigDecimal("0.3"), "strategy-v1"), rules);
        order.setExecutableContractQuantity(plan.entry().executableContractQuantity());
        order.setCanonicalExecutablePlan(plan);

        adapter.placeOrder(order);

        assertThat(gateway.payload).containsEntry("sz", "5").containsEntry("px", "100");
        assertThat(attachedPayload((List<?>) gateway.payload.get("attachAlgoOrds"), 0))
                .containsEntry("slTriggerPx", "98");
    }

    @Test
    void leverageTimeoutIsKnownToOccurBeforeTheOrderRequest() {
        LeverageTimeoutGateway gateway = new LeverageTimeoutGateway();
        OkxTradeAdapter adapter = testAdapter(gateway);

        assertThatThrownBy(() -> adapter.placeOrder(order()))
                .isInstanceOf(OrderNotSubmittedException.class)
                .hasMessageContaining("PRE_ORDER_SETUP_FAILED");
        assertThat(gateway.payload).isNull();
    }

    @Test
    void unknownSubmitRecoveryFallsBackToOrderHistoryAfterCurrentLookupSaysNotFound() {
        HistoryRecoveryGateway gateway = new HistoryRecoveryGateway();
        OkxTradeAdapter adapter = testAdapter(gateway);
        PendingOrder order = order();

        OrderExecutionResult result = adapter.recoverUnknownSubmitStatus(order);

        assertThat(result.submitted()).isTrue();
        assertThat(result.externalOrderId()).isEqualTo("history-order-1");
        assertThat(gateway.queryOrderPayload).containsEntry("clOrdId", order.clientOrderId());
        assertThat(gateway.historyPayload).containsEntry("clOrdId", order.clientOrderId());
    }

    @Test
    void disabledLiveTradingNeverCallsOrderGateway() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = new OkxTradeAdapter(
                gateway,
                null,
                instId -> new OkxInstrumentRules(instId, BigDecimal.ONE, "BTC", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO),
                () -> OkxPositionMode.LONG_SHORT,
                new AgentProperties(),
                new TradingProperties("SEMI_AUTO", true, false, 120, false)
        );

        assertThatThrownBy(() -> adapter.placeOrder(order()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实盘交易已禁用");
        assertThatThrownBy(() -> adapter.closePosition("BTC-USDT-SWAP", "long", "cross"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实盘交易已禁用");
        assertThat(gateway.payload).isNull();
        assertThat(gateway.closePayload).isNull();
    }

    @Test
    void limitOrderSubmitDoesNotSubmitProtectionWhenNoFillIsConfirmed() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway);
        PendingOrder order = new PendingOrder(
                UUID.randomUUID(),
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "buy",
                "long",
                "LIMIT",
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(5),
                2,
                "cross",
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(104),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(2),
                78,
                BigDecimal.valueOf(0.0002),
                BigDecimal.valueOf(0.02),
                BigDecimal.valueOf(25000000),
                UUID.randomUUID(),
                "token",
                Instant.parse("2026-06-14T00:00:00Z"),
                Instant.parse("2026-06-14T00:02:00Z")
        );

        OrderExecutionResult result = adapter.placeOrder(order);

        assertThat(result.executed()).isTrue();
        assertThat(result.submitted()).isTrue();
        assertThat(result.filled()).isFalse();
        assertThat(result.partiallyFilled()).isFalse();
        assertThat(result.unknown()).isFalse();
        assertThat(result.liveOrder()).isTrue();
        assertThat(result.externalOrderId()).isEqualTo("123456");
        assertThat(result.message()).contains("等待成交");
        assertThat(gateway.leveragePayload).containsEntry("lever", "2");
        assertThat(gateway.leveragePayload).containsEntry("mgnMode", "cross");
        assertThat(gateway.payload).containsEntry("instId", "BTC-USDT-SWAP");
        assertThat(gateway.payload).containsEntry("tdMode", "cross");
        assertThat(gateway.payload).containsEntry("side", "buy");
        assertThat(gateway.payload).containsEntry("posSide", "long");
        assertThat(gateway.payload).containsEntry("ordType", "limit");
        assertThat(gateway.payload).containsEntry("px", "100");
        assertThat(gateway.payload).containsEntry("sz", "5");
        assertThat(gateway.payload).containsKey("clOrdId");
        List<?> attached = (List<?>) gateway.payload.get("attachAlgoOrds");
        assertThat(attached).hasSize(1);
        assertThat(attachedPayload(attached, 0))
                .containsEntry("slTriggerPx", "98")
                .containsKey("attachAlgoClOrdId")
                .containsEntry("slOrdPx", "-1");
        assertThat(gateway.algoPayloads).isEmpty();
    }

    @Test
    void normalizesSwapOrderSizeToLotSizeBeforeSubmitting() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway, instId -> new OkxInstrumentRules(
                instId,
                new BigDecimal("1000"),
                "MEW",
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("0.0000001")
        ));
        PendingOrder order = new PendingOrder(
                UUID.randomUUID(),
                MarketType.OKX_SWAP,
                "MEW-USDT-SWAP",
                TradePlanType.OPEN_SHORT,
                "sell",
                "short",
                "LIMIT",
                new BigDecimal("0.0003853"),
                new BigDecimal("242636.28341552"),
                2,
                "cross",
                new BigDecimal("0.0003930"),
                new BigDecimal("0.0003700"),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(2),
                82,
                BigDecimal.valueOf(0.0002),
                BigDecimal.valueOf(0.02),
                BigDecimal.valueOf(25000000),
                UUID.randomUUID(),
                "token",
                Instant.parse("2026-06-14T00:00:00Z"),
                Instant.parse("2026-06-14T00:02:00Z")
        );

        adapter.placeOrder(order);

        assertThat(gateway.payload).containsEntry("sz", "242");
        assertThat(gateway.payload).containsEntry("px", "0.0003853");
        assertThat(gateway.algoPayloads).isEmpty();
    }

    @Test
    void rejectsOrderInsteadOfRoundingRiskBudgetUpToMinimumSize() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway, instId -> new OkxInstrumentRules(
                instId, BigDecimal.ONE, "BTC", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.1")));
        PendingOrder order = marketOrderWithSize(new BigDecimal("0.4"));

        assertThatThrownBy(() -> adapter.placeOrder(order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("低于OKX最小下单量");
        assertThat(gateway.payload).isNull();
    }

    @Test
    void omitsPosSideWhenAccountUsesNetMode() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway, OkxInstrumentRules::defaultFor,
                () -> OkxPositionMode.NET);

        adapter.placeOrder(order());

        assertThat(gateway.leveragePayload).doesNotContainKey("posSide");
        assertThat(gateway.payload).doesNotContainKey("posSide");
        assertThat(gateway.algoPayloads).allSatisfy(payload -> assertThat(payload).doesNotContainKey("posSide"));
    }

    @Test
    void closesLongShortPositionThroughOkxClosePositionEndpoint() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway, OkxInstrumentRules::defaultFor,
                () -> OkxPositionMode.LONG_SHORT);

        OrderExecutionResult result = adapter.closePosition("BTC-USDT-SWAP", "long", "cross");

        assertThat(result.executed()).isTrue();
        assertThat(result.liveOrder()).isTrue();
        assertThat(result.externalOrderId()).isEqualTo("close-123456");
        assertThat(gateway.closePayload).containsEntry("instId", "BTC-USDT-SWAP");
        assertThat(gateway.closePayload).containsEntry("posSide", "long");
        assertThat(gateway.closePayload).containsEntry("mgnMode", "cross");
        assertThat(gateway.closePayload).containsEntry("autoCxl", "true");
    }

    @Test
    void omitsClosePosSideWhenAccountUsesNetMode() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway, OkxInstrumentRules::defaultFor,
                () -> OkxPositionMode.NET);

        adapter.closePosition("ETH-USDT-SWAP", "net", "isolated");

        assertThat(gateway.closePayload).containsEntry("instId", "ETH-USDT-SWAP");
        assertThat(gateway.closePayload).containsEntry("mgnMode", "isolated");
        assertThat(gateway.closePayload).doesNotContainKey("posSide");
    }

    @Test
    void rejectsWhenOkxOrderDataHasFailureCode() {
        RejectingGateway gateway = new RejectingGateway();
        OkxTradeAdapter adapter = testAdapter(gateway);

        assertThatThrownBy(() -> adapter.placeOrder(order()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sCode=51008");
    }

    @Test
    void marketOrderAckRemainsSubmittedUntilOkxConfirmsFill() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = testAdapter(gateway);

        OrderExecutionResult result = adapter.placeOrder(marketOrder());

        assertThat(result.executed()).isTrue();
        assertThat(result.submitted()).isTrue();
        assertThat(result.filled()).isFalse();
        assertThat(result.message()).contains("等待成交确认");
        assertThat(result.externalOrderId()).isEqualTo("123456");
        List<?> attached = (List<?>) gateway.payload.get("attachAlgoOrds");
        assertThat(attached).hasSize(1);
        assertThat(attachedPayload(attached, 0))
                .containsEntry("slTriggerPx", "98")
                .containsKey("attachAlgoClOrdId");
        assertThat(gateway.algoPayloads).isEmpty();
        assertThat(gateway.closePayload).isNull();
    }

    @Test
    void automaticOrderUsesStableClientOrderIdFromPendingOrder() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = testAdapter(gateway);
        PendingOrder order = order();
        order.applyBudgetReservation(new BigDecimal("22.5"), UUID.randomUUID(), "{}", "AUTO_plan1234_order5678");

        adapter.placeOrder(order);

        assertThat(gateway.payload).containsEntry("clOrdId", "AUTO_plan1234_order5678");
    }

    @Test
    void submitTimeoutQueriesOkxByClientOrderIdAndRecoversExistingOrder() {
        TimeoutThenFoundGateway gateway = new TimeoutThenFoundGateway();
        OkxTradeAdapter adapter = testAdapter(gateway);
        PendingOrder order = order();
        order.applyBudgetReservation(new BigDecimal("22.5"), UUID.randomUUID(), "{}", "AUTOrecover123456");

        OrderExecutionResult result = adapter.placeOrder(order);

        assertThat(result.executed()).isTrue();
        assertThat(result.externalOrderId()).isEqualTo("recovered-ord-1");
        assertThat(result.message()).contains("clOrdId");
        assertThat(gateway.queryOrderPayload)
                .containsEntry("instId", "BTC-USDT-SWAP")
                .containsEntry("clOrdId", "AUTOrecover123456");
    }

    @Test
    void submitTimeoutKeepsStatusUnknownWhenClientOrderIdIsNotYetFound() {
        TimeoutThenMissingGateway gateway = new TimeoutThenMissingGateway();
        OkxTradeAdapter adapter = testAdapter(gateway);
        PendingOrder order = order();
        order.applyBudgetReservation(new BigDecimal("22.5"), UUID.randomUUID(), "{}", "AUTOmissing123456");

        assertThatThrownBy(() -> adapter.placeOrder(order))
                .isInstanceOf(com.example.quant.order.OrderSubmitStatusUnknownException.class)
                .hasMessageContaining("OKX request timed out");
        assertThat(gateway.queryOrderPayload).containsEntry("clOrdId", "AUTOmissing123456");
    }

    private static OkxTradeAdapter testAdapter(OkxOrderGateway gateway) {
        return new OkxTradeAdapter(gateway, instId -> new OkxInstrumentRules(
                instId, BigDecimal.ONE, "BTC", new BigDecimal("0.01"),
                new BigDecimal("0.01"), new BigDecimal("0.1")));
    }

    private static PendingOrder order() {
        return new PendingOrder(
                UUID.randomUUID(),
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "buy",
                "long",
                "LIMIT",
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(5),
                2,
                "cross",
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(104),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(2),
                78,
                BigDecimal.valueOf(0.0002),
                BigDecimal.valueOf(0.02),
                BigDecimal.valueOf(25000000),
                UUID.randomUUID(),
                "token",
                Instant.parse("2026-06-14T00:00:00Z"),
                Instant.parse("2026-06-14T00:02:00Z")
        );
    }

    private static PendingOrder marketOrder() {
        return marketOrderWithSize(BigDecimal.valueOf(5));
    }

    private static PendingOrder marketOrderWithSize(BigDecimal size) {
        return new PendingOrder(
                UUID.randomUUID(),
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "buy",
                "long",
                "MARKET",
                BigDecimal.valueOf(100),
                size,
                2,
                "cross",
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(106),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(2),
                88,
                BigDecimal.valueOf(0.0002),
                BigDecimal.valueOf(0.02),
                BigDecimal.valueOf(25000000),
                UUID.randomUUID(),
                "token",
                Instant.parse("2026-06-14T00:00:00Z"),
                Instant.parse("2026-06-14T00:02:00Z")
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> attachedPayload(List<?> attached, int index) {
        return (Map<String, String>) attached.get(index);
    }

    private static class CapturingGateway implements OkxOrderGateway {
        protected Map<String, String> leveragePayload;
        protected Map<String, Object> payload;
        protected Map<String, String> queryOrderPayload;
        protected Map<String, String> closePayload;
        protected final List<Map<String, String>> algoPayloads = new ArrayList<>();
        protected BigDecimal fillAvgPx;
        protected BigDecimal fillSize;

        @Override
        public JsonNode setLeverage(Map<String, String> payload) {
            this.leveragePayload = payload;
            return new ObjectMapper().createObjectNode().putArray("data").addObject();
        }

        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            this.payload = payload;
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject().put("ordId", "123456");
            return root;
        }

        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            this.queryOrderPayload = payload;
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("ordId", payload.get("ordId"));
            item.put("state", "filled");
            item.put("avgPx", fillAvgPx == null ? "" : fillAvgPx.toPlainString());
            item.put("accFillSz", fillSize == null ? "" : fillSize.toPlainString());
            return root;
        }

        @Override
        public JsonNode placeAlgoOrder(Map<String, String> payload) {
            this.algoPayloads.add(payload);
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject().put("algoId", "algo-" + algoPayloads.size());
            return root;
        }

        @Override
        public JsonNode closePosition(Map<String, String> payload) {
            this.closePayload = payload;
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject().put("ordId", "close-123456");
            return root;
        }
    }

    private static class RejectingGateway implements OkxOrderGateway {
        @Override
        public JsonNode setLeverage(Map<String, String> payload) {
            return new ObjectMapper().createObjectNode().putArray("data").addObject();
        }

        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("ordId", "");
            item.put("sCode", "51008");
            item.put("sMsg", "Order failed");
            return root;
        }
    }

    private static class TimeoutThenFoundGateway extends CapturingGateway {
        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            this.payload = payload;
            throw new IllegalStateException("OKX request timed out");
        }

        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            this.queryOrderPayload = payload;
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("ordId", "recovered-ord-1");
            item.put("clOrdId", payload.get("clOrdId"));
            item.put("state", "live");
            return root;
        }
    }

    private static class TimeoutThenMissingGateway extends CapturingGateway {
        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            this.payload = payload;
            throw new IllegalStateException("OKX request timed out");
        }

        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            this.queryOrderPayload = payload;
            return new ObjectMapper().createObjectNode().putArray("data");
        }
    }

    private static class LeverageTimeoutGateway extends CapturingGateway {
        @Override
        public JsonNode setLeverage(Map<String, String> payload) {
            throw new IllegalStateException("OKX request failed: request timed out");
        }
    }

    private static class HistoryRecoveryGateway extends CapturingGateway {
        private Map<String, String> historyPayload;

        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            this.queryOrderPayload = payload;
            throw new IllegalStateException("OKX code=51603, msg=Order does not exist");
        }

        @Override
        public JsonNode currentOrders(Map<String, String> payload) {
            return new ObjectMapper().createObjectNode().putArray("data");
        }

        @Override
        public JsonNode queryOrderHistory(Map<String, String> payload) {
            this.historyPayload = payload;
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject()
                    .put("ordId", "history-order-1")
                    .put("clOrdId", payload.get("clOrdId"))
                    .put("state", "filled")
                    .put("accFillSz", "5");
            return root;
        }
    }
}

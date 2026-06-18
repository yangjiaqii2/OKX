package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.market.MarketType;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.tradeplan.TradePlanType;
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
    void placesRealOkxOrderThroughGateway() {
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
        assertThat(result.liveOrder()).isTrue();
        assertThat(result.externalOrderId()).isEqualTo("123456");
        assertThat(result.message()).contains("保护单已提交 4 个");
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
        assertThat(gateway.algoPayloads).hasSize(4);
        assertThat(gateway.algoPayloads).allSatisfy(payload -> {
            assertThat(payload).containsEntry("reduceOnly", "true");
            assertThat(payload).containsKey("algoClOrdId");
            assertThat(payload).doesNotContainKey("orderRole");
        });
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

        assertThat(gateway.payload).containsEntry("sz", "243");
        assertThat(gateway.payload).containsEntry("px", "0.0003853");
        assertThat(gateway.algoPayloads)
                .extracting(payload -> payload.get("sz"))
                .containsExactly("243", "72", "97", "74");
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
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway);

        assertThatThrownBy(() -> adapter.placeOrder(order()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sCode=51008");
    }

    @Test
    void marketOrderProtectionUsesActualAverageFillPrice() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.fillAvgPx = new BigDecimal("100.2");
        gateway.fillSize = new BigDecimal("5");
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway);

        adapter.placeOrder(marketOrder());

        assertThat(gateway.queryOrderPayload).containsEntry("ordId", "123456");
        assertThat(gateway.algoPayloads).hasSize(4);
        assertThat(gateway.algoPayloads.get(0)).containsEntry("slTriggerPx", "98");
        assertThat(gateway.algoPayloads.get(1)).containsEntry("tpTriggerPx", "102.4");
        assertThat(gateway.algoPayloads.get(2)).containsEntry("tpTriggerPx", "104.6");
        assertThat(gateway.algoPayloads.get(3)).containsEntry("tpTriggerPx", "106.8");
        assertThat(gateway.algoPayloads)
                .extracting(payload -> payload.get("sz"))
                .containsExactly("5", "1", "2", "2");
    }

    @Test
    void marketOrderFillDeviationTooLargeClosesPositionInsteadOfSubmittingProtection() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.fillAvgPx = new BigDecimal("101");
        gateway.fillSize = new BigDecimal("5");
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway);

        OrderExecutionResult result = adapter.placeOrder(marketOrder());

        assertThat(result.executed()).isTrue();
        assertThat(gateway.algoPayloads).isEmpty();
        assertThat(gateway.closePayload).containsEntry("instId", "BTC-USDT-SWAP");
        assertThat(result.message()).contains("保护单");
    }

    @Test
    void smallMarketFillMergesTinyTakeProfitLegsIntoFinalRemainingLeg() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.fillAvgPx = new BigDecimal("100");
        gateway.fillSize = new BigDecimal("2");
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway);

        adapter.placeOrder(marketOrderWithSize(new BigDecimal("2")));

        assertThat(gateway.algoPayloads).hasSize(2);
        assertThat(gateway.algoPayloads.get(0)).containsEntry("sz", "2");
        assertThat(gateway.algoPayloads.get(0)).containsEntry("reduceOnly", "true");
        assertThat(gateway.algoPayloads.get(1)).containsEntry("sz", "2");
        assertThat(gateway.algoPayloads.get(1)).containsEntry("reduceOnly", "true");
    }

    @Test
    void automaticOrderUsesStableClientOrderIdFromPendingOrder() {
        CapturingGateway gateway = new CapturingGateway();
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway);
        PendingOrder order = order();
        order.applyBudgetReservation(new BigDecimal("22.5"), UUID.randomUUID(), "{}", "AUTO_plan1234_order5678");

        adapter.placeOrder(order);

        assertThat(gateway.payload).containsEntry("clOrdId", "AUTO_plan1234_order5678");
    }

    @Test
    void submitTimeoutQueriesOkxByClientOrderIdAndRecoversExistingOrder() {
        TimeoutThenFoundGateway gateway = new TimeoutThenFoundGateway();
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway);
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
    void submitTimeoutReturnsRejectedWhenOkxConfirmsClientOrderIdMissing() {
        TimeoutThenMissingGateway gateway = new TimeoutThenMissingGateway();
        OkxTradeAdapter adapter = new OkxTradeAdapter(gateway);
        PendingOrder order = order();
        order.applyBudgetReservation(new BigDecimal("22.5"), UUID.randomUUID(), "{}", "AUTOmissing123456");

        OrderExecutionResult result = adapter.placeOrder(order);

        assertThat(result.executed()).isFalse();
        assertThat(result.message()).contains("OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT");
        assertThat(gateway.queryOrderPayload).containsEntry("clOrdId", "AUTOmissing123456");
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

    private static class CapturingGateway implements OkxOrderGateway {
        protected Map<String, String> leveragePayload;
        protected Map<String, String> payload;
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
        public JsonNode placeOrder(Map<String, String> payload) {
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
        public JsonNode placeOrder(Map<String, String> payload) {
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
        public JsonNode placeOrder(Map<String, String> payload) {
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
        public JsonNode placeOrder(Map<String, String> payload) {
            this.payload = payload;
            throw new IllegalStateException("OKX request timed out");
        }

        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            this.queryOrderPayload = payload;
            return new ObjectMapper().createObjectNode().putArray("data");
        }
    }
}

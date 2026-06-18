package com.example.quant.agent.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetAllocationRequest;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.budget.BudgetReservationStatus;
import com.example.quant.config.AgentProperties;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.tradeplan.TradePlan;
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

class AutoTradeLifecycleServiceTest {

    @Test
    void cancelsUnfilledEntryAfterConfiguredTimeoutAndReleasesBudget() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "live";
        gateway.accFillSz = BigDecimal.ZERO;
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:11:00Z"));

        assertThat(result.entryTimeoutCancelled()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.ENTRY_TIMEOUT_CANCELLED);
        assertThat(gateway.cancelPayload).containsEntry("ordId", "entry-ord-1");
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RELEASED);
    }

    @Test
    void partialFillCancelsRemainderAndSubmitsProtectionForActualFill() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        gateway.orderState = "partially_filled";
        gateway.accFillSz = new BigDecimal("10");
        gateway.avgPx = new BigDecimal("100");
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T00:11:00Z"));

        assertThat(result.partialFillProtected()).isEqualTo(1);
        assertThat(gateway.cancelPayload).containsEntry("ordId", "entry-ord-1");
        assertThat(gateway.algoPayloads).hasSize(4);
        assertThat(gateway.algoPayloads.get(0)).containsEntry("reduceOnly", "true");
        assertThat(gateway.algoPayloads.get(0)).containsEntry("sz", "10");
        assertThat(gateway.algoPayloads.get(0)).containsEntry("slTriggerPx", "98");
        assertThat(gateway.algoPayloads.get(1)).containsEntry("sz", "3").containsEntry("tpTriggerPx", "102");
        assertThat(gateway.algoPayloads.get(2)).containsEntry("sz", "4").containsEntry("tpTriggerPx", "104");
        assertThat(gateway.algoPayloads.get(3)).containsEntry("sz", "3").containsEntry("tpTriggerPx", "106");
        assertThat(order.status()).isEqualTo(OrderStatus.PROTECTION_SUBMITTED);
    }

    @Test
    void sidewaysLongPositionTightensTakeProfitToSmallProfitExit() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "2", "100", "5", "1000")))
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T03:01:00Z"));

        assertThat(result.sidewaysTpAdjusted()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED);
        assertThat(gateway.algoPayloads.get(0)).containsEntry("tpTriggerPx", "100.3");
    }

    @Test
    void sidewaysShortPositionTightensTakeProfitBelowEntryPrice() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("ETH-USDT-SWAP", TradePlanType.OPEN_SHORT));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-2");
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("ETH-USDT-SWAP", "short", "2", "100", "5", "1000")))
        );

        service.runOnce(Instant.parse("2026-06-18T03:01:00Z"));

        assertThat(gateway.algoPayloads.get(0)).containsEntry("tpTriggerPx", "99.7");
    }

    @Test
    void maxHoldTimeoutSubmitsClosePositionAndKeepsBudgetUntilClosedSync() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder order = autoOrder(pendingOrderService, budgetService, samplePlan("BTC-USDT-SWAP", TradePlanType.OPEN_LONG));
        order.markSubmitted(Instant.parse("2026-06-18T00:00:00Z"), "entry-ord-1");
        budgetService.markUsed(order.budgetReservationId());
        LifecycleGateway gateway = new LifecycleGateway();
        AutoTradeLifecycleService service = new AutoTradeLifecycleService(
                pendingOrderService,
                budgetService,
                properties,
                gateway,
                new FixedPositionSnapshotService(List.of(position("BTC-USDT-SWAP", "long", "2", "100", "20", "1000")))
        );

        AutoTradeLifecycleService.LifecycleRunResult result = service.runOnce(Instant.parse("2026-06-18T08:01:00Z"));

        assertThat(result.maxHoldCloseSubmitted()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSE_SUBMITTED);
        assertThat(gateway.closePayload).containsEntry("instId", "BTC-USDT-SWAP");
        assertThat(budgetService.reservation(order.budgetReservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
    }

    private static PendingOrder autoOrder(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                          TradePlan plan) {
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
        return pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                plan,
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                reservation.reservationId(),
                allocation,
                "AUTO" + pendingOrderId.toString().replace("-", "").substring(0, 12)
        );
    }

    private static PositionSummary position(String instId, String posSide, String size, String avgPx,
                                            String upl, String notionalUsd) {
        return new PositionSummary(
                instId,
                posSide,
                size,
                new BigDecimal(avgPx),
                new BigDecimal(upl),
                BigDecimal.TEN,
                BigDecimal.TEN,
                "cross",
                BigDecimal.valueOf(2),
                BigDecimal.ZERO,
                new BigDecimal(notionalUsd),
                BigDecimal.ZERO,
                "OKX_REAL"
        );
    }

    private static TradePlan samplePlan(String instId, TradePlanType action) {
        boolean shortSide = action == TradePlanType.OPEN_SHORT;
        return new TradePlan(
                UUID.randomUUID(),
                instId,
                action,
                "LIMIT",
                shortSide ? DirectionBias.BEARISH : DirectionBias.BULLISH,
                new BigDecimal("0.9"),
                new BigDecimal("100"),
                shortSide ? new BigDecimal("102") : new BigDecimal("98"),
                shortSide ? new BigDecimal("94") : new BigDecimal("106"),
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
            this.positions = positions;
        }

        @Override
        public List<PositionSummary> positions() {
            return positions;
        }
    }

    private static class LifecycleGateway implements OkxOrderGateway {
        private String orderState = "live";
        private BigDecimal accFillSz = BigDecimal.ZERO;
        private BigDecimal avgPx = BigDecimal.ZERO;
        private Map<String, String> cancelPayload;
        private Map<String, String> closePayload;
        private final List<Map<String, String>> algoPayloads = new ArrayList<>();

        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("ordId", payload.getOrDefault("ordId", "entry-ord-1"));
            item.put("state", orderState);
            item.put("accFillSz", accFillSz.toPlainString());
            item.put("avgPx", avgPx.toPlainString());
            return root;
        }

        @Override
        public JsonNode cancelOrder(Map<String, String> payload) {
            this.cancelPayload = payload;
            return new ObjectMapper().createObjectNode().putArray("data").addObject().put("ordId", payload.get("ordId"));
        }

        @Override
        public JsonNode placeAlgoOrder(Map<String, String> payload) {
            this.algoPayloads.add(payload);
            return new ObjectMapper().createObjectNode().putArray("data").addObject().put("algoId", "algo-1");
        }

        @Override
        public JsonNode closePosition(Map<String, String> payload) {
            this.closePayload = payload;
            return new ObjectMapper().createObjectNode().putArray("data").addObject().put("ordId", "close-1");
        }
    }
}

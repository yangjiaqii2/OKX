package com.example.quant.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.account.ClosePositionRecoveryService;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetAllocationRequest;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.budget.BudgetReservationStatus;
import com.example.quant.config.AgentProperties;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutoTradeRecoveryTaskTest {

    @Test
    void recoversUnknownSubmitStatusByClientOrderIdWhenOkxOrderExists() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        BudgetAllocation allocation = budgetService.allocate(request());
        UUID pendingOrderId = UUID.randomUUID();
        BudgetReservation reservation = budgetService.reserveBudget(
                samplePlan().id(),
                pendingOrderId,
                "BTC-USDT-SWAP",
                allocation.finalOrderMarginUsdt(),
                new BigDecimal("50")
        );
        PendingOrder order = pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                samplePlan(),
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                reservation.reservationId(),
                allocation,
                "AUTOunknownexists"
        );
        order.markUnknownSubmitStatus("OKX submit timeout");

        AutoTradeRecoveryTask task = new AutoTradeRecoveryTask(
                pendingOrderService,
                budgetService,
                properties,
                new OkxTradeAdapter(new QueryExistingOrderGateway())
        );
        AutoTradeRecoveryTask.RecoveryResult result = task.runOnce();

        assertThat(result.recoveredUnknownSubmits()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(order.externalOrderId()).isEqualTo("recovered-ord-1");
        assertThat(budgetService.reservation(reservation.reservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.USED);
    }

    @Test
    void releasesUnknownSubmitBudgetOnlyWhenOkxConfirmsClientOrderIdMissing() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        BudgetAllocation allocation = budgetService.allocate(request());
        UUID pendingOrderId = UUID.randomUUID();
        BudgetReservation reservation = budgetService.reserveBudget(
                samplePlan().id(),
                pendingOrderId,
                "BTC-USDT-SWAP",
                allocation.finalOrderMarginUsdt(),
                new BigDecimal("50")
        );
        PendingOrder order = pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                samplePlan(),
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                reservation.reservationId(),
                allocation,
                "AUTOunknownmissing"
        );
        order.markUnknownSubmitStatus("OKX submit timeout");

        AutoTradeRecoveryTask task = new AutoTradeRecoveryTask(
                pendingOrderService,
                budgetService,
                properties,
                new OkxTradeAdapter(new QueryMissingOrderGateway())
        );
        AutoTradeRecoveryTask.RecoveryResult result = task.runOnce();

        assertThat(result.releasedReservations()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.rejectReason()).contains("OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT");
        assertThat(budgetService.reservation(reservation.reservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RELEASED);
    }

    @Test
    void releasesReservedBudgetForFailedPendingOrder() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        BudgetAllocation allocation = budgetService.allocate(request());
        UUID pendingOrderId = UUID.randomUUID();
        BudgetReservation reservation = budgetService.reserveBudget(
                samplePlan().id(),
                pendingOrderId,
                "BTC-USDT-SWAP",
                allocation.finalOrderMarginUsdt(),
                new BigDecimal("50")
        );
        PendingOrder order = pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                samplePlan(),
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                reservation.reservationId(),
                allocation,
                "AUTO_plan_pending_test"
        );
        order.markRejected("OKX拒绝");

        AutoTradeRecoveryTask task = new AutoTradeRecoveryTask(pendingOrderService, budgetService, properties);
        AutoTradeRecoveryTask.RecoveryResult result = task.runOnce();

        assertThat(result.releasedReservations()).isEqualTo(1);
        assertThat(budgetService.reservation(reservation.reservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RELEASED);
    }

    @Test
    void marksExpiredReservedPendingOrderAndReleasesBudget() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(0);
        BudgetAllocation allocation = budgetService.allocate(request());
        UUID pendingOrderId = UUID.randomUUID();
        BudgetReservation reservation = budgetService.reserveBudget(
                samplePlan().id(),
                pendingOrderId,
                "ETH-USDT-SWAP",
                allocation.finalOrderMarginUsdt(),
                new BigDecimal("50")
        );
        PendingOrder order = pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                samplePlan(),
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                reservation.reservationId(),
                allocation,
                "AUTO_plan_pending_expired"
        );

        AutoTradeRecoveryTask task = new AutoTradeRecoveryTask(pendingOrderService, budgetService, properties);
        AutoTradeRecoveryTask.RecoveryResult result = task.runOnce();

        assertThat(result.expiredOrders()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(budgetService.reservation(reservation.reservationId()))
                .get()
                .extracting(BudgetReservation::status)
                .isEqualTo(BudgetReservationStatus.RELEASED);
    }

    @Test
    void invokesClosePositionRecoveryDuringScheduledRecovery() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        CountingCloseRecovery closeRecovery = new CountingCloseRecovery();
        AutoTradeRecoveryTask task = new AutoTradeRecoveryTask(
                pendingOrderService,
                budgetService,
                properties,
                null,
                null,
                closeRecovery
        );

        task.runOnce();

        assertThat(closeRecovery.calls).isEqualTo(1);
    }

    private static BudgetAllocationRequest request() {
        return new BudgetAllocationRequest(
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
        );
    }

    private static TradePlan samplePlan() {
        return new TradePlan(
                UUID.randomUUID(),
                "BTC-USDT-SWAP",
                TradePlanType.OPEN_LONG,
                "MARKET",
                DirectionBias.BULLISH,
                new BigDecimal("0.9"),
                new BigDecimal("100"),
                new BigDecimal("98"),
                new BigDecimal("104"),
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

    private static class QueryExistingOrderGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            ObjectNode item = root.putArray("data").addObject();
            item.put("ordId", "recovered-ord-1");
            item.put("clOrdId", payload.get("clOrdId"));
            item.put("state", "live");
            return root;
        }
    }

    private static class QueryMissingOrderGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            return new ObjectMapper().createObjectNode().putArray("data");
        }
    }

    private static class CountingCloseRecovery extends ClosePositionRecoveryService {
        private int calls;

        CountingCloseRecovery() {
            super(null, null, null, null, null);
        }

        @Override
        public CloseRecoveryResult runOnce(Instant now) {
            calls++;
            return new CloseRecoveryResult(0);
        }
    }
}

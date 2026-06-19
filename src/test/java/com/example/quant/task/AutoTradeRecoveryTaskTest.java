package com.example.quant.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.account.ClosePositionRecoveryService;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetAllocationRequest;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.budget.BudgetReservationStatus;
import com.example.quant.agent.lifecycle.AutoTradeLifecycleService;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxCurrentOrderSyncService;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import com.example.quant.system.SystemControlService;
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
    void scheduledRecoveryRunsAsAutoTradeOwnerForBudgetAndOkxContext() {
        AgentProperties properties = new AgentProperties();
        ContextCapturingBudgetService budgetService = new ContextCapturingBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        AuthUserContext.callAs("userA", () -> systemControlService.enableAutoTrade(new BigDecimal("50")));
        BudgetAllocation allocation = AuthUserContext.callAs("userA", () -> budgetService.allocate(request()));
        UUID pendingOrderId = UUID.randomUUID();
        BudgetReservation ownerReservation = AuthUserContext.callAs("userA", () -> budgetService.reserveBudget(
                samplePlan().id(),
                pendingOrderId,
                "BTC-USDT-SWAP",
                allocation.finalOrderMarginUsdt(),
                new BigDecimal("50")
        ));
        BudgetReservation localAdminReservation = budgetService.reserveBudget(
                samplePlan().id(),
                UUID.randomUUID(),
                "LOCAL-USDT-SWAP",
                new BigDecimal("1"),
                new BigDecimal("50")
        );
        PendingOrder order = pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                samplePlan(),
                pendingOrderId,
                allocation.finalOrderMarginUsdt(),
                ownerReservation.reservationId(),
                allocation,
                "AUTOownerctx"
        );
        order.markUnknownSubmitStatus("OKX submit timeout");
        ContextCapturingOkxTradeAdapter okxTradeAdapter = new ContextCapturingOkxTradeAdapter();
        ContextCapturingLifecycle lifecycleService = new ContextCapturingLifecycle();
        ContextCapturingCloseRecovery closeRecovery = new ContextCapturingCloseRecovery();
        ContextCapturingCurrentOrderSync syncService = new ContextCapturingCurrentOrderSync();
        AutoTradeRecoveryTask task = new AutoTradeRecoveryTask(
                pendingOrderService,
                budgetService,
                properties,
                okxTradeAdapter,
                lifecycleService,
                closeRecovery,
                syncService,
                systemControlService
        );

        AutoTradeRecoveryTask.RecoveryResult result = task.runOnce();

        assertThat(result.recoveredUnknownSubmits()).isEqualTo(1);
        assertThat(okxTradeAdapter.username).isEqualTo("userA");
        assertThat(syncService.username).isEqualTo("userA");
        assertThat(lifecycleService.username).isEqualTo("userA");
        assertThat(closeRecovery.username).isEqualTo("userA");
        assertThat(budgetService.markUsedUsername).isEqualTo("userA");
        assertThat(budgetService.reservation(ownerReservation.reservationId()).orElseThrow().status())
                .isEqualTo(BudgetReservationStatus.USED);
        assertThat(budgetService.reservation(localAdminReservation.reservationId()).orElseThrow().status())
                .isEqualTo(BudgetReservationStatus.RESERVED);
    }

    @Test
    void skipsRecoveryWhenOwnerIsMissing() {
        AgentProperties properties = new AgentProperties();
        ContextCapturingBudgetService budgetService = new ContextCapturingBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        MissingOwnerSystemControlService systemControlService = new MissingOwnerSystemControlService();
        ContextCapturingCurrentOrderSync syncService = new ContextCapturingCurrentOrderSync();
        AutoTradeRecoveryTask task = new AutoTradeRecoveryTask(
                pendingOrderService,
                budgetService,
                properties,
                null,
                null,
                null,
                syncService,
                systemControlService
        );

        AutoTradeRecoveryTask.RecoveryResult result = task.runOnce();

        assertThat(result.attentionRequired()).isEqualTo(1);
        assertThat(syncService.calls).isZero();
    }

    @Test
    void recoversUnknownSubmitStatusByClientOrderIdWhenOkxOrderExistsButNotFilled() {
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
                .isEqualTo(BudgetReservationStatus.RESERVED);
    }

    @Test
    void marksUnknownSubmitBudgetUsedOnlyWhenRecoveredOrderIsFilled() {
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
                "AUTOunknownfilled"
        );
        order.markUnknownSubmitStatus("OKX submit timeout");

        AutoTradeRecoveryTask task = new AutoTradeRecoveryTask(
                pendingOrderService,
                budgetService,
                properties,
                new OkxTradeAdapter(new QueryExistingOrderGateway("filled", "0.45"))
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
        properties.recovery().setUnknownSubmitStatusTimeoutSeconds(0);
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

    @Test
    void invokesCurrentOrderSyncDuringScheduledRecovery() {
        AgentProperties properties = new AgentProperties();
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(properties);
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        CountingCurrentOrderSync syncService = new CountingCurrentOrderSync();
        AutoTradeRecoveryTask task = new AutoTradeRecoveryTask(
                pendingOrderService,
                budgetService,
                properties,
                null,
                null,
                null,
                syncService
        );

        task.runOnce();

        assertThat(syncService.calls).isEqualTo(1);
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

    private static TradingProperties tradingProperties() {
        return new TradingProperties("SEMI_AUTO", true, true, 120, false);
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse("local-admin");
    }

    private static class MissingOwnerSystemControlService extends SystemControlService {
        MissingOwnerSystemControlService() {
            super(tradingProperties());
        }

        @Override
        public String autoTradeOwnerUsername() {
            return "";
        }
    }

    private static class ContextCapturingBudgetService extends AutoTradeBudgetService {
        private String markUsedUsername;

        ContextCapturingBudgetService(AgentProperties agentProperties) {
            super(agentProperties);
        }

        @Override
        public synchronized BudgetReservation markUsed(UUID reservationId) {
            markUsedUsername = currentUsername();
            return super.markUsed(reservationId);
        }
    }

    private static class ContextCapturingOkxTradeAdapter extends OkxTradeAdapter {
        private String username;

        ContextCapturingOkxTradeAdapter() {
            super(new QueryExistingOrderGateway());
        }

        @Override
        public OrderExecutionResult recoverUnknownSubmitStatus(PendingOrder order) {
            username = currentUsername();
            return new OrderExecutionResult(true, true, "owner-okx-order", "recovered");
        }
    }

    private static class ContextCapturingLifecycle extends AutoTradeLifecycleService {
        private String username;

        ContextCapturingLifecycle() {
            super(null, null, new AgentProperties(), null, null);
        }

        @Override
        public LifecycleRunResult runOnce(Instant now) {
            username = currentUsername();
            return new LifecycleRunResult(0, 0, 0, 0, 0, 0);
        }
    }

    private static class ContextCapturingCloseRecovery extends ClosePositionRecoveryService {
        private String username;

        ContextCapturingCloseRecovery() {
            super(null, null, null, null, null);
        }

        @Override
        public CloseRecoveryResult runOnce(Instant now) {
            username = currentUsername();
            return new CloseRecoveryResult(0);
        }
    }

    private static class QueryExistingOrderGateway implements OkxOrderGateway {
        private final String state;
        private final String accFillSz;

        QueryExistingOrderGateway() {
            this("live", "0");
        }

        QueryExistingOrderGateway(String state, String accFillSz) {
            this.state = state;
            this.accFillSz = accFillSz;
        }

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
            item.put("state", state);
            item.put("accFillSz", accFillSz);
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

    private static class CountingCurrentOrderSync extends OkxCurrentOrderSyncService {
        protected int calls;

        CountingCurrentOrderSync() {
            super(null, null);
        }

        @Override
        public SyncResult syncOnce() {
            calls++;
            return new SyncResult(0, 0, 0, false, null, List.of());
        }
    }

    private static class ContextCapturingCurrentOrderSync extends CountingCurrentOrderSync {
        private String username;

        @Override
        public SyncResult syncOnce() {
            username = currentUsername();
            return super.syncOnce();
        }
    }
}

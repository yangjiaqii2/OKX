package com.example.quant.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.config.AgentProperties;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.risk.ContractRiskRequest;
import com.example.quant.risk.RiskCheckResult;
import com.example.quant.risk.RiskLevel;
import com.example.quant.risk.RiskService;
import com.example.quant.risk.ContractRiskService;
import com.example.quant.config.TradingProperties;
import com.example.quant.system.AutoTradeRiskMode;
import com.example.quant.system.SystemControlService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderConfirmServiceTest {

    @Test
    void expiredPendingOrderCannotBeConfirmed() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(1, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        Clock later = Clock.offset(clock, Duration.ofSeconds(2));
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                new OkxTradeAdapter(new SuccessfulGateway()),
                later
        );

        OrderExecutionResult result = confirmService.confirm(order.id(), BigDecimal.TEN, ContractRiskRequest.safeDefaults());

        assertThat(result.executed()).isFalse();
        assertThat(result.message()).contains("过期");
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void confirmationPlacesRealOrder() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                new OkxTradeAdapter(new SuccessfulGateway()),
                clock
        );

        OrderExecutionResult result = confirmService.confirm(order.id(), BigDecimal.TEN, ContractRiskRequest.safeDefaults());

        assertThat(result.executed()).isTrue();
        assertThat(result.liveOrder()).isTrue();
        assertThat(result.externalOrderId()).isEqualTo("okx-order-1");
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(pendingOrderService.get(order.id()).externalOrderId()).isEqualTo("okx-order-1");
        assertThat(pendingOrderService.get(order.id()).submittedAt()).isEqualTo(clock.instant());
    }

    @Test
    void confirmationBuildsRiskRequestFromPendingOrderAndMargin() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        CapturingRiskService riskService = new CapturingRiskService();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                riskService,
                new OkxTradeAdapter(new SuccessfulGateway()),
                clock
        );

        OrderExecutionResult result = confirmService.confirm(order.id(), BigDecimal.valueOf(75));

        assertThat(result.executed()).isTrue();
        assertThat(riskService.captured).isNotNull();
        assertThat(riskService.captured.leverage()).isEqualTo(order.leverage());
        assertThat(riskService.captured.entryPrice()).isEqualByComparingTo(order.price());
        assertThat(riskService.captured.stopLossPrice()).isEqualByComparingTo(order.stopLossPrice());
        assertThat(riskService.captured.riskRewardRatio()).isEqualByComparingTo(order.riskRewardRatio());
        assertThat(riskService.captured.suggestedMargin()).isEqualByComparingTo("75");
        assertThat(riskService.captured.signalScore()).isEqualTo(order.signalScore());
        assertThat(riskService.captured.volatility()).isEqualByComparingTo(order.volatility());
        assertThat(riskService.captured.fundingRate()).isEqualByComparingTo(order.fundingRate());
        assertThat(riskService.captured.volume24h()).isEqualByComparingTo(order.volume24h());
    }

    @Test
    void autoConfirmationLowersLeverageWhenDynamicRiskCapRejects() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        AutoLowerLeverageRiskService riskService = new AutoLowerLeverageRiskService();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                riskService,
                new OkxTradeAdapter(new SuccessfulGateway()),
                clock
        );

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), BigDecimal.valueOf(75));

        assertThat(result.executed()).isTrue();
        assertThat(riskService.calls).isEqualTo(2);
        assertThat(riskService.firstLeverage).isEqualTo(2);
        assertThat(riskService.secondLeverage).isEqualTo(1);
        assertThat(order.leverage()).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void liquidityCheckAllowsSmallOrdersBelowOldFixedDepthThreshold() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                new OkxTradeAdapter(new SuccessfulGateway()),
                new FixedLiquidityService(depth(BigDecimal.valueOf(500))),
                clock
        );

        OrderExecutionResult result = confirmService.confirm(order.id(), BigDecimal.valueOf(20), ContractRiskRequest.safeDefaults());

        assertThat(result.executed()).isTrue();
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void autoConfirmationKeepsConfiguredMarginWhenDepthIsThin() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                new OkxTradeAdapter(new SuccessfulGateway()),
                new FixedLiquidityService(depth(BigDecimal.valueOf(50))),
                clock
        );

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), BigDecimal.valueOf(20));

        assertThat(result.executed()).isTrue();
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(pendingOrderService.get(order.id()).marginAmount()).isEqualByComparingTo("20.0000");
    }

    @Test
    void noRiskAutoConfirmationStillRejectsExtremeSpreadButBypassesRuleRisk() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.NO_RISK, 70);
        RejectingRiskService riskService = new RejectingRiskService();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                riskService,
                new OkxTradeAdapter(new SuccessfulGateway()),
                new FixedLiquidityService(spread(BigDecimal.valueOf(200))),
                new AgentProperties(),
                null,
                systemControlService,
                null,
                null,
                clock
        );

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), BigDecimal.valueOf(20));

        assertThat(result.executed()).isFalse();
        assertThat(result.message()).contains("spread_bps_above");
        assertThat(riskService.calls).isZero();
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void autoConfirmationRaisesAiPlanLeverageToUserMinimumBeforeSubmit() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.NO_RISK, 70, 5);
        LeverageCapturingGateway gateway = new LeverageCapturingGateway();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new RejectingRiskService(),
                new OkxTradeAdapter(gateway),
                new FixedLiquidityService(spread(BigDecimal.valueOf(2))),
                new AgentProperties(),
                null,
                systemControlService,
                null,
                null,
                clock
        );

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), BigDecimal.valueOf(20));

        assertThat(result.executed()).isTrue();
        assertThat(order.leverage()).isEqualTo(5);
        assertThat(order.size()).isEqualByComparingTo("1.00000000");
        assertThat(gateway.leveragePayload).containsEntry("lever", "5");
    }

    @Test
    void noRiskAutoConfirmationStillRejectsEmergencyStop() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.NO_RISK, 70);
        systemControlService.emergencyStop();
        CountingGateway gateway = new CountingGateway();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new RejectingRiskService(),
                new OkxTradeAdapter(gateway),
                new FixedLiquidityService(spread(BigDecimal.valueOf(200))),
                new AgentProperties(),
                null,
                systemControlService,
                null,
                null,
                clock
        );

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), BigDecimal.valueOf(20));

        assertThat(result.executed()).isFalse();
        assertThat(result.message()).contains("紧急停止");
        assertThat(gateway.placeOrderCalls).isZero();
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void manualConfirmationRiskRejectKeepsOrderConfirmableForRetry() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        RejectOnceRiskService riskService = new RejectOnceRiskService();
        CountingGateway gateway = new CountingGateway();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                riskService,
                new OkxTradeAdapter(gateway),
                new FixedLiquidityService(depth(BigDecimal.valueOf(500))),
                clock
        );

        OrderExecutionResult first = confirmService.confirm(order.id(), BigDecimal.valueOf(20), ContractRiskRequest.safeDefaults());
        OrderExecutionResult second = confirmService.confirm(order.id(), BigDecimal.valueOf(10), ContractRiskRequest.safeDefaults());

        assertThat(first.executed()).isFalse();
        assertThat(first.message()).contains("test retryable risk rejection");
        assertThat(second.executed()).isTrue();
        assertThat(gateway.placeOrderCalls).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void autoConfirmationUsesReservedOrderMarginInsteadOfPassedTotalBudget() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        UUID pendingOrderId = UUID.randomUUID();
        BudgetReservation reservation = budgetService.reserveBudget(
                PendingOrderServiceTest.samplePlan().id(),
                pendingOrderId,
                "BTC-USDT-SWAP",
                new BigDecimal("22.5"),
                new BigDecimal("50")
        );
        PendingOrder order = pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                PendingOrderServiceTest.samplePlan(),
                pendingOrderId,
                new BigDecimal("22.5"),
                reservation.reservationId(),
                allocation("22.5"),
                "AUTO_plan_order"
        );
        CapturingRiskService riskService = new CapturingRiskService();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                riskService,
                new OkxTradeAdapter(new SuccessfulGateway()),
                null,
                new AgentProperties(),
                null,
                null,
                null,
                budgetService,
                clock
        );

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), new BigDecimal("50"));

        assertThat(result.executed()).isTrue();
        assertThat(riskService.captured.suggestedMargin()).isEqualByComparingTo("22.5000");
        assertThat(order.marginAmount()).isEqualByComparingTo("22.5000");
        assertThat(budgetService.reservation(reservation.reservationId()).orElseThrow().status().name()).isEqualTo("USED");
    }

    @Test
    void autoConfirmationCanSubmitPendingOrderOnlyOnce() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        UUID pendingOrderId = UUID.randomUUID();
        BudgetReservation reservation = budgetService.reserveBudget(
                PendingOrderServiceTest.samplePlan().id(),
                pendingOrderId,
                "BTC-USDT-SWAP",
                new BigDecimal("22.5"),
                new BigDecimal("50")
        );
        PendingOrder order = pendingOrderService.createAutoPendingOrder(
                MarketType.OKX_SWAP,
                PendingOrderServiceTest.samplePlan(),
                pendingOrderId,
                new BigDecimal("22.5"),
                reservation.reservationId(),
                allocation("22.5"),
                "AUTO_plan_order"
        );
        CountingGateway gateway = new CountingGateway();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                new OkxTradeAdapter(gateway),
                null,
                new AgentProperties(),
                null,
                null,
                null,
                budgetService,
                clock
        );

        OrderExecutionResult first = confirmService.confirmAuto(order.id(), new BigDecimal("50"));
        OrderExecutionResult second = confirmService.confirmAuto(order.id(), new BigDecimal("50"));

        assertThat(first.executed()).isTrue();
        assertThat(second.executed()).isFalse();
        assertThat(gateway.placeOrderCalls).isEqualTo(1);
        assertThat(order.status()).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void liquidityCheckReducesOrdersThatConsumeTooMuchDepth() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                new OkxTradeAdapter(new SuccessfulGateway()),
                new FixedLiquidityService(depth(BigDecimal.valueOf(500))),
                clock
        );

        OrderExecutionResult result = confirmService.confirm(order.id(), BigDecimal.valueOf(80), ContractRiskRequest.safeDefaults());

        assertThat(result.executed()).isTrue();
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(pendingOrderService.get(order.id()).marginAmount()).isEqualByComparingTo("50.0000");
    }

    private static OrderBookLiquiditySnapshot depth(BigDecimal availableDepth) {
        return new OrderBookLiquiditySnapshot(
                "BTC-USDT-SWAP",
                BigDecimal.valueOf(99.99),
                BigDecimal.valueOf(100.01),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(2),
                availableDepth,
                availableDepth,
                BigDecimal.ONE,
                true,
                "",
                Instant.now()
        );
    }

    private static OrderBookLiquiditySnapshot spread(BigDecimal spreadBps) {
        return new OrderBookLiquiditySnapshot(
                "BTC-USDT-SWAP",
                BigDecimal.valueOf(99),
                BigDecimal.valueOf(101),
                BigDecimal.valueOf(100),
                spreadBps,
                BigDecimal.valueOf(500),
                BigDecimal.valueOf(500),
                BigDecimal.ONE,
                true,
                "",
                Instant.now()
        );
    }

    private static TradingProperties tradingProperties() {
        return new TradingProperties("SEMI_AUTO", true, true, 120, false);
    }

    private static class SuccessfulGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject().put("ordId", "okx-order-1");
            return root;
        }
    }

    private static class CountingGateway extends SuccessfulGateway {
        private int placeOrderCalls;

        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
            placeOrderCalls++;
            return super.placeOrder(payload);
        }
    }

    private static class LeverageCapturingGateway extends SuccessfulGateway {
        private Map<String, String> leveragePayload;

        @Override
        public JsonNode setLeverage(Map<String, String> payload) {
            this.leveragePayload = payload;
            return null;
        }
    }

    private static BudgetAllocation allocation(String finalMargin) {
        return new BudgetAllocation(
                new BigDecimal("50"),
                new BigDecimal("45"),
                new BigDecimal("40"),
                new BigDecimal("50"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("50"),
                1,
                new BigDecimal("0.45"),
                new BigDecimal("22.5"),
                BigDecimal.ONE,
                new BigDecimal("22.5"),
                new BigDecimal("30"),
                new BigDecimal("25"),
                BigDecimal.ZERO,
                new BigDecimal(finalMargin),
                new BigDecimal("45"),
                "TARGET_UTILIZATION",
                "OK",
                java.util.List.of(),
                "test allocation"
        );
    }

    private static class FixedLiquidityService extends OrderBookLiquidityService {
        private final OrderBookLiquiditySnapshot snapshot;

        FixedLiquidityService(OrderBookLiquiditySnapshot snapshot) {
            super(null, new AgentProperties());
            this.snapshot = snapshot;
        }

        @Override
        public OrderBookLiquiditySnapshot snapshot(String instId) {
            return snapshot;
        }
    }

    private static class CapturingRiskService implements RiskService {
        private ContractRiskRequest captured;

        @Override
        public RiskCheckResult check(ContractRiskRequest request) {
            this.captured = request;
            return new RiskCheckResult(true, RiskLevel.LOW, null, null, java.util.List.of(), null,
                    request.leverage(), BigDecimal.valueOf(100), Instant.now());
        }
    }

    private static class AutoLowerLeverageRiskService implements RiskService {
        private int calls;
        private int firstLeverage;
        private int secondLeverage;

        @Override
        public RiskCheckResult check(ContractRiskRequest request) {
            calls++;
            if (calls == 1) {
                firstLeverage = request.leverage();
                return new RiskCheckResult(false, RiskLevel.BLOCKED, "LEVERAGE_ABOVE_DYNAMIC_CAP",
                        "Leverage exceeds dynamic risk cap", java.util.List.of("cap leverage to 1x"),
                        null, 1, null, Instant.now());
            }
            secondLeverage = request.leverage();
            return new RiskCheckResult(true, RiskLevel.HIGH, null, null, java.util.List.of(),
                    null, request.leverage(), BigDecimal.valueOf(100), Instant.now());
        }
    }

    private static class RejectingRiskService implements RiskService {
        private int calls;

        @Override
        public RiskCheckResult check(ContractRiskRequest request) {
            calls++;
            return RiskCheckResult.rejected("TEST_RISK_REJECTED", "test risk rejection");
        }
    }

    private static class RejectOnceRiskService implements RiskService {
        private int calls;

        @Override
        public RiskCheckResult check(ContractRiskRequest request) {
            calls++;
            if (calls == 1) {
                return RiskCheckResult.rejected("TEST_RETRYABLE_RISK_REJECTED", "test retryable risk rejection");
            }
            return new RiskCheckResult(true, RiskLevel.LOW, null, null, java.util.List.of(), null,
                    request.leverage(), BigDecimal.valueOf(100), Instant.now());
        }
    }
}

package com.example.quant.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.agent.exit.NetProfitExitPolicy;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetAllocation;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.config.AgentProperties;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.risk.ContractRiskRequest;
import com.example.quant.risk.RiskCheckResult;
import com.example.quant.risk.RiskLevel;
import com.example.quant.risk.RiskService;
import com.example.quant.risk.ContractRiskService;
import com.example.quant.risk.DailyRiskService;
import com.example.quant.config.TradingProperties;
import com.example.quant.market.DirectionBias;
import com.example.quant.system.AutoTradeRiskMode;
import com.example.quant.system.SystemControlService;
import com.example.quant.tradeplan.CanonicalPlanCompiler;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.quant.tradeplan.PlanExecutionRisk;

class OrderConfirmServiceTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "9.99"})
    void manualConfirmationChecksFreshAvailableBalanceDespitePositiveEquity(String available) {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrders = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrders.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        CountingGateway gateway = new CountingGateway();
        AccountSnapshotService snapshots = mock(AccountSnapshotService.class);
        when(snapshots.summary())
                .thenReturn(new AccountSummary(new BigDecimal("10000"), available == null ? null : new BigDecimal(available), "OKX_DEMO", ""))
                .thenReturn(new AccountSummary(new BigDecimal("10000"), BigDecimal.TEN, "OKX_DEMO", ""));
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrders, new ContractRiskService(), testAdapter(gateway), null, null, snapshots, null, null, clock);

        OrderExecutionResult rejected = confirmService.confirm(order.id(), BigDecimal.TEN, ContractRiskRequest.safeDefaults());

        assertThat(rejected.executed()).isFalse();
        assertThat(rejected.message()).contains("可用余额");
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRM);
        assertThat(gateway.placeOrderCalls).isZero();

        OrderExecutionResult retried = confirmService.confirm(order.id(), BigDecimal.TEN);

        assertThat(retried.executed()).isTrue();
        assertThat(gateway.placeOrderCalls).isEqualTo(1);
    }

    @Test
    void autoConfirmationReleasesReservationWhenFreshBalanceIsInsufficient() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrders = new PendingOrderService(120, clock);
        AutoTradeBudgetService budgetService = new AutoTradeBudgetService(new AgentProperties());
        UUID orderId = UUID.randomUUID();
        TradePlan plan = PendingOrderServiceTest.samplePlan();
        BigDecimal margin = new BigDecimal("22.5");
        BudgetReservation reservation = budgetService.reserveBudget(plan.id(), orderId, "BTC-USDT-SWAP", margin, new BigDecimal("50"));
        PendingOrder order = pendingOrders.createAutoPendingOrder(MarketType.OKX_SWAP, plan, orderId,
                margin, reservation.reservationId(), allocation("22.5"), "AUTO_balance_recheck");
        CountingGateway gateway = new CountingGateway();
        AccountSnapshotService snapshots = mock(AccountSnapshotService.class);
        when(snapshots.summary()).thenReturn(new AccountSummary(new BigDecimal("10000"), BigDecimal.TEN, "OKX_REAL", ""));
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrders, new ContractRiskService(), testAdapter(gateway), null, null, snapshots, null, null, budgetService, clock);
        DailyRiskService dailyRisk = mock(DailyRiskService.class);
        confirmService.configureDailyRisk(dailyRisk);

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), new BigDecimal("50"));

        assertThat(result.executed()).isFalse();
        assertThat(result.message()).contains("可用余额不足");
        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(gateway.placeOrderCalls).isZero();
        assertThat(budgetService.reservation(reservation.reservationId()).orElseThrow().status().name()).isEqualTo("RELEASED");
        verifyNoInteractions(dailyRisk);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void automaticConfirmationResizesAsterAndStillChecksNetRiskReward(boolean passesNetRiskReward) {
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T10:27:45Z"), ZoneOffset.UTC);
        var pendingOrders = new PendingOrderService(120, clock);
        var budgetService = new AutoTradeBudgetService(new AgentProperties());
        BigDecimal margin = new BigDecimal("11.93435348");
        TradePlan plan = new TradePlan(UUID.randomUUID(), "ASTER-USDT-SWAP", TradePlanType.OPEN_SHORT, "MARKET",
                DirectionBias.BEARISH, new BigDecimal("0.75"),
                new BigDecimal("0.5225"), new BigDecimal("0.53338286"), new BigDecimal("0.49855771"),
                6, new BigDecimal("148.78"), new BigDecimal("12.95674638"), new BigDecimal("1.619212886907"),
                new BigDecimal("2.2"), 82, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("100000000"),
                "cross", List.of(), List.of(), "", false, clock.instant().plusSeconds(120));
        UUID id = UUID.randomUUID();
        var reservation = budgetService.reserveBudget(plan.id(), id, plan.instId(), margin, new BigDecimal("150"));
        PendingOrder order = pendingOrders.createAutoPendingOrder(MarketType.OKX_SWAP, plan, id, margin,
                reservation.reservationId(), allocation("11.93435348"), "AUTO_ASTER_RISK");
        var rules = new OkxInstrumentRules(plan.instId(), BigDecimal.ONE, "ASTER",
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.0001"));
        var gateway = new CountingGateway();
        var adapter = new OkxTradeAdapter(gateway, instId -> rules);
        var compiler = new CanonicalPlanCompiler();
        adapter.setCanonicalPlanCompiler(compiler);
        AgentProperties properties = new AgentProperties();
        if (passesNetRiskReward) {
            properties.market().setMaxFundingAbs(BigDecimal.ZERO);
        }
        AccountSnapshotService snapshots = mock(AccountSnapshotService.class);
        when(snapshots.summary()).thenReturn(new AccountSummary(
                new BigDecimal("10000"), new BigDecimal("100"), "OKX_DEMO", ""));
        var book = mock(OrderBookLiquidityService.class);
        when(book.snapshot(plan.instId())).thenAnswer(ignored -> new OrderBookLiquiditySnapshot(
                plan.instId(), new BigDecimal("0.5225"), new BigDecimal("0.5226"), new BigDecimal("0.52255"),
                BigDecimal.valueOf(2), new BigDecimal("10000"), new BigDecimal("10000"),
                BigDecimal.ONE, true, "", Instant.now()));
        RiskService risk = request -> new RiskCheckResult(true, RiskLevel.LOW, null, null, List.of(),
                null, request.leverage(), new BigDecimal("1.4914"), Instant.now());
        var confirmService = new OrderConfirmService(pendingOrders, risk, adapter,
                book, properties, snapshots, null, null, budgetService, clock);
        NetProfitExitPolicy exitCosts = mock(NetProfitExitPolicy.class);
        when(exitCosts.feeRates(plan.instId())).thenReturn(new NetProfitExitPolicy.FeeRates(
                new BigDecimal("0.0002"), new BigDecimal("0.0005")));
        confirmService.configureExecutionRisk(new PlanExecutionRisk(compiler, instId -> rules, book, exitCosts, properties, risk));
        DailyRiskService dailyRisk = mock(DailyRiskService.class);
        when(dailyRisk.claimAutomaticEntry(anyString(), eq(id.toString()), any(BigDecimal.class),
                eq(clock.instant()), any(BigDecimal.class))).thenReturn(new DailyRiskService.Decision(true, "OK"));
        confirmService.configureDailyRisk(dailyRisk);

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), margin);

        assertThat(budgetService.reservation(reservation.reservationId()).orElseThrow().amount())
                .isEqualByComparingTo(margin);
        if (passesNetRiskReward) {
            assertThat(result.submitted()).as(result.message()).isTrue();
            assertThat(order.size()).isEqualByComparingTo("126");
            assertThat(order.executableContractQuantity()).isEqualByComparingTo("126");
            assertThat(order.canonicalExecutablePlan().risk().estimatedNetStopLossAmount())
                    .isLessThanOrEqualTo(new BigDecimal("1.4914"));
            assertThat(gateway.placeOrderCalls).isEqualTo(1);
            assertThat(gateway.lastOrderPayload.get("sz")).isEqualTo("126");
            assertThat(gateway.lastOrderPayload.get("attachAlgoOrds")).asList().hasSize(1);
            assertThat(((Map<?, ?>) ((List<?>) gateway.lastOrderPayload.get("attachAlgoOrds")).get(0)).get("slTriggerPx"))
                    .isEqualTo("0.5334");
            verify(dailyRisk).claimAutomaticEntry(anyString(), eq(id.toString()), any(BigDecimal.class),
                    eq(clock.instant()), any(BigDecimal.class));
        } else {
            assertThat(result.submitted()).isFalse();
            assertThat(result.message()).startsWith("NET_RISK_REWARD_AFTER_COSTS_TOO_LOW:");
            assertThat(gateway.placeOrderCalls).isZero();
            assertThat(budgetService.reservation(reservation.reservationId()).orElseThrow().status().name())
                    .isEqualTo("RELEASED");
            verifyNoInteractions(dailyRisk);
        }
    }

    @Test
    void manualMarketConfirmationRejectsAnAdverseQuoteBeforeExchangeWrite() {
        var orders = new PendingOrderService(120);
        TradePlan plan = marketPlan();
        var order = orders.createPendingOrder(MarketType.OKX_SWAP, plan);
        var book = mock(OrderBookLiquidityService.class);
        when(book.snapshot(plan.instId())).thenAnswer(ignored -> new OrderBookLiquiditySnapshot(
                plan.instId(), new BigDecimal("100.29"), new BigDecimal("100.31"), new BigDecimal("100.30"),
                BigDecimal.valueOf(2), new BigDecimal("10000"), new BigDecimal("10000"),
                BigDecimal.ONE, true, "", Instant.now()));
        var feePolicy = mock(NetProfitExitPolicy.class);
        when(feePolicy.feeRates(plan.instId())).thenReturn(new NetProfitExitPolicy.FeeRates(BigDecimal.ZERO, BigDecimal.ZERO));
        var account = mock(AccountSnapshotService.class);
        when(account.summary()).thenReturn(new AccountSummary(new BigDecimal("10000"), new BigDecimal("100"), "OKX_DEMO", ""));
        var gateway = new CountingGateway();
        RiskService risk = new CapturingRiskService();
        var properties = new AgentProperties();
        var service = new OrderConfirmService(orders, risk, testAdapter(gateway), book, properties,
                account, null, null, Clock.systemUTC());
        service.configureExecutionRisk(new PlanExecutionRisk(new CanonicalPlanCompiler(),
                instId -> new OkxInstrumentRules(instId, new BigDecimal("0.01"), "BTC",
                        BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.01")),
                book, feePolicy, properties, risk));

        var result = service.confirm(order.id(), new BigDecimal("20"));

        assertThat(result.submitted()).isFalse();
        assertThat(result.message()).startsWith("MARKET_PRICE_DEVIATION_TOO_WIDE:");
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRM);
        assertThat(order.rejectReason()).isEqualTo(result.message());
        assertThat(gateway.placeOrderCalls).isZero();
    }

    @Test
    void dailyRiskGateBlocksEntryBeforeExchangeWrite() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(
                MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        OkxTradeAdapter adapter = mock(OkxTradeAdapter.class);
        DailyRiskService dailyRisk = mock(DailyRiskService.class);
        when(dailyRisk.claimEntry(anyString(), eq(order.id().toString()), any(BigDecimal.class), eq(clock.instant())))
                .thenReturn(new DailyRiskService.Decision(false, "MAX_DAILY_TRADES_REACHED"));
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService, new ContractRiskService(), adapter, clock);
        confirmService.configureDailyRisk(dailyRisk);

        OrderExecutionResult result = confirmService.confirm(
                order.id(), BigDecimal.TEN, ContractRiskRequest.safeDefaults());

        assertThat(result.executed()).isFalse();
        assertThat(result.message()).isEqualTo("MAX_DAILY_TRADES_REACHED");
        verifyNoInteractions(adapter);
    }

    @Test
    void expiredPendingOrderCannotBeConfirmed() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(1, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        Clock later = Clock.offset(clock, Duration.ofSeconds(2));
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                testAdapter(new SuccessfulGateway()),
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
                testAdapter(new SuccessfulGateway()),
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
                testAdapter(new SuccessfulGateway()),
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
    void confirmationUsesMeasuredOrderBookFreshness() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:05Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        CapturingRiskService riskService = new CapturingRiskService();
        OrderBookLiquiditySnapshot liquidity = new OrderBookLiquiditySnapshot(
                order.instId(), BigDecimal.valueOf(99.99), BigDecimal.valueOf(100.01), BigDecimal.valueOf(100),
                BigDecimal.valueOf(2), BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.ONE,
                true, "", clock.instant().minusSeconds(2));
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService, riskService, testAdapter(new SuccessfulGateway()),
                new FixedLiquidityService(liquidity), clock);

        OrderExecutionResult result = confirmService.confirm(order.id(), BigDecimal.TEN);

        assertThat(result.executed()).isTrue();
        assertThat(riskService.captured.websocketConnected()).isTrue();
        assertThat(riskService.captured.marketDelayMs()).isEqualTo(2000);
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
                testAdapter(new SuccessfulGateway()),
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
                testAdapter(new SuccessfulGateway()),
                new FixedLiquidityService(depth(BigDecimal.valueOf(500))),
                clock
        );

        OrderExecutionResult result = confirmService.confirm(order.id(), BigDecimal.valueOf(20), ContractRiskRequest.safeDefaults());

        assertThat(result.executed()).isTrue();
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void autoConfirmationRejectsConfiguredMarginWhenDepthIsTooThin() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                testAdapter(new SuccessfulGateway()),
                new FixedLiquidityService(depth(BigDecimal.valueOf(50))),
                clock
        );

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), BigDecimal.valueOf(20));

        assertThat(result.executed()).isFalse();
        assertThat(result.message()).contains("depth_usage_above");
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(pendingOrderService.get(order.id()).marginAmount()).isEqualByComparingTo("20.0000");
    }

    @Test
    void autoMarketConfirmationRejectsPlannedPriceDriftAgainstLiveMid() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, marketPlan());
        CountingGateway gateway = new CountingGateway();
        OrderBookLiquiditySnapshot liquidity = new OrderBookLiquiditySnapshot(
                order.instId(), new BigDecimal("100.99"), new BigDecimal("101.01"), new BigDecimal("101"),
                new BigDecimal("2"), new BigDecimal("500"), new BigDecimal("500"), BigDecimal.ONE,
                true, "", clock.instant());
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService, new ContractRiskService(), testAdapter(gateway),
                new FixedLiquidityService(liquidity), new AgentProperties(), null, null, null, clock);

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), BigDecimal.valueOf(20));

        assertThat(result.executed()).isFalse();
        assertThat(result.message()).contains("market_price_deviation_above_0.25");
        assertThat(gateway.placeOrderCalls).isZero();
    }

    @Test
    void autoConfirmationTreatsRuleScoreAsSoftAfterAutoPipelineGate() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, planWithScore(60));
        CountingGateway gateway = new CountingGateway();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService, new ContractRiskService(), testAdapter(gateway),
                new FixedLiquidityService(depth(BigDecimal.valueOf(500))), new AgentProperties(),
                null, null, null, clock);

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), BigDecimal.valueOf(20));

        assertThat(result.executed()).isTrue();
        assertThat(order.leverage()).isEqualTo(1);
        assertThat(gateway.placeOrderCalls).isEqualTo(1);
    }

    @Test
    void shadowAutoModeIsRejectedBeforeRiskOrExchangeSubmission() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.NO_RISK, 70);
        RejectingRiskService riskService = new RejectingRiskService();
        CountingGateway gateway = new CountingGateway();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                riskService,
                testAdapter(gateway),
                new FixedLiquidityService(spread(BigDecimal.valueOf(2))),
                new AgentProperties(),
                null,
                systemControlService,
                null,
                null,
                clock
        );

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), BigDecimal.valueOf(20));

        assertThat(result.executed()).isFalse();
        assertThat(result.message()).contains("Shadow");
        assertThat(riskService.calls).isZero();
        assertThat(gateway.placeOrderCalls).isZero();
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void stoppingAutoTradeBeforeConfirmationBlocksTheFinalSubmit() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(
                MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        CountingGateway gateway = new CountingGateway();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                testAdapter(gateway),
                new FixedLiquidityService(spread(BigDecimal.valueOf(2))),
                new AgentProperties(),
                null,
                systemControlService,
                null,
                null,
                clock
        );

        OrderExecutionResult result = confirmService.confirmAuto(order.id(), BigDecimal.valueOf(20));

        assertThat(result.executed()).isFalse();
        assertThat(result.message()).contains("自动交易已停止");
        assertThat(gateway.placeOrderCalls).isZero();
        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void autoConfirmationNeverRaisesAiPlanLeverageToUserMinimum() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.STRICT, 70, 5);
        LeverageCapturingGateway gateway = new LeverageCapturingGateway();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new CapturingRiskService(),
                testAdapter(gateway),
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
        assertThat(order.leverage()).isEqualTo(2);
        assertThat(order.size()).isEqualByComparingTo("0.40000000");
        assertThat(gateway.leveragePayload).containsEntry("lever", "2");
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
                testAdapter(gateway),
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
                testAdapter(gateway),
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
                testAdapter(new SuccessfulGateway()),
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
        assertThat(riskService.captured.maxSingleMarginRate()).isEqualByComparingTo("0.33333334");
        assertThat(riskService.captured.maxAbsFundingRate())
                .isEqualByComparingTo(new AgentProperties().market().maxFundingAbs());
        assertThat(riskService.captured.minVolume24h())
                .isEqualByComparingTo("2000000");
        assertThat(order.marginAmount()).isEqualByComparingTo("22.5000");
        assertThat(result.submitted()).isTrue();
        assertThat(result.filled()).isFalse();
        assertThat(result.partiallyFilled()).isFalse();
        assertThat(budgetService.reservation(reservation.reservationId()).orElseThrow().status().name()).isEqualTo("RESERVED");
        assertThat(order.status()).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void autoConfirmationSubmitTimeoutMarksUnknownAndKeepsReservedBudget() {
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
                "AUTOtimeoutunknown"
        );
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                testAdapter(new TimeoutAndQueryFailGateway()),
                null,
                new AgentProperties(),
                null,
                null,
                null,
                budgetService,
                clock
        );

        assertThatThrownBy(() -> confirmService.confirmAuto(order.id(), new BigDecimal("50")))
                .isInstanceOf(OrderSubmitStatusUnknownException.class)
                .hasMessageContaining("OKX request timed out");

        assertThat(order.status()).isEqualTo(OrderStatus.UNKNOWN_SUBMIT_STATUS);
        assertThat(budgetService.reservation(reservation.reservationId()).orElseThrow().status())
                .isEqualTo(com.example.quant.agent.budget.BudgetReservationStatus.RESERVED);
    }

    @Test
    void leverageTimeoutRejectsBecauseTradeOrderRequestWasNotSent() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(
                MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                testAdapter(new LeverageTimeoutGateway()),
                clock
        );

        assertThatThrownBy(() -> confirmService.confirm(
                order.id(), BigDecimal.TEN, ContractRiskRequest.safeDefaults()))
                .isInstanceOf(com.example.quant.okxtrade.OrderNotSubmittedException.class)
                .hasMessageContaining("PRE_ORDER_SETUP_FAILED");

        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.rejectReason()).contains("PRE_ORDER_SETUP_FAILED");
    }

    @Test
    void explicitUnknownResolutionReleasesDailyRiskOnlyAfterExchangeNotFound() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:03:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(
                MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        order.markUnknownSubmitStatus("request timed out");
        OkxTradeAdapter adapter = mock(OkxTradeAdapter.class);
        when(adapter.recoverUnknownSubmitStatus(order)).thenReturn(new OrderExecutionResult(
                false, true, null, "OKX_CLORDID_NOT_FOUND_AFTER_TIMEOUT: " + order.clientOrderId(),
                false, false, false, false));
        DailyRiskService dailyRisk = mock(DailyRiskService.class);
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService, new ContractRiskService(), adapter, clock);
        confirmService.configureDailyRisk(dailyRisk);

        OrderExecutionResult result = confirmService.resolveUnknownSubmit(order.id());

        assertThat(result.executed()).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
        verify(dailyRisk).releaseDefinitiveFailure(anyString(), eq(order.id().toString()), eq(clock.instant()));
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
                testAdapter(gateway),
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
                testAdapter(new SuccessfulGateway()),
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

    private static TradePlan marketPlan() {
        TradePlan plan = PendingOrderServiceTest.samplePlan();
        return new TradePlan(
                plan.id(), plan.instId(), plan.action(), "MARKET", plan.directionBias(), plan.confidence(),
                plan.entryPrice(), plan.stopLossPrice(), plan.takeProfitPrice(), plan.suggestedLeverage(),
                plan.suggestedSize(), plan.suggestedMargin(), plan.maxLossAmount(), plan.riskRewardRatio(),
                plan.signalScore(), plan.fundingRate(), plan.volatility(), plan.volume24h(), plan.tdMode(),
                plan.reasonList(), plan.riskList(), plan.invalidCondition(), plan.needUserConfirm(), plan.expireTime());
    }

    private static TradePlan planWithScore(int score) {
        TradePlan plan = PendingOrderServiceTest.samplePlan();
        return new TradePlan(
                plan.id(), plan.instId(), plan.action(), plan.orderType(), plan.directionBias(), plan.confidence(),
                plan.entryPrice(), plan.stopLossPrice(), plan.takeProfitPrice(), plan.suggestedLeverage(),
                plan.suggestedSize(), plan.suggestedMargin(), plan.maxLossAmount(), plan.riskRewardRatio(),
                score, plan.fundingRate(), plan.volatility(), plan.volume24h(), plan.tdMode(), plan.reasonList(),
                plan.riskList(), plan.invalidCondition(), plan.needUserConfirm(), plan.expireTime());
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
        public JsonNode placeOrder(Map<String, Object> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject().put("ordId", "okx-order-1");
            return root;
        }
    }

    private static class CountingGateway extends SuccessfulGateway {
        private int placeOrderCalls;
        private Map<String, Object> lastOrderPayload;

        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            placeOrderCalls++;
            lastOrderPayload = payload;
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

    private static class TimeoutAndQueryFailGateway extends SuccessfulGateway {
        @Override
        public JsonNode placeOrder(Map<String, Object> payload) {
            throw new IllegalStateException("OKX request timed out");
        }

        @Override
        public JsonNode queryOrder(Map<String, String> payload) {
            throw new IllegalStateException("OKX query unavailable");
        }
    }

    private static class LeverageTimeoutGateway extends SuccessfulGateway {
        @Override
        public JsonNode setLeverage(Map<String, String> payload) {
            throw new IllegalStateException("OKX request failed: request timed out");
        }
    }

    private static OkxTradeAdapter testAdapter(OkxOrderGateway gateway) {
        return new OkxTradeAdapter(gateway, instId -> new OkxInstrumentRules(
                instId, BigDecimal.ONE, "BTC", new BigDecimal("0.01"),
                new BigDecimal("0.01"), new BigDecimal("0.1")));
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

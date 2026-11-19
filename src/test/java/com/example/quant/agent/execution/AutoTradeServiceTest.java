package com.example.quant.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.entry.WaitingEntryIntentService;
import com.example.quant.agent.entry.WaitingEntryIntentStatus;
import com.example.quant.agent.gate.ContractTradeGate;
import com.example.quant.agent.selection.AiContractSelectionService;
import com.example.quant.agent.selection.ContractSelectionDecision;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.selection.V2ScoreDimension;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.OrderSubmitStatusUnknownException;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.okxtrade.OkxCurrentOrderSyncService;
import com.example.quant.risk.RiskLevel;
import com.example.quant.system.AutoTradeRiskMode;
import com.example.quant.system.SystemControlService;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanService;
import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;

class AutoTradeServiceTest {

    @Test
    void scanRepresentativePreservesScannerDeliveryOrderEvenWhenLegacyScorePrefersAnotherSymbol() {
        var first = candidate("ETH-USDT-SWAP", 64);
        var second = candidate("PEPE-USDT-SWAP", 70);
        var calculator = new com.example.quant.crypto.selection.V2SelectionScoreCalculator();
        var scores = java.util.Map.of(first.instId(), calculator.calculate(first),
                second.instId(), calculator.calculate(second));
        assertThat(AutoTradeService.topCandidate(List.of(first, second), scores)).contains(first);
        assertThat(AutoTradeService.topCandidate(List.of(first, second), java.util.Map.of())).contains(second);
    }

    @Test
    void preservesScannerCandidateOrderWhenPassingEligibleCandidatesToAi() {
        AgentProperties properties = new AgentProperties();
        properties.score().setMinTrendScore(0);
        properties.score().setMinVolumeScore(0);
        properties.score().setMinLiquidityScore(0);
        SystemControlService controls = new SystemControlService(tradingProperties());
        controls.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                properties, controls, new FakeTradeOrderRecordService(false), new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(), new PendingOrderService(120), new CapturingOrderConfirmService(),
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)), new FixedPositionSnapshotService(List.of()));
        AiContractSelectionService selector = Mockito.mock(AiContractSelectionService.class);
        ContractCandidate v2FirstButLowerV1 = candidate("ETH-USDT-SWAP", 70);
        ContractCandidate v2SecondButHigherV1 = candidate("BTC-USDT-SWAP", 95);
        Map<String, V2SelectionScoreResult> v2Scores = Map.of(
                v2FirstButLowerV1.instId(), selectionScore("92"),
                v2SecondButHigherV1.instId(), selectionScore("71"));
        Mockito.when(selector.select(Mockito.anyList(), Mockito.eq(v2Scores)))
                .thenReturn(new ContractSelectionDecision(
                        ContractSelectionDecision.DecisionSource.RULE_FALLBACK,
                        ContractSelectionDecision.SelectionAction.WAIT,
                        "", null, "WAIT", BigDecimal.ZERO, List.of(), List.of(), List.of(), "test-only"));
        service.configureAiContractSelection(selector);
        service.evaluateAndExecute(List.of(v2SecondButHigherV1, v2FirstButLowerV1), Map.of(), v2Scores);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContractCandidate>> pool = ArgumentCaptor.forClass(List.class);
        Mockito.verify(selector).select(pool.capture(), Mockito.eq(v2Scores));
        assertThat(pool.getValue()).extracting(ContractCandidate::instId)
                .containsExactly("ETH-USDT-SWAP", "BTC-USDT-SWAP");
    }

    private static V2SelectionScoreResult selectionScore(String rankingScore) {
        BigDecimal score = new BigDecimal(rankingScore);
        return new V2SelectionScoreResult(score, score, new BigDecimal("100"), BigDecimal.ONE,
                Map.of(V2ScoreDimension.CAPITAL_FLOW, score), List.of());
    }

    @Test
    void fallsBackFromHypeToBtcWithoutCrossCandidateAuditMetadata() {
        AgentProperties properties = new AgentProperties();
        SystemControlService controls = new SystemControlService(tradingProperties());
        controls.enableAutoTrade(BigDecimal.valueOf(20));
        CapturingOrderConfirmService confirm = new CapturingOrderConfirmService();
        MetadataTradePlanService plans = new MetadataTradePlanService();
        CapturingSelectionRecordService records = new CapturingSelectionRecordService();
        AutoTradeService service = new AutoTradeService(properties, controls, new FakeTradeOrderRecordService(false), records,
                plans, new PendingOrderService(120), confirm, new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of()));
        AiContractSelectionService selector = Mockito.mock(AiContractSelectionService.class);
        ContractSelectionDecision selection = new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY, ContractSelectionDecision.SelectionAction.TRADE,
                "HYPE-USDT-SWAP", DirectionBias.BULLISH, "MARKET",
                new BigDecimal("0.90"), List.of(
                new ContractSelectionDecision.RankedCandidate("HYPE-USDT-SWAP", 1, DirectionBias.BULLISH, "MARKET", new BigDecimal("0.90"),
                        List.of("HYPE三周期上涨", "RSI 65.41", "OI +3.17%"), List.of("HYPE波动风险")),
                new ContractSelectionDecision.RankedCandidate("BTC-USDT-SWAP", 2, DirectionBias.BEARISH, "MARKET", new BigDecimal("0.82"),
                        List.of("BTC下行"), List.of("BTC超卖"))), List.of("全局理由"), List.of("全局风险"), "");
        Mockito.when(selector.select(Mockito.anyList())).thenReturn(selection);
        service.configureAiContractSelection(selector);

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidateWithAction("HYPE-USDT-SWAP", 95, "WATCH_ONLY"),
                        candidate("BTC-USDT-SWAP", 90, RiskLevel.LOW, DirectionBias.BEARISH, DirectionBias.BEARISH)), Map.of());

        assertThat(plans.capturedPlan.instId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(plans.capturedPlan.reasonList()).containsExactly("BTC下行");
        assertThat(plans.capturedPlan.riskList()).containsExactly("BTC超卖");
    }

    @Test
    void selectedAiWaitPersistsIntentWithoutBudgetOrOrderSubmission() {
        AgentProperties properties = new AgentProperties();
        SystemControlService controls = new SystemControlService(tradingProperties());
        controls.enableAutoTrade(BigDecimal.valueOf(20));
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        AutoTradeService service = new AutoTradeService(
                properties, controls, new FakeTradeOrderRecordService(false), new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(), new PendingOrderService(120), confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)), new FixedPositionSnapshotService(List.of()));
        ContractCandidate candidate = candidateWithClosedCandle("ETH-USDT-SWAP", Instant.parse("2026-08-12T08:00:00Z"),
                "WAIT_PULLBACK", "100");
        ContractSelectionDecision wait = selectedDecision(
                ContractSelectionDecision.DecisionSource.AI_WAIT, candidate.instId(), DirectionBias.BULLISH,
                "WAIT_PULLBACK");
        AiContractSelectionService selector = Mockito.mock(AiContractSelectionService.class);
        Mockito.when(selector.select(Mockito.anyList())).thenReturn(wait);
        WaitingEntryIntentService intents = Mockito.mock(WaitingEntryIntentService.class);
        Mockito.when(intents.evaluate(Mockito.anyString(), Mockito.anyList(), Mockito.any()))
                .thenReturn(Optional.empty());
        Mockito.when(intents.createOrGet(Mockito.anyString(), Mockito.anyString(), Mockito.eq(candidate),
                        Mockito.eq(wait), Mockito.eq("qd-wait"), Mockito.any()))
                .thenReturn(new WaitingEntryIntentService.Intent(
                        "intent-1", candidate.instId(), WaitingEntryIntentStatus.WAITING, "qd-wait", true));
        service.configureAiContractSelection(selector);
        service.configureWaitingEntryIntent(intents);

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidate), Map.of(candidate.instId(), "qd-wait"));

        assertThat(result.status()).isEqualTo("WAITING_ENTRY");
        assertThat(result.instId()).isEqualTo(candidate.instId());
        assertThat(result.message()).isEqualTo("AI_WAITING_ENTRY");
        assertThat(confirmService.confirmCount).isZero();
        Mockito.verify(intents).createOrGet(Mockito.anyString(), Mockito.eq("AUTO_CONTRACT_V1"),
                Mockito.eq(candidate), Mockito.eq(wait), Mockito.eq("qd-wait"), Mockito.any());
    }

    @Test
    void triggeredWaitUsesOriginalClientOrderIdAndExistingHardGuardedExecution() {
        AgentProperties properties = new AgentProperties();
        properties.aiSelection().setPolicy("CURRENT_AI_OVERRIDE");
        SystemControlService controls = new SystemControlService(tradingProperties());
        controls.enableAutoTrade(BigDecimal.valueOf(20));
        CapturingPendingOrderService pendingOrders = new CapturingPendingOrderService();
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        AutoTradeService service = new AutoTradeService(
                properties, controls, new FakeTradeOrderRecordService(false), new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(), pendingOrders, confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)), new FixedPositionSnapshotService(List.of()));
        ContractCandidate candidate = candidateWithClosedCandle("ETH-USDT-SWAP", Instant.parse("2026-08-12T08:05:00Z"),
                "READY", "99.8");
        AiContractSelectionService selector = Mockito.mock(AiContractSelectionService.class);
        WaitingEntryIntentService intents = Mockito.mock(WaitingEntryIntentService.class);
        Mockito.when(intents.evaluate(Mockito.anyString(), Mockito.anyList(), Mockito.any()))
                .thenReturn(Optional.of(new WaitingEntryIntentService.TriggeredIntent(
                        "intent-1", candidate.instId(), DirectionBias.BULLISH, "qd-original", candidate)));
        service.configureAiContractSelection(selector);
        service.configureWaitingEntryIntent(intents);

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidate), Map.of(candidate.instId(), "qd-current"));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(pendingOrders.capturedClientOrderId).isEqualTo("qd-original");
        Mockito.verify(selector, Mockito.never()).select(Mockito.anyList());
        Mockito.verify(intents).complete("intent-1", WaitingEntryIntentStatus.SUBMITTED,
                result.pendingOrderId(), result.okxOrderId(), result.message(), result.createdAt());
    }

    @Test
    void ruleFirstTriggeredWaitCannotBypassCurrentRuleEligibility() {
        AgentProperties properties = new AgentProperties();
        SystemControlService controls = new SystemControlService(tradingProperties());
        controls.enableAutoTrade(BigDecimal.valueOf(20));
        CapturingPendingOrderService pendingOrders = new CapturingPendingOrderService();
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        AutoTradeService service = new AutoTradeService(
                properties, controls, new FakeTradeOrderRecordService(false), new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(), pendingOrders, confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)), new FixedPositionSnapshotService(List.of()));
        ContractCandidate denied = candidateWithAction("ETH-USDT-SWAP", 60, "WAIT_CONFIRM");
        WaitingEntryIntentService intents = Mockito.mock(WaitingEntryIntentService.class);
        Mockito.when(intents.evaluate(Mockito.anyString(), Mockito.anyList(), Mockito.any()))
                .thenReturn(Optional.of(new WaitingEntryIntentService.TriggeredIntent(
                        "intent-2", denied.instId(), DirectionBias.BULLISH, "qd-original", denied)));
        service.configureWaitingEntryIntent(intents);

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(denied));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).startsWith("WAITING_ENTRY_RULE_INELIGIBLE:");
        assertThat(confirmService.confirmCount).isZero();
        assertThat(pendingOrders.capturedClientOrderId).isNull();
        Mockito.verify(intents).complete(Mockito.eq("intent-2"), Mockito.eq(WaitingEntryIntentStatus.FAILED_SAFE),
                Mockito.isNull(), Mockito.isNull(), Mockito.contains("WAITING_ENTRY_RULE_INELIGIBLE"), Mockito.any());
    }

    @Test
    void executesAiSelectedCandidateInsteadOfRuleFirstCandidate() {
        AgentProperties properties = new AgentProperties();
        properties.autoTrade().setMinOpenPositions(1);
        properties.autoTrade().setMaxOpenPositions(1);
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        CapturingSelectionRecordService recordService = new CapturingSelectionRecordService();
        SystemControlService controls = new SystemControlService(tradingProperties());
        controls.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                properties, controls, new FakeTradeOrderRecordService(false), recordService,
                new CandidateTradePlanService(), new PendingOrderService(120), confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)), new FixedPositionSnapshotService(List.of())
        );
        AiContractSelectionService selector = Mockito.mock(AiContractSelectionService.class);
        Mockito.when(selector.select(Mockito.anyList())).thenReturn(new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE,
                "ETH-USDT-SWAP", DirectionBias.BEARISH, "MARKET", new BigDecimal("0.8"),
                List.of(new ContractSelectionDecision.RankedCandidate(
                        "ETH-USDT-SWAP", 1, DirectionBias.BEARISH, "MARKET", new BigDecimal("0.8"))),
                List.of(), List.of(), ""));
        service.configureAiContractSelection(selector);

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(
                candidate("BTC-USDT-SWAP", 95), candidate("ETH-USDT-SWAP", 85)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.instId()).isEqualTo("ETH-USDT-SWAP");
        assertThat(confirmService.confirmCount).isEqualTo(1);
        assertThat(recordService.selection.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.AI_PRIMARY);
    }

    @Test
    void recordsAiSelectedCandidateAndWaitReasonWhenPlanDoesNotEnter() {
        AgentProperties properties = new AgentProperties();
        properties.autoTrade().setMinOpenPositions(1);
        properties.autoTrade().setMaxOpenPositions(1);
        CapturingSelectionRecordService recordService = new CapturingSelectionRecordService();
        SystemControlService controls = new SystemControlService(tradingProperties());
        controls.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                properties, controls, new FakeTradeOrderRecordService(false), recordService,
                new WatchSelectedTradePlanService(), new PendingOrderService(120),
                new CapturingOrderConfirmService(), new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );
        AiContractSelectionService selector = Mockito.mock(AiContractSelectionService.class);
        Mockito.when(selector.select(Mockito.anyList())).thenReturn(new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE,
                "ETH-USDT-SWAP", DirectionBias.BEARISH, "WAIT_RETEST", new BigDecimal("0.8"),
                List.of(new ContractSelectionDecision.RankedCandidate(
                        "ETH-USDT-SWAP", 1, DirectionBias.BEARISH, "WAIT_RETEST", new BigDecimal("0.8"))),
                List.of("等待回踩确认"), List.of(), ""));
        service.configureAiContractSelection(selector);

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(
                candidate("BTC-USDT-SWAP", 95), candidate("ETH-USDT-SWAP", 79)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).startsWith("no_order_submitted:");
        assertThat(recordService.candidate.instId()).isEqualTo("ETH-USDT-SWAP");
    }

    @Test
    void stopsEntireCandidateRoundOnLossCooldown() {
        AgentProperties properties = new AgentProperties();
        properties.autoTrade().setMinOpenPositions(1);
        properties.autoTrade().setMaxOpenPositions(1);
        SystemControlService controls = new SystemControlService(tradingProperties());
        controls.enableAutoTrade(BigDecimal.valueOf(20));
        SequencedOrderConfirmService confirmService = new SequencedOrderConfirmService(List.of(
                OrderExecutionResult.rejected("LOSS_COOLDOWN_ACTIVE")
        ));
        AutoTradeService service = new AutoTradeService(
                properties, controls, new FakeTradeOrderRecordService(false), new FakeAutoTradeRecordService(),
                new FirstExecutableThenWatchTradePlanService(), new PendingOrderService(120), confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)), new FixedPositionSnapshotService(List.of())
        );
        AiContractSelectionService selector = Mockito.mock(AiContractSelectionService.class);
        Mockito.when(selector.select(Mockito.anyList())).thenReturn(new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE,
                "BTC-USDT-SWAP", DirectionBias.BULLISH, "MARKET", new BigDecimal("0.8"),
                List.of(
                        new ContractSelectionDecision.RankedCandidate(
                                "BTC-USDT-SWAP", 1, DirectionBias.BULLISH, "MARKET", new BigDecimal("0.8")),
                        new ContractSelectionDecision.RankedCandidate(
                                "ETH-USDT-SWAP", 2, DirectionBias.BULLISH, "MARKET", new BigDecimal("0.75"))
                ), List.of(), List.of(), ""));
        service.configureAiContractSelection(selector);

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(
                candidate("BTC-USDT-SWAP", 90), candidate("ETH-USDT-SWAP", 89)));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.message()).contains("LOSS_COOLDOWN_ACTIVE");
        assertThat(confirmService.confirmCount).isEqualTo(1);
    }

    @Test
    void skipsWhenRuntimeSwitchIsOff() {
        CapturingAutoTradeRecordService recordService = new CapturingAutoTradeRecordService();
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                new SystemControlService(tradingProperties()),
                new FakeTradeOrderRecordService(false),
                recordService,
                new FailingTradePlanService(),
                new PendingOrderService(120),
                null,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of());

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).isEqualTo("auto_trade_runtime_switch_off");
        assertThat(recordService.records).extracting(AutoTradeService.AutoTradeResult::status)
                .containsExactly("SKIPPED");
    }

    @Test
    void skipsWhenPositionCapacityIsFull() {
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade();
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new FailingTradePlanService(),
                new PendingOrderService(120),
                null,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of(
                        new PositionSummary("BTC-USDT-SWAP", "long", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"),
                        new PositionSummary("ETH-USDT-SWAP", "short", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"),
                        new PositionSummary("SOL-USDT-SWAP", "long", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL")
                ))
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate()));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("position_capacity_full");
    }

    @Test
    void capsOrderMarginByAvailableBalanceWithoutShrinkingConfiguredTotalBudget() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(BigDecimal.valueOf(100), BigDecimal.valueOf(20), confirmService)
                .evaluateAndExecute(List.of(candidate()));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.capturedMargin).isEqualByComparingTo("20");
    }

    @Test
    void capsAllocatedMarginAtLeveragedStopAndCostBudget() {
        AgentProperties properties = new AgentProperties();
        SystemControlService controls = new SystemControlService(tradingProperties());
        controls.enableAutoTrade(new BigDecimal("60000"));
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        TradePlan base = samplePlan();
        TradePlan largeRiskPlan = new TradePlan(
                base.id(), base.instId(), base.action(), base.orderType(), base.directionBias(), base.confidence(),
                base.entryPrice(), base.stopLossPrice(), base.takeProfitPrice(), base.suggestedLeverage(),
                base.suggestedSize(), new BigDecimal("40000"), base.maxLossAmount(), base.riskRewardRatio(),
                base.signalScore(), base.fundingRate(), base.volatility(), base.volume24h(), base.tdMode(),
                base.reasonList(), base.riskList(), base.invalidCondition(), base.needUserConfirm(), base.expireTime());
        AutoTradeService service = new AutoTradeService(
                properties, controls, new FakeTradeOrderRecordService(false), new FakeAutoTradeRecordService(),
                new FixedTradePlanService(largeRiskPlan), new PendingOrderService(120), confirmService,
                new FixedAccountSnapshotService(new BigDecimal("60000"), new BigDecimal("60000")),
                new FixedPositionSnapshotService(List.of()));

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate()));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.capturedMargin).isEqualByComparingTo("13761.4678899");
    }

    @Test
    void accountSnapshotFailureBlocksNewAutomatedEntry() {
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50));
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(), systemControlService, new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(), new FixedTradePlanService(samplePlan()),
                new PendingOrderService(120), confirmService, new FailingAccountSnapshotService(),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate()));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("account_available_balance_unavailable");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void positionSnapshotFailureBlocksNewAutomatedEntry() {
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50));
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(), systemControlService, new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(), new FixedTradePlanService(samplePlan()),
                new PendingOrderService(120), confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FailingPositionSnapshotService()
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate()));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("position_snapshot_unavailable");
        assertThat(confirmService.confirmCount).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid"})
    void malformedPositionQuantityBlocksBeforeItCanDisappearFromRiskChecks(String size) {
        SystemControlService controls = new SystemControlService(tradingProperties());
        controls.enableAutoTrade(BigDecimal.valueOf(50));
        CapturingOrderConfirmService confirm = new CapturingOrderConfirmService();
        PositionSummary malformed = new PositionSummary("SOL-USDT-SWAP", "net", size,
                new BigDecimal("100"), BigDecimal.ZERO, "OKX_REAL");
        AutoTradeService service = new AutoTradeService(new AgentProperties(), controls,
                new FakeTradeOrderRecordService(false), new FakeAutoTradeRecordService(),
                new FixedTradePlanService(samplePlan()), new PendingOrderService(120), confirm,
                new FixedAccountSnapshotService(new BigDecimal("1000")),
                new FixedPositionSnapshotService(List.of(malformed)));

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate()));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("position_snapshot_unavailable", "POSITION_SIZE_UNAVAILABLE");
        assertThat(confirm.confirmCount).isZero();
    }

    @Test
    void blocksThirdSameDirectionAltOpenedInSameRound() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(
                candidate("DOGE-USDT-SWAP", 95),
                candidate("PEPE-USDT-SWAP", 94),
                candidate("SHIB-USDT-SWAP", 93)
        ));

        assertThat(confirmService.confirmCount).isEqualTo(2);
        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.message()).contains("auto_refill_submitted=2");
    }

    @Test
    void blocksThirdSameDirectionAltFromPreviousRoundPendingOrders() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(List.of(
                        new PositionSummary("DOGE-USDT-SWAP", "long", "1", BigDecimal.ONE, BigDecimal.ZERO, "LOCAL_ACTIVE_ENTRY"),
                        new PositionSummary("PEPE-USDT-SWAP", "long", "1", BigDecimal.ONE, BigDecimal.ZERO, "LOCAL_ACTIVE_ENTRY")
                )),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(
                candidate("SHIB-USDT-SWAP", 95),
                candidate("BTC-USDT-SWAP", 94)
        ));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.instId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(confirmService.confirmCount).isEqualTo(1);
    }

    @Test
    void blocksThirdSameDirectionAltFromPreviousRoundOkxCurrentOrders() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of()),
                new com.example.quant.agent.budget.AutoTradeBudgetService(new AgentProperties()),
                new FixedCurrentOrderSyncService(new OkxCurrentOrderSyncService.SyncResult(
                        2, 0, 2, false, null,
                        List.of("DOGE-USDT-SWAP", "PEPE-USDT-SWAP"),
                        List.of(
                                new PositionSummary("DOGE-USDT-SWAP", "long", "1", BigDecimal.ONE, BigDecimal.ZERO, "OKX_CURRENT_ENTRY"),
                                new PositionSummary("PEPE-USDT-SWAP", "long", "1", BigDecimal.ONE, BigDecimal.ZERO, "OKX_CURRENT_ENTRY")
                        )))
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(
                candidate("SHIB-USDT-SWAP", 95),
                candidate("BTC-USDT-SWAP", 94)
        ));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.instId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(confirmService.confirmCount).isEqualTo(1);
    }

    @Test
    void treatsConfiguredAutoTradeMarginAsTotalBudgetNotPerOrderMargin() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(BigDecimal.valueOf(50), BigDecimal.valueOf(1000), confirmService)
                .evaluateAndExecute(List.of(candidate()));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.capturedMargin).isEqualByComparingTo("16.666667");
    }

    @Test
    void opensThirdPositionWithThirdSlotBudgetWhenTwoPositionsAreHeld() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(50),
                        confirmService,
                        List.of(
                                new PositionSummary("ETH-USDT-SWAP", "long", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"),
                                new PositionSummary("SOL-USDT-SWAP", "short", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL")
                        )
                )
                .evaluateAndExecute(List.of(candidate()));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.capturedMargin).isEqualByComparingTo("6.6666666");
    }

    @Test
    void refillsMissingPositionsUntilConfiguredMinimumOpenPositions() {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.autoTrade().setMinOpenPositions(3);
        agentProperties.autoTrade().setMaxOpenPositions(3);
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                agentProperties,
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of(
                        new PositionSummary("XRP-USDT-SWAP", "long", "1", BigDecimal.valueOf(1), BigDecimal.ZERO, "OKX_REAL")
                ))
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(
                candidate("BTC-USDT-SWAP"),
                candidate("ETH-USDT-SWAP"),
                candidate("SOL-USDT-SWAP")
        ));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.message()).contains("auto_refill_submitted=2");
        assertThat(confirmService.confirmCount).isEqualTo(2);
        assertThat(confirmService.capturedMargins).hasSize(2);
        assertThat(confirmService.capturedMargins.get(0)).isEqualByComparingTo("6.6666666");
        assertThat(confirmService.capturedMargins.get(1)).isEqualByComparingTo("6.6666666");
    }

    @Test
    void allocatesThreePositionsByEqualThirdsFromOneTotalBudget() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(
                candidate("BTC-USDT-SWAP", 92),
                candidate("ETH-USDT-SWAP", 86),
                candidate("SOL-USDT-SWAP", 82)
        ));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(3);
        assertThat(confirmService.capturedMargins).hasSize(3);
        assertThat(confirmService.capturedMargins.get(0)).isEqualByComparingTo("16.666667");
        assertThat(confirmService.capturedMargins.get(1)).isEqualByComparingTo("16.6666665");
        assertThat(confirmService.capturedMargins.get(2)).isEqualByComparingTo("16.6666665");
    }

    @Test
    void allowsScore81WhenNoPositionIsOpen() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(BigDecimal.valueOf(20), BigDecimal.valueOf(1000), confirmService)
                .evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 81)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
    }

    @Test
    void strictRiskModeStillRequiresAutoTradeAllowedAction() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.STRICT);
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new FailingTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidateWithAction("BTC-USDT-SWAP", 95, "WAIT_CONFIRM")));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("candidate_action_not_auto_trade_allowed_WAIT_CONFIRM");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void noRiskModeRecordsShadowWithoutPendingOrderOrSubmission() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.NO_RISK);
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new NoRiskCandidateTradePlanService(),
                pendingOrderService,
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidateWithAction("BTC-USDT-SWAP", 70, "WAIT_CONFIRM")));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("shadow_observation_recorded=1");
        assertThat(confirmService.confirmCount).isZero();
        assertThat(pendingOrderService.allOrders()).isEmpty();
    }

    @Test
    void noRiskModeUsesUserSelectedMinimumScore() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.NO_RISK, 60);
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new NoRiskCandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidateWithAction("BTC-USDT-SWAP", 65, "WAIT_CONFIRM")));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("shadow_observation_recorded=1");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void noRiskModeRecordsUnknownNewsAsNonQualifiedShadowCandidate() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.NO_RISK, 70);
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new NoRiskCandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidateWithNewsRisk(
                candidate("PENGU-USDT-SWAP", 75, RiskLevel.LOW, DirectionBias.BEARISH, DirectionBias.BEARISH,
                        BigDecimal.TEN, BigDecimal.ONE),
                ContractNewsRiskAnalysis.unknown("新闻源不可用")
        )));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("shadow_no_candidate_qualified: news_data_unavailable");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void noRiskModeStillBlocksCriticalNewsRisk() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.NO_RISK, 70);
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new NoRiskCandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidateWithNewsRisk(
                candidate("DELIST-USDT-SWAP", 85, RiskLevel.LOW, DirectionBias.BEARISH, DirectionBias.BEARISH,
                        BigDecimal.TEN, BigDecimal.ONE),
                ContractNewsRiskAnalysis.critical("退市/暂停交易重大新闻")
        )));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("news_risk_critical");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void strictModeRejectsUnknownNewsBeforeOrderSubmission() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidateWithNewsRisk(
                candidate("PENGU-USDT-SWAP", 95, RiskLevel.LOW, DirectionBias.BULLISH, DirectionBias.BULLISH,
                        BigDecimal.valueOf(2), BigDecimal.ONE),
                ContractNewsRiskAnalysis.unknown("新闻源不可用", 20)
        )));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("news_data_unavailable");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void noRiskModeUsesDisplayedScoreForShadowEligibilityOnly() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50), AutoTradeRiskMode.NO_RISK);
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new NoRiskCandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidateWithActionAndFinalRankScore("PEPE-USDT-SWAP", 84, "WAIT_CONFIRM", BigDecimal.valueOf(60))));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("shadow_observation_recorded=1");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void autoTradeClientOrderIdUsesOkxAcceptedAlphanumericFormat() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        CapturingPendingOrderService pendingOrderService = new CapturingPendingOrderService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.STRICT, 69);
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new NoRiskCandidateTradePlanService(),
                pendingOrderService,
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidate("DOGE-USDT-SWAP", 95)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(pendingOrderService.capturedClientOrderId)
                .matches("[A-Za-z0-9]{1,32}")
                .doesNotContain("_");
    }

    @Test
    void strictModeKeepsThirdSlotEqualAfterCandidatePassedScoreGate() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50), AutoTradeRiskMode.STRICT, 65);
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of(
                        new PositionSummary("DOGE-USDT-SWAP", "short", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"),
                        new PositionSummary("BONK-USDT-SWAP", "short", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL")
                ))
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidate("PIPPIN-USDT-SWAP", 95)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
        assertThat(confirmService.capturedMargin).isEqualByComparingTo("16.6666665");
    }

    @Test
    void strictModeStillUsesQualityAdjustedBudgetWhenAvailableBalanceDrops() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50), AutoTradeRiskMode.STRICT, 65);
        AgentProperties agentProperties = new AgentProperties();
        com.example.quant.agent.budget.AutoTradeBudgetService budgetService =
                new com.example.quant.agent.budget.AutoTradeBudgetService(agentProperties);
        var first = budgetService.reserveBudget(UUID.randomUUID(), UUID.randomUUID(), "PEPE-USDT-SWAP",
                new BigDecimal("22.5"), new BigDecimal("50"));
        budgetService.markUsed(first.reservationId());
        var second = budgetService.reserveBudget(UUID.randomUUID(), UUID.randomUUID(), "DOGE-USDT-SWAP",
                new BigDecimal("15"), new BigDecimal("50"));
        budgetService.markUsed(second.reservationId());
        AutoTradeService service = new AutoTradeService(
                agentProperties,
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(new BigDecimal("12.5")),
                new FixedPositionSnapshotService(List.of(
                        new PositionSummary("PEPE-USDT-SWAP", "long", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"),
                        new PositionSummary("DOGE-USDT-SWAP", "short", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL")
                )),
                budgetService
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidate("BABY-USDT-SWAP", 95)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
        assertThat(confirmService.capturedMargin).isEqualByComparingTo("12.5");
    }

    @Test
    void allowsScore69WhenOnePositionIsOpenBecausePositionCountDoesNotRaiseThreshold() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(1000),
                        confirmService,
                List.of(new PositionSummary("ETH-USDT-SWAP", "long", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"))
                )
                .evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 69)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
    }

    @Test
    void allowsRelaxedScoreCandidateWhenNoOtherHardRiskBlocksIt() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(1000),
                        confirmService)
                .evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 72)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
    }

    @Test
    void skipsThirdSameDirectionAltAndFallsBackToMajorCandidate() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(1000),
                        confirmService,
                        List.of(
                                new PositionSummary("SOL-USDT-SWAP", "long", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"),
                                new PositionSummary("ARB-USDT-SWAP", "long", "1", BigDecimal.valueOf(1), BigDecimal.ZERO, "OKX_REAL")
                        )
                )
                .evaluateAndExecute(List.of(candidate("DOGE-USDT-SWAP", 95), candidate("BTC-USDT-SWAP", 94)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.instId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(confirmService.confirmCount).isEqualTo(1);
    }

    @Test
    void allowsSecondQualifiedPositionInChoppyRegimeButKeepsReducedCapacity() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(1000),
                        confirmService,
                        List.of(new PositionSummary("ETH-USDT-SWAP", "long", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"))
                )
                .evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 95, RiskLevel.LOW,
                        DirectionBias.NEUTRAL, DirectionBias.NEUTRAL)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
    }

    @Test
    void doesNotOpenNewPositionWhenMarketRegimeIsRiskOff() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(BigDecimal.valueOf(20), BigDecimal.valueOf(1000), confirmService)
                .evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 95, RiskLevel.BLOCKED,
                        DirectionBias.BEARISH, DirectionBias.BEARISH)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("dynamicMax=0");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void continuesToSecondCandidateWhenFirstFailsWithFallbackAllowedReason() {
        SequencedOrderConfirmService confirmService = new SequencedOrderConfirmService(List.of(
                OrderExecutionResult.rejected("实时订单簿流动性不足：spread_bps_above_8"),
                OrderExecutionResult.filled("okx-order-2", "入场已成交")
        ));
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidate("BTC-USDT-SWAP", 95), candidate("ETH-USDT-SWAP", 94)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.instId()).isEqualTo("ETH-USDT-SWAP");
        assertThat(confirmService.confirmCount).isEqualTo(2);
    }

    @Test
    void positionQualityAllows60AndRejectsEitherScoreBelow60() {
        PositionQualityPolicy policy = new PositionQualityPolicy(new AgentProperties());

        assertThat(policy.evaluate(List.of(), 0,
                candidateWithActionAndFinalRankScore("ETH-USDT-SWAP", 60, "AUTO_TRADE_ALLOWED",
                        BigDecimal.valueOf(60))).allowed()).isTrue();
        assertThat(policy.evaluate(List.of(), 0,
                candidateWithActionAndFinalRankScore("ETH-USDT-SWAP", 59, "AUTO_TRADE_ALLOWED",
                        BigDecimal.valueOf(60))).reason()).isEqualTo("position_quality_score_below_60");
        assertThat(policy.evaluate(List.of(), 0,
                candidateWithActionAndFinalRankScore("ETH-USDT-SWAP", 71, "AUTO_TRADE_ALLOWED",
                        new BigDecimal("59.99"))).reason()).isEqualTo("position_quality_final_rank_below_60");
        ContractCandidate highVolatility = candidate("ETH-USDT-SWAP", 87, RiskLevel.HIGH,
                DirectionBias.BULLISH, DirectionBias.BULLISH);
        assertThat(policy.evaluate(List.of(), 0, highVolatility).reason())
                .isEqualTo("position_quality_score_below_88");
    }

    @Test
    void ruleFallbackRecordUsesTheRecordedCandidatesOwnSkipReason() {
        AgentProperties properties = new AgentProperties();
        properties.positionQuality().setMinScoreByOpenPositions(Map.of(0, 65));
        properties.positionQuality().setMinFinalRankScoreByOpenPositions(Map.of(0, 65));
        SystemControlService controls = new SystemControlService(tradingProperties());
        controls.enableAutoTrade(BigDecimal.valueOf(20));
        CapturingSelectionRecordService records = new CapturingSelectionRecordService();
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        AutoTradeService service = new AutoTradeService(
                properties, controls, new FakeTradeOrderRecordService(false), records,
                new CandidateTradePlanService(), new PendingOrderService(120), confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)), new FixedPositionSnapshotService(List.of())
        );
        AiContractSelectionService selector = Mockito.mock(AiContractSelectionService.class);
        Mockito.when(selector.select(Mockito.anyList())).thenReturn(new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.RULE_FALLBACK,
                ContractSelectionDecision.SelectionAction.TRADE,
                null, null, null, null, List.of(), List.of(), List.of(), ""));
        service.configureAiContractSelection(selector);

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(
                candidateWithActionAndFinalRankScore("ETH-USDT-SWAP", 71, "WAIT", BigDecimal.valueOf(60)),
                candidateWithActionAndFinalRankScore("MMT-USDT-SWAP", 55, "WAIT", BigDecimal.valueOf(55))));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(records.candidate.instId()).isEqualTo("ETH-USDT-SWAP");
        assertThat(records.candidate.score()).isEqualTo(71);
        assertThat(records.candidate.finalRankScore()).isEqualByComparingTo("60");
        assertThat(result.message())
                .contains("position_quality_final_rank_below_65", "candidate_action_not_auto_trade_allowed_WAIT")
                .doesNotContain("position_quality_score_below_65");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void fallbackAttemptBudgetIsNotConsumedByAlreadyIneligibleHigherRankedCandidates() {
        // maxAutoTradeFallbackRank=5。5 个 finalRankScore 更高但点差极端（已知不可交易）的候选，
        // 不得把唯一合法候选挤出尝试名单——那样这一轮根本不会尝试任何可执行候选。
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        List<ContractCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candidates.add(candidate("WIDE" + i + "-USDT-SWAP", 95, RiskLevel.LOW,
                    DirectionBias.BULLISH, DirectionBias.BULLISH,
                    BigDecimal.valueOf(30), BigDecimal.ONE));
        }
        candidates.add(candidate("GOOD-USDT-SWAP", 80, RiskLevel.LOW,
                DirectionBias.BULLISH, DirectionBias.BULLISH,
                BigDecimal.valueOf(2), BigDecimal.ONE));

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(candidates);

        assertThat(result.instId())
                .as("合法交易候选必须仍然进入尝试名单")
                .isEqualTo("GOOD-USDT-SWAP");
        assertThat(result.status()).isEqualTo("EXECUTED");
    }

    @Test
    void fallbackPriorityTreatsNewsBlockedCandidatesAsNotReadyEvenWhenTheActionGatePasses() {
        // autoDenyReasons 只看 action / signalType / 极端点差，完全不看新闻。
        // 这 5 个候选点差正常、动作合规，但新闻已经硬拒绝——循环走到它们必然 continue。
        // 只看 autoGate 的优先级判断会把它们当成就绪，耗光全部 5 个尝试名额。
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AgentProperties properties = new AgentProperties();
        List<ContractCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ContractCandidate newsBlocked = candidateWithNewsRisk(
                    candidate("NEWSBAD" + i + "-USDT-SWAP", 95, RiskLevel.LOW,
                            DirectionBias.BULLISH, DirectionBias.BULLISH,
                            BigDecimal.valueOf(2), BigDecimal.ONE),
                    ContractNewsRiskAnalysis.critical("退市/暂停交易重大新闻"));
            assertThat(ContractTradeGate.autoDenyReasons(newsBlocked, properties))
                    .as("前提：动作门控确实通过，只看 autoGate 会误判为就绪")
                    .isEmpty();
            assertThat(ContractTradeGate.newsDenyReasons(newsBlocked, properties))
                    .as("前提：新闻门控确实必拒")
                    .isNotEmpty();
            candidates.add(newsBlocked);
        }
        candidates.add(candidate("GOOD-USDT-SWAP", 80, RiskLevel.LOW,
                DirectionBias.BULLISH, DirectionBias.BULLISH,
                BigDecimal.valueOf(2), BigDecimal.ONE));

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(candidates);

        assertThat(result.instId())
                .as("新闻已判定必拒的候选不得占用有限的尝试名额")
                .isEqualTo("GOOD-USDT-SWAP");
        assertThat(result.status()).isEqualTo("EXECUTED");
    }

    @Test
    void fallbackPriorityDoesNotTreatPlanGateWarningsAsKnownRejection() {
        // planDenyReasons 在规则回退路径下由 ContractTradePlanBuilder 当作 gateWarnings 处理，
        // 并不必然阻断下单。把它算进"已知必拒"会误伤真正可成交的候选：
        // 这个候选 planDenyReasons 非空，但它确实能成交。
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        ContractCandidate good = candidate("GOOD-USDT-SWAP", 80, RiskLevel.LOW,
                DirectionBias.BULLISH, DirectionBias.BULLISH,
                BigDecimal.valueOf(2), BigDecimal.ONE);
        assertThat(ContractTradeGate.planDenyReasons(good, new AgentProperties()))
                .as("前提：该候选的计划门控确实非空")
                .isNotEmpty();

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(good));

        assertThat(result.status())
                .as("计划门控非空不等于已知必拒，不得据此把候选踢出尝试名单")
                .isEqualTo("EXECUTED");
    }

    @Test
    void ineligibleCandidatePrioritisationStillLeavesEveryHardGateInPlace() {
        // 排名只决定优先级。即使唯一候选排在第一位，硬门控不通过就不得产生任何下单。
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        PendingOrderService pendingOrders = new PendingOrderService(120);
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                pendingOrders,
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidate("WIDE-USDT-SWAP", 95, RiskLevel.LOW,
                        DirectionBias.BULLISH, DirectionBias.BULLISH,
                        BigDecimal.valueOf(30), BigDecimal.ONE)));

        assertThat(result.status()).isNotEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isZero();
        assertThat(pendingOrders.allOrders()).isEmpty();
    }

    @Test
    void preConfirmRefreshSpreadFailureFallsBackToNextCandidate() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(
                candidate("BTC-USDT-SWAP", 95, RiskLevel.LOW, DirectionBias.BULLISH, DirectionBias.BULLISH,
                        BigDecimal.valueOf(10), BigDecimal.ONE),
                candidate("ETH-USDT-SWAP", 94, RiskLevel.LOW, DirectionBias.BULLISH, DirectionBias.BULLISH,
                        BigDecimal.valueOf(2), BigDecimal.ONE)
        ));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.instId()).isEqualTo("ETH-USDT-SWAP");
        assertThat(confirmService.confirmCount).isEqualTo(1);
    }

    @Test
    void onlyOneAutoTradeExecutionCanRunAtATime() throws Exception {
        BlockingOrderConfirmService confirmService = new BlockingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );
        var executor = Executors.newSingleThreadExecutor();

        Future<AutoTradeService.AutoTradeResult> first = executor.submit(() -> service.evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 95))));
        assertThat(confirmService.entered.await(2, TimeUnit.SECONDS)).isTrue();
        AutoTradeService.AutoTradeResult second = service.evaluateAndExecute(List.of(candidate("ETH-USDT-SWAP", 94)));
        confirmService.release.countDown();
        AutoTradeService.AutoTradeResult firstResult = first.get(2, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(second.status()).isEqualTo("SKIPPED");
        assertThat(second.message()).isEqualTo("AUTO_TRADE_EXECUTION_ALREADY_RUNNING");
        assertThat(firstResult.status()).isEqualTo("EXECUTED");
    }

    @Test
    void localActiveEntryOnlyBlocksTheSameSymbol() {
        AgentProperties properties = new AgentProperties();
        properties.autoTrade().setMinOpenPositions(3);
        properties.autoTrade().setMaxOpenPositions(3);
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                properties,
                systemControlService,
                new FakeTradeOrderRecordService(Set.of("XAU-USDT-SWAP")),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 95)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
    }

    @Test
    void heldSymbolGetsPositionReasonEvenWithNoActiveOrder() {
        var confirm = new CapturingOrderConfirmService();
        var held = new PositionSummary("AKE-USDT-SWAP", "long", "1", BigDecimal.ONE, BigDecimal.ZERO, "OKX_REAL");
        var result = autoTradeWithMargin(BigDecimal.TEN, BigDecimal.valueOf(1000), confirm, List.of(held))
                .evaluateAndExecute(List.of(candidate("AKE-USDT-SWAP", 95)));
        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("SYMBOL_ALREADY_HELD");
        assertThat(confirm.confirmCount).isZero();
    }

    @Test
    void skipsWhenLocalActiveEntryUsesTheSameSymbol() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(), systemControlService,
                new FakeTradeOrderRecordService(Set.of("BTC-USDT-SWAP")),
                new FakeAutoTradeRecordService(), new CandidateTradePlanService(), new PendingOrderService(120),
                confirmService, new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 95)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("local_active_entry_order");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void skipsWhenOkxCurrentOrderAlreadyOccupiesSameSymbol() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of()),
                new com.example.quant.agent.budget.AutoTradeBudgetService(new AgentProperties()),
                new FixedCurrentOrderSyncService(new OkxCurrentOrderSyncService.SyncResult(
                        1, 1, 2, false, null, List.of("BTC-USDT-SWAP")))
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 95)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("okx_current_active_order");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void currentOrderConsumesPositionCapacityForDifferentSymbol() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(100));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of(
                        new PositionSummary("BTC-USDT-SWAP", "long", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"),
                        new PositionSummary("ETH-USDT-SWAP", "short", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL")
                )),
                new com.example.quant.agent.budget.AutoTradeBudgetService(new AgentProperties()),
                new FixedCurrentOrderSyncService(new OkxCurrentOrderSyncService.SyncResult(
                        1, 1, 2, false, null, List.of("OLD-USDT-SWAP")))
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate("SOL-USDT-SWAP", 95)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("position_capacity_full");
        assertThat(result.message()).contains("configuredMax=3");
        assertThat(result.message()).contains("marketRegime=BULLISH");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void inFlightOrdersDoNotConsumePositionCapacityForDifferentSymbol() {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.autoTrade().setMinOpenPositions(3);
        agentProperties.autoTrade().setMaxOpenPositions(3);
        SequencedOrderConfirmService confirmService = new SequencedOrderConfirmService(List.of(
                OrderExecutionResult.submitted("okx-order-1", "入场委托已提交，等待成交"),
                OrderExecutionResult.submitted("okx-order-2", "入场委托已提交，等待成交"),
                OrderExecutionResult.filled("okx-order-3", "入场已成交")
        ));
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(100));
        AutoTradeService service = new AutoTradeService(
                agentProperties,
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of(
                        new PositionSummary("BTC-USDT-SWAP", "long", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL")
                ))
        );

        service.evaluateAndExecute(List.of(candidate("OLD1-USDT-SWAP", 95), candidate("OLD2-USDT-SWAP", 94)));
        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate("SOL-USDT-SWAP", 93)));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.instId()).isEqualTo("SOL-USDT-SWAP");
        assertThat(confirmService.confirmCount).isEqualTo(3);
    }

    @Test
    void skipsRoundWhenOkxCurrentOrderSyncFails() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of()),
                new com.example.quant.agent.budget.AutoTradeBudgetService(new AgentProperties()),
                new FixedCurrentOrderSyncService(new OkxCurrentOrderSyncService.SyncResult(
                        0, 0, 0, true, "OKX orders unavailable", List.of()))
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 95)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("okx_current_orders_unavailable");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void unresolvedLocalSubmitBlocksNewAutoOrderAfterRestart() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        PendingOrderService pendingOrderService = new PendingOrderService(120);
        PendingOrder unresolved = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, samplePlan());
        unresolved.transition(OrderStatus.PENDING_CONFIRM, OrderStatus.SUBMITTING);
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                pendingOrderService,
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidate("BTC-USDT-SWAP", 95)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("unresolved_local_submit");
        assertThat(confirmService.confirmCount).isZero();
    }

    @Test
    void autoTradeDoesNotReleaseBudgetWhenConfirmThrowsAfterUnknownSubmit() {
        UnknownSubmitOrderConfirmService confirmService = new UnknownSubmitOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AgentProperties agentProperties = new AgentProperties();
        com.example.quant.agent.budget.AutoTradeBudgetService budgetService =
                new com.example.quant.agent.budget.AutoTradeBudgetService(agentProperties);
        AutoTradeService service = new AutoTradeService(
                agentProperties,
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of()),
                budgetService
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 95)));

        assertThat(result.status()).isEqualTo("UNKNOWN_SUBMIT_STATUS");
        assertThat(confirmService.confirmCount).isEqualTo(1);
        assertThat(budgetService.reservedBudget()).isEqualByComparingTo("6.6666668");
        assertThat(budgetService.usedBudget()).isZero();
    }

    @Test
    void autoTradeEntrySubmittedKeepsBudgetReservedAndRecordsEntrySubmitted() {
        EntrySubmittedOrderConfirmService confirmService = new EntrySubmittedOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AgentProperties agentProperties = new AgentProperties();
        com.example.quant.agent.budget.AutoTradeBudgetService budgetService =
                new com.example.quant.agent.budget.AutoTradeBudgetService(agentProperties);
        CapturingAutoTradeRecordService recordService = new CapturingAutoTradeRecordService();
        AutoTradeService service = new AutoTradeService(
                agentProperties,
                systemControlService,
                new FakeTradeOrderRecordService(false),
                recordService,
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of()),
                budgetService
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 95)));

        assertThat(result.status()).isEqualTo("ENTRY_SUBMITTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
        assertThat(budgetService.reservedBudget()).isEqualByComparingTo("6.6666668");
        assertThat(budgetService.usedBudget()).isZero();
        assertThat(recordService.records).extracting(AutoTradeService.AutoTradeResult::status)
                .containsExactly("ENTRY_SUBMITTED");
    }

    @Test
    void doesNotRetrySameSymbolInOneFallbackRound() {
        SequencedOrderConfirmService confirmService = new SequencedOrderConfirmService(List.of(
                OrderExecutionResult.rejected("实时订单簿流动性不足：spread_bps_above_8"),
                OrderExecutionResult.filled("okx-order-2", "入场已成交")
        ));
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of())
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(List.of(
                candidate("BTC-USDT-SWAP", 95),
                candidate("BTC-USDT-SWAP", 94),
                candidate("ETH-USDT-SWAP", 93)
        ));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(result.instId()).isEqualTo("ETH-USDT-SWAP");
        assertThat(confirmService.confirmCount).isEqualTo(2);
    }

    private static AutoTradeService autoTradeWithMargin(BigDecimal marginUsdt, BigDecimal availableBalance,
                                                        CapturingOrderConfirmService confirmService) {
        return autoTradeWithMargin(marginUsdt, availableBalance, confirmService, List.of());
    }

    private static AutoTradeService autoTradeWithMargin(BigDecimal marginUsdt, BigDecimal availableBalance,
                                                        CapturingOrderConfirmService confirmService,
                                                        List<PositionSummary> positions) {
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(marginUsdt);
        return new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new FixedTradePlanService(samplePlan()),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(availableBalance),
                new FixedPositionSnapshotService(positions)
        );
    }

    private static TradingProperties tradingProperties() {
        return new TradingProperties("SEMI_AUTO", true, true, 120, false);
    }

    private static ContractCandidate candidate() {
        return candidate("BTC-USDT-SWAP");
    }

    private static ContractCandidate candidate(String instId) {
        return candidate(instId, 95);
    }

    private static ContractCandidate candidate(String instId, int score) {
        return candidate(instId, score, RiskLevel.LOW, DirectionBias.BULLISH, DirectionBias.BULLISH);
    }

    private static ContractCandidate candidate(String instId, int score, RiskLevel marketRiskLevel,
                                               DirectionBias btcTrend, DirectionBias ethTrend) {
        return candidate(instId, score, marketRiskLevel, btcTrend, ethTrend, BigDecimal.valueOf(2), BigDecimal.ONE);
    }

    private static ContractCandidate candidate(String instId, int score, RiskLevel marketRiskLevel,
                                               DirectionBias btcTrend, DirectionBias ethTrend,
                                               BigDecimal spreadBps, BigDecimal estimatedSlippageBps) {
        String baseCurrency = instId.split("-")[0];
        return new ContractCandidate(
                MarketType.OKX_SWAP,
                instId,
                baseCurrency,
                "USDT",
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(3),
                BigDecimal.valueOf(0.8),
                BigDecimal.valueOf(120_000_000),
                BigDecimal.ONE,
                new BigDecimal("0.0001"),
                BigDecimal.valueOf(100_000_000),
                BigDecimal.ZERO,
                DirectionBias.BULLISH,
                new BigDecimal("0.015"),
                score,
                new ContractFactorScore(20, 11, 12, 3, 8, 10, 8),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(105),
                2,
                new BigDecimal("2.5"),
                spreadBps,
                BigDecimal.valueOf(50_000),
                BigDecimal.valueOf(50_000),
                estimatedSlippageBps,
                btcTrend,
                ethTrend,
                marketRiskLevel,
                List.of(),
                List.of(),
                Instant.now()
        );
    }

    private static ContractSelectionDecision selectedDecision(
            ContractSelectionDecision.DecisionSource source, String instId, DirectionBias direction, String entryMode) {
        return new ContractSelectionDecision(
                source,
                source == ContractSelectionDecision.DecisionSource.AI_WAIT
                        ? ContractSelectionDecision.SelectionAction.WAIT
                        : ContractSelectionDecision.SelectionAction.TRADE,
                instId,
                direction,
                entryMode,
                new BigDecimal("0.75"),
                List.of(new ContractSelectionDecision.RankedCandidate(
                        instId, 1, direction, entryMode, new BigDecimal("0.75"))),
                List.of(), List.of(), "");
    }

    private static ContractCandidate candidateWithClosedCandle(String instId, Instant candle,
                                                               String entryTiming, String lastPrice) {
        ContractCandidate base = candidate(instId, 90);
        ContractKlineAnalysis kline = new ContractKlineAnalysis(
                "UP", "UP", "UP", "ALIGNED", "CONFIRMED",
                new BigDecimal("50"), new BigDecimal("25"), "TREND", entryTiming,
                BigDecimal.ONE, new BigDecimal("2"), BigDecimal.ZERO, true, candle);
        return new ContractCandidate(
                base.marketType(), base.instId(), base.baseCurrency(), base.quoteCurrency(),
                new BigDecimal(lastPrice), base.changePercent24h(), base.changePercent5m(), base.volume24h(),
                base.volumeSpikeRatio(), base.fundingRate(), base.openInterest(), base.openInterestChange(),
                base.trendDirection(), base.volatility(), base.score(), base.factorScore(), new BigDecimal(lastPrice),
                base.stopLossPrice(), base.takeProfitPrice(), base.suggestedLeverage(), base.riskRewardRatio(),
                base.spreadBps(), base.bidDepthUsdt(), base.askDepthUsdt(), base.estimatedSlippageBps(),
                base.btcTrend(), base.ethTrend(), base.marketRiskLevel(), base.candidateReasonList(), base.riskTagList(),
                candle, base.signalType(), base.action(), entryTiming, base.todayChangePct(), base.atrPct20m(),
                base.stopLossPct(), base.finalRankScore(), base.scoreBreakdown(), kline, base.newsAnalysis());
    }

    private static ContractCandidate candidateWithAction(String instId, int score, String action) {
        ContractCandidate base = candidate(instId, score);
        return candidateFrom(base, score, action, base.finalRankScore());
    }

    private static ContractCandidate candidateWithActionAndFinalRankScore(String instId, int score, String action,
                                                                          BigDecimal finalRankScore) {
        ContractCandidate base = candidate(instId, score);
        return candidateFrom(base, score, action, finalRankScore);
    }

    private static ContractCandidate candidateWithNewsRisk(ContractCandidate base, ContractNewsRiskAnalysis newsRiskAnalysis) {
        int score = base.score();
        return new ContractCandidate(
                base.marketType(),
                base.instId(),
                base.baseCurrency(),
                base.quoteCurrency(),
                base.lastPrice(),
                base.changePercent24h(),
                base.changePercent5m(),
                base.volume24h(),
                base.volumeSpikeRatio(),
                base.fundingRate(),
                base.openInterest(),
                base.openInterestChange(),
                base.trendDirection(),
                base.volatility(),
                score,
                base.factorScore(),
                base.entryPrice(),
                base.stopLossPrice(),
                base.takeProfitPrice(),
                base.suggestedLeverage(),
                base.riskRewardRatio(),
                base.spreadBps(),
                base.bidDepthUsdt(),
                base.askDepthUsdt(),
                base.estimatedSlippageBps(),
                base.btcTrend(),
                base.ethTrend(),
                base.marketRiskLevel(),
                base.candidateReasonList(),
                base.riskTagList(),
                base.createdAt(),
                base.signalType(),
                base.action(),
                base.entryType(),
                base.todayChangePct(),
                base.atrPct20m(),
                base.stopLossPct(),
                base.finalRankScore(),
                base.scoreBreakdown(),
                base.klineAnalysis(),
                newsRiskAnalysis
        );
    }

    private static ContractCandidate candidateFrom(ContractCandidate base, int score, String action,
                                                   BigDecimal finalRankScore) {
        return new ContractCandidate(
                base.marketType(),
                base.instId(),
                base.baseCurrency(),
                base.quoteCurrency(),
                base.lastPrice(),
                base.changePercent24h(),
                base.changePercent5m(),
                base.volume24h(),
                base.volumeSpikeRatio(),
                base.fundingRate(),
                base.openInterest(),
                base.openInterestChange(),
                base.trendDirection(),
                base.volatility(),
                score,
                base.factorScore(),
                base.entryPrice(),
                base.stopLossPrice(),
                base.takeProfitPrice(),
                base.suggestedLeverage(),
                base.riskRewardRatio(),
                base.spreadBps(),
                base.bidDepthUsdt(),
                base.askDepthUsdt(),
                base.estimatedSlippageBps(),
                base.btcTrend(),
                base.ethTrend(),
                base.marketRiskLevel(),
                base.candidateReasonList(),
                base.riskTagList(),
                base.createdAt(),
                base.signalType(),
                action,
                base.entryType(),
                base.todayChangePct(),
                base.atrPct20m(),
                base.stopLossPct(),
                finalRankScore,
                base.scoreBreakdown(),
                base.klineAnalysis(),
                base.newsAnalysis()
        );
    }

    private static TradePlan samplePlan() {
        return samplePlan("BTC-USDT-SWAP");
    }

    private static TradePlan samplePlan(String instId) {
        return new TradePlan(
                UUID.randomUUID(),
                instId,
                TradePlanType.OPEN_LONG,
                "LIMIT",
                DirectionBias.BULLISH,
                BigDecimal.valueOf(0.8),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(105),
                2,
                BigDecimal.ONE,
                BigDecimal.valueOf(250),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(2),
                95,
                new BigDecimal("0.0001"),
                new BigDecimal("0.015"),
                BigDecimal.valueOf(120_000_000),
                "cross",
                List.of(),
                List.of(),
                "invalid",
                true,
                Instant.now().plusSeconds(120)
        );
    }

    private static class FakeTradeOrderRecordService extends TradeOrderRecordService {
        private final Set<String> activeEntryInstIds;
        private final List<PositionSummary> activeEntryExposures;

        FakeTradeOrderRecordService(boolean activeEntryOrder) {
            this(activeEntryOrder ? Set.of("BTC-USDT-SWAP") : Set.of());
        }

        FakeTradeOrderRecordService(Set<String> activeEntryInstIds) {
            super(null, null);
            this.activeEntryInstIds = activeEntryInstIds;
            this.activeEntryExposures = activeEntryInstIds.stream()
                    .map(instId -> new PositionSummary(instId, "long", "1", BigDecimal.ONE, BigDecimal.ZERO, "LOCAL_ACTIVE_ENTRY"))
                    .toList();
        }

        FakeTradeOrderRecordService(List<PositionSummary> activeEntryExposures) {
            super(null, null);
            this.activeEntryExposures = activeEntryExposures;
            this.activeEntryInstIds = activeEntryExposures.stream()
                    .map(PositionSummary::instId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        @Override
        public Set<String> activeEntryInstIds() {
            return activeEntryInstIds;
        }

        @Override
        public List<PositionSummary> activeEntryExposures() {
            return activeEntryExposures;
        }
    }

    private static class FakeAutoTradeRecordService extends AutoTradeRecordService {
        FakeAutoTradeRecordService() {
            super(null);
        }

        @Override
        public void record(AutoTradeService.AutoTradeResult result, int candidateCount,
                           com.example.quant.crypto.dto.ContractCandidate candidate) {
        }
    }

    private static class CapturingAutoTradeRecordService extends AutoTradeRecordService {
        private final List<AutoTradeService.AutoTradeResult> records = new ArrayList<>();

        CapturingAutoTradeRecordService() {
            super(null);
        }

        @Override
        public void record(AutoTradeService.AutoTradeResult result, int candidateCount,
                           com.example.quant.crypto.dto.ContractCandidate candidate) {
            records.add(result);
        }
    }

    private static class CapturingSelectionRecordService extends FakeAutoTradeRecordService {
        private ContractSelectionDecision selection;
        private ContractCandidate candidate;

        @Override
        public void record(AutoTradeService.AutoTradeResult result, int candidateCount,
                           ContractCandidate candidate, ContractSelectionDecision selection) {
            this.selection = selection;
            this.candidate = candidate;
        }
    }

    private static class FixedCurrentOrderSyncService extends OkxCurrentOrderSyncService {
        private final SyncResult result;

        FixedCurrentOrderSyncService(SyncResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public SyncResult syncOnce() {
            return result;
        }
    }

    private static class FailingTradePlanService extends TradePlanService {
        FailingTradePlanService() {
            super(null);
        }

        @Override
        public TradePlan createContractPlan(String instId) {
            throw new AssertionError("trade plan should not be created");
        }

        @Override
        public TradePlan createContractPlan(ContractCandidate candidate) {
            throw new AssertionError("trade plan should not be created");
        }
    }

    private static class FixedTradePlanService extends TradePlanService {
        private final TradePlan plan;

        FixedTradePlanService(TradePlan plan) {
            super(null);
            this.plan = plan;
        }

        @Override
        public TradePlan createContractPlan(String instId) {
            return plan;
        }

        @Override
        public TradePlan createContractPlan(ContractCandidate candidate) {
            return plan;
        }
    }

    private static class CandidateTradePlanService extends TradePlanService {
        CandidateTradePlanService() {
            super(null);
        }

        @Override
        public TradePlan createContractPlan(ContractCandidate candidate) {
            return samplePlan(candidate.instId());
        }

        @Override
        public TradePlan createSelectedContractPlan(ContractCandidate candidate, ContractSelectionDecision selection) {
            return samplePlan(candidate.instId());
        }
    }

    private static class MetadataTradePlanService extends CandidateTradePlanService {
        private TradePlan capturedPlan;
        @Override
        public TradePlan createSelectedContractPlan(ContractCandidate candidate, ContractSelectionDecision selection) {
            if ("HYPE-USDT-SWAP".equals(candidate.instId())) {
                return new TradePlan(UUID.randomUUID(), candidate.instId(), TradePlanType.WATCH, "NO_ENTRY",
                        DirectionBias.NEUTRAL, selection.confidence(), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, "cross", selection.reasonList(), selection.riskList(), "reject", false, Instant.now());
            }
            capturedPlan = new TradePlan(UUID.randomUUID(), candidate.instId(), TradePlanType.OPEN_SHORT, "MARKET",
                    DirectionBias.BEARISH, selection.confidence(), BigDecimal.valueOf(100), BigDecimal.valueOf(101), BigDecimal.valueOf(98),
                    1, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.valueOf(2), 90, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.valueOf(1000000), "cross", selection.reasonList(), selection.riskList(), "", false, Instant.now());
            return capturedPlan;
        }
    }

    private static class WatchSelectedTradePlanService extends CandidateTradePlanService {
        @Override
        public TradePlan createSelectedContractPlan(ContractCandidate candidate, ContractSelectionDecision selection) {
            TradePlan base = samplePlan(candidate.instId());
            return new TradePlan(
                    base.id(), base.instId(), TradePlanType.WATCH, "NO_ENTRY", base.directionBias(), base.confidence(),
                    base.entryPrice(), base.stopLossPrice(), base.takeProfitPrice(), base.suggestedLeverage(),
                    base.suggestedSize(), base.suggestedMargin(), base.maxLossAmount(), base.riskRewardRatio(),
                    base.signalScore(), base.fundingRate(), base.volatility(), base.volume24h(), base.tdMode(),
                    base.reasonList(), base.riskList(), base.invalidCondition(), base.needUserConfirm(), base.expireTime());
        }
    }

    private static class FirstExecutableThenWatchTradePlanService extends CandidateTradePlanService {
        @Override
        public TradePlan createSelectedContractPlan(ContractCandidate candidate, ContractSelectionDecision selection) {
            if ("BTC-USDT-SWAP".equals(candidate.instId())) {
                return samplePlan(candidate.instId());
            }
            TradePlan base = samplePlan(candidate.instId());
            return new TradePlan(
                    base.id(), base.instId(), TradePlanType.WATCH, "NO_ENTRY", base.directionBias(), base.confidence(),
                    base.entryPrice(), base.stopLossPrice(), base.takeProfitPrice(), base.suggestedLeverage(),
                    base.suggestedSize(), base.suggestedMargin(), base.maxLossAmount(), base.riskRewardRatio(),
                    base.signalScore(), base.fundingRate(), base.volatility(), base.volume24h(), base.tdMode(),
                    base.reasonList(), base.riskList(), base.invalidCondition(), base.needUserConfirm(), base.expireTime());
        }
    }

    private static class NoRiskCandidateTradePlanService extends CandidateTradePlanService {
        @Override
        public TradePlan createNoRiskContractPlan(ContractCandidate candidate) {
            return samplePlan(candidate.instId());
        }
    }

    private static class WideStopNoRiskTradePlanService extends NoRiskCandidateTradePlanService {
        @Override
        public TradePlan createNoRiskContractPlan(ContractCandidate candidate) {
            return new TradePlan(
                    UUID.randomUUID(),
                    candidate.instId(),
                    TradePlanType.OPEN_SHORT,
                    "LIMIT",
                    DirectionBias.BEARISH,
                    BigDecimal.valueOf(0.8),
                    BigDecimal.valueOf(100),
                    BigDecimal.valueOf(199),
                    BigDecimal.valueOf(70),
                    2,
                    BigDecimal.ONE,
                    BigDecimal.valueOf(250),
                    BigDecimal.valueOf(10),
                    BigDecimal.valueOf(2),
                    70,
                    new BigDecimal("0.0001"),
                    new BigDecimal("0.015"),
                    BigDecimal.valueOf(120_000_000),
                    "cross",
                    List.of(),
                    List.of(),
                    "invalid",
                    true,
                    Instant.now().plusSeconds(120)
            );
        }
    }

    private static class CapturingOrderConfirmService extends com.example.quant.order.OrderConfirmService {
        protected BigDecimal capturedMargin;
        protected int confirmCount;
        protected final List<BigDecimal> capturedMargins = new ArrayList<>();

        CapturingOrderConfirmService() {
            super(null, null, null);
        }

        @Override
        public OrderExecutionResult confirmAuto(UUID orderId, BigDecimal marginAmount) {
            this.capturedMargin = marginAmount;
            this.confirmCount++;
            this.capturedMargins.add(marginAmount);
            return OrderExecutionResult.filled("okx-order-1", "入场已成交");
        }
    }

    private static class CapturingPendingOrderService extends PendingOrderService {
        private String capturedClientOrderId;

        CapturingPendingOrderService() {
            super(120);
        }

        @Override
        public com.example.quant.order.PendingOrder createAutoPendingOrder(MarketType marketType, TradePlan plan,
                                                                           UUID pendingOrderId,
                                                                           BigDecimal orderMarginUsdt,
                                                                           UUID budgetReservationId,
                                                                           com.example.quant.agent.budget.BudgetAllocation allocation,
                                                                           String clientOrderId) {
            this.capturedClientOrderId = clientOrderId;
            return super.createAutoPendingOrder(marketType, plan, pendingOrderId, orderMarginUsdt,
                    budgetReservationId, allocation, clientOrderId);
        }
    }

    private static class SequencedOrderConfirmService extends CapturingOrderConfirmService {
        private final List<OrderExecutionResult> results;

        SequencedOrderConfirmService(List<OrderExecutionResult> results) {
            this.results = new ArrayList<>(results);
        }

        @Override
        public OrderExecutionResult confirmAuto(UUID orderId, BigDecimal marginAmount) {
            this.capturedMargin = marginAmount;
            this.confirmCount++;
            this.capturedMargins.add(marginAmount);
            if (results.isEmpty()) {
                return OrderExecutionResult.rejected("no_result_configured");
            }
            return results.remove(0);
        }
    }

    private static class BlockingOrderConfirmService extends CapturingOrderConfirmService {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public OrderExecutionResult confirmAuto(UUID orderId, BigDecimal marginAmount) {
            this.capturedMargin = marginAmount;
            this.confirmCount++;
            this.capturedMargins.add(marginAmount);
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return OrderExecutionResult.filled("okx-order-1", "入场已成交");
        }
    }

    private static class UnknownSubmitOrderConfirmService extends CapturingOrderConfirmService {
        @Override
        public OrderExecutionResult confirmAuto(UUID orderId, BigDecimal marginAmount) {
            this.capturedMargin = marginAmount;
            this.confirmCount++;
            this.capturedMargins.add(marginAmount);
            throw new OrderSubmitStatusUnknownException("OKX submit timed out");
        }
    }

    private static class EntrySubmittedOrderConfirmService extends CapturingOrderConfirmService {
        @Override
        public OrderExecutionResult confirmAuto(UUID orderId, BigDecimal marginAmount) {
            this.capturedMargin = marginAmount;
            this.confirmCount++;
            this.capturedMargins.add(marginAmount);
            return OrderExecutionResult.submitted("okx-order-1", "入场委托已提交，等待成交");
        }
    }

    private static class FixedAccountSnapshotService extends AccountSnapshotService {
        private final BigDecimal equity;
        private final BigDecimal availableBalance;

        FixedAccountSnapshotService(BigDecimal availableBalance) {
            this(availableBalance.max(BigDecimal.valueOf(1000)), availableBalance);
        }

        FixedAccountSnapshotService(BigDecimal equity, BigDecimal availableBalance) {
            super(null);
            this.equity = equity;
            this.availableBalance = availableBalance;
        }

        @Override
        public AccountSummary summary() {
            return new AccountSummary(equity, availableBalance, "OKX_REAL", "ok");
        }
    }

    private static class FailingAccountSnapshotService extends AccountSnapshotService {
        FailingAccountSnapshotService() {
            super(null);
        }

        @Override
        public AccountSummary summary() {
            throw new IllegalStateException("OKX account unavailable");
        }
    }

    private static class FailingPositionSnapshotService extends PositionSnapshotService {
        FailingPositionSnapshotService() {
            super(null);
        }

        @Override
        public List<PositionSummary> positions() {
            throw new IllegalStateException("OKX positions unavailable");
        }
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
}

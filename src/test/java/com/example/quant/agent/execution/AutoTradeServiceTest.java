package com.example.quant.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.OrderSubmitStatusUnknownException;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AutoTradeServiceTest {

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
    void treatsConfiguredAutoTradeMarginAsTotalBudgetNotPerOrderMargin() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(BigDecimal.valueOf(50), BigDecimal.valueOf(1000), confirmService)
                .evaluateAndExecute(List.of(candidate()));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.capturedMargin).isEqualByComparingTo("22.5");
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
        assertThat(confirmService.capturedMargin).isEqualByComparingTo("5.0");
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
        assertThat(confirmService.capturedMargins.get(0)).isEqualByComparingTo("6.0");
        assertThat(confirmService.capturedMargins.get(1)).isEqualByComparingTo("5.0");
    }

    @Test
    void allocatesThreePositionsFromOneTotalBudgetBySlotAndScore() {
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
        assertThat(confirmService.capturedMargins.get(0)).isEqualByComparingTo("22.5");
        assertThat(confirmService.capturedMargins.get(1)).isEqualByComparingTo("15.0");
        assertThat(confirmService.capturedMargins.get(2)).isEqualByComparingTo("12.5");
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
    void noRiskModeAllowsScore70CandidateWithoutAutoTradeAllowedAction() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.NO_RISK);
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
                List.of(candidateWithAction("BTC-USDT-SWAP", 70, "WAIT_CONFIRM")));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
        assertThat(confirmService.capturedMargin).isEqualByComparingTo("9.0");
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

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
        assertThat(confirmService.capturedMargin).isGreaterThanOrEqualTo(new BigDecimal("5"));
    }

    @Test
    void noRiskModeDoesNotBlockCandidateWhenNewsRiskIsUnknown() {
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

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
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
    void strictModeDoesNotBlockUnknownNewsRiskWhenScoreIsQualified() {
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

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
    }

    @Test
    void noRiskModeUsesDisplayedScoreInsteadOfFinalRankScoreForMinimumGateAndBudget() {
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

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
        assertThat(confirmService.capturedMargin).isGreaterThanOrEqualTo(new BigDecimal("5"));
    }

    @Test
    void autoTradeClientOrderIdUsesOkxAcceptedAlphanumericFormat() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        CapturingPendingOrderService pendingOrderService = new CapturingPendingOrderService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20), AutoTradeRiskMode.NO_RISK, 69);
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
                List.of(candidateWithAction("DOGE-USDT-SWAP", 69, "WAIT_CONFIRM")));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(pendingOrderService.capturedClientOrderId)
                .matches("[A-Za-z0-9]{1,32}")
                .doesNotContain("_");
    }

    @Test
    void noRiskModeDoesNotShrinkThirdSlotByRiskBasedMarginLimit() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50), AutoTradeRiskMode.NO_RISK, 65);
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(false),
                new FakeAutoTradeRecordService(),
                new WideStopNoRiskTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
                new FixedPositionSnapshotService(List.of(
                        new PositionSummary("DOGE-USDT-SWAP", "short", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"),
                        new PositionSummary("BONK-USDT-SWAP", "short", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL")
                ))
        );

        AutoTradeService.AutoTradeResult result = service.evaluateAndExecute(
                List.of(candidateWithAction("PIPPIN-USDT-SWAP", 70, "WAIT_CONFIRM")));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
        assertThat(confirmService.capturedMargin).isEqualByComparingTo("12.5");
    }

    @Test
    void noRiskModeKeepsConfiguredBudgetWhenAvailableBalanceDropsAfterTwoPositions() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(50), AutoTradeRiskMode.NO_RISK, 65);
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
                new NoRiskCandidateTradePlanService(),
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
                List.of(candidateWithAction("BABY-USDT-SWAP", 79, "WAIT_CONFIRM")));

        assertThat(result.status()).isEqualTo("EXECUTED");
        assertThat(confirmService.confirmCount).isEqualTo(1);
        assertThat(confirmService.capturedMargin).isEqualByComparingTo("12.5");
    }

    @Test
    void rejectsScore81WhenOnePositionIsOpenBecauseQualityThresholdRises() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(1000),
                        confirmService,
                        List.of(new PositionSummary("ETH-USDT-SWAP", "long", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"))
                )
                .evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 81)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("position_quality_score_below_84");
        assertThat(confirmService.confirmCount).isZero();
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
    void doesNotOpenNewPositionWhenChoppyRegimeAlreadyHasOnePosition() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();

        AutoTradeService.AutoTradeResult result = autoTradeWithMargin(
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(1000),
                        confirmService,
                        List.of(new PositionSummary("ETH-USDT-SWAP", "long", "1", BigDecimal.valueOf(100), BigDecimal.ZERO, "OKX_REAL"))
                )
                .evaluateAndExecute(List.of(candidate("BTC-USDT-SWAP", 95, RiskLevel.LOW,
                        DirectionBias.NEUTRAL, DirectionBias.NEUTRAL)));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("dynamicMax=1");
        assertThat(confirmService.confirmCount).isZero();
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
                new OrderExecutionResult(false, false, null, "实时订单簿流动性不足：spread_bps_above_8"),
                new OrderExecutionResult(true, true, "okx-order-2", "submitted")
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
    void skipsWhenLocalActiveEntryOrderExists() {
        CapturingOrderConfirmService confirmService = new CapturingOrderConfirmService();
        SystemControlService systemControlService = new SystemControlService(tradingProperties());
        systemControlService.enableAutoTrade(BigDecimal.valueOf(20));
        AutoTradeService service = new AutoTradeService(
                new AgentProperties(),
                systemControlService,
                new FakeTradeOrderRecordService(true),
                new FakeAutoTradeRecordService(),
                new CandidateTradePlanService(),
                new PendingOrderService(120),
                confirmService,
                new FixedAccountSnapshotService(BigDecimal.valueOf(1000)),
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
        assertThat(budgetService.reservedBudget()).isEqualByComparingTo("9");
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
        assertThat(budgetService.reservedBudget()).isEqualByComparingTo("9");
        assertThat(budgetService.usedBudget()).isZero();
        assertThat(recordService.records).extracting(AutoTradeService.AutoTradeResult::status)
                .containsExactly("ENTRY_SUBMITTED");
    }

    @Test
    void doesNotRetrySameSymbolInOneFallbackRound() {
        SequencedOrderConfirmService confirmService = new SequencedOrderConfirmService(List.of(
                new OrderExecutionResult(false, false, null, "实时订单簿流动性不足：spread_bps_above_8"),
                new OrderExecutionResult(true, true, "okx-order-2", "submitted")
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
        private final boolean activeEntryOrder;

        FakeTradeOrderRecordService(boolean activeEntryOrder) {
            super(null, null);
            this.activeEntryOrder = activeEntryOrder;
        }

        @Override
        public boolean hasActiveEntryOrder() {
            return activeEntryOrder;
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
            return new OrderExecutionResult(true, true, "okx-order-1", "submitted");
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
                return new OrderExecutionResult(false, false, null, "no_result_configured");
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
            return new OrderExecutionResult(true, true, "okx-order-1", "submitted");
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
            return new OrderExecutionResult(true, true, "okx-order-1", "入场委托已提交，等待成交",
                    true, false, false, false);
        }
    }

    private static class FixedAccountSnapshotService extends AccountSnapshotService {
        private final BigDecimal availableBalance;

        FixedAccountSnapshotService(BigDecimal availableBalance) {
            super(null);
            this.availableBalance = availableBalance;
        }

        @Override
        public AccountSummary summary() {
            return new AccountSummary(availableBalance, availableBalance, "OKX_REAL", "ok");
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

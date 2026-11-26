package com.example.quant.tradeplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.agent.plan.TradePlanRecordService;
import com.example.quant.agent.selection.ContractSelectionDecision;
import com.example.quant.ai.AiAnalysisService;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.AiProperties;
import com.example.quant.crypto.ContractAnalysisService;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.leverage.LeverageDecisionService;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.position.PositionSizingService;
import com.example.quant.risk.RiskCheckResult;
import com.example.quant.risk.RiskLevel;
import com.example.quant.risk.RiskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractTradePlanBuilderTest {

    @Test
    void requestedInstIdMissingFromCandidatesIsRejectedInsteadOfSilentTopPick() {
        ContractCandidate sol = autoTradeCandidate("SOL-USDT-SWAP", 90);
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                candidateSnapshot(List.of(sol)),
                new LimitOrderAiAnalysisService(),
                new FixedAccountSnapshotService(),
                new PositionSizingService(),
                new LeverageDecisionService(),
                passingRiskService(),
                new AgentProperties(),
                new NoopTradePlanRecordService()
        );

        assertThatThrownBy(() -> builder.buildPlan("BTC-USDT-SWAP"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested_inst_id_not_in_candidates")
                .hasMessageContaining("BTC-USDT-SWAP");
    }

    @Test
    void buildTopCandidatePlanUsesPublishedOrderInsteadOfResortingByOldScore() {
        ContractCandidate sol = autoTradeCandidate("SOL-USDT-SWAP", 70);
        ContractCandidate eth = autoTradeCandidate("ETH-USDT-SWAP", 90);
        TradePlanRecordService records = mock(TradePlanRecordService.class);
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                candidateSnapshot(List.of(sol, eth)),
                new LimitOrderAiAnalysisService(),
                new FixedAccountSnapshotService(),
                new PositionSizingService(),
                new LeverageDecisionService(),
                passingRiskService(),
                new AgentProperties(),
                records
        );

        TradePlan plan = builder.buildTopCandidatePlan();

        assertThat(plan.instId()).isEqualTo("SOL-USDT-SWAP");
        verify(records).record(any(), any(), any(), argThat(payload ->
                payload instanceof Map<?, ?> audit
                        && ContractScoreBreakdown.weightSummary().equals(audit.get("scoreWeights"))));
    }

    @Test
    void aiPrimarySelectionWithOppositeDirectionFailsClosedInsteadOfMirroringStopDistance() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null,
                new LimitOrderAiAnalysisService(),
                new FixedAccountSnapshotService(),
                new PositionSizingService(),
                new LeverageDecisionService(),
                passingRiskService(),
                new AgentProperties(),
                new NoopTradePlanRecordService()
        );
        ContractSelectionDecision selection = new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE,
                "BTC-USDT-SWAP",
                DirectionBias.BEARISH,
                "MARKET",
                new BigDecimal("0.82"),
                List.of(new ContractSelectionDecision.RankedCandidate(
                        "BTC-USDT-SWAP", 1, DirectionBias.BEARISH, "MARKET", new BigDecimal("0.82"))),
                List.of("AI选择空头"),
                List.of("新闻不可用"),
                ""
        );

        TradePlan plan = builder.buildSelectedPlan(autoTradeCandidate(), selection);

        assertThat(plan.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(plan.orderType()).isEqualTo("NO_ENTRY");
        assertThat(plan.suggestedMargin()).isZero();
        assertThat(plan.riskList()).anyMatch(reason -> reason.contains("DIRECTION_MISMATCH"));
        assertThat(plan.reasonList()).anyMatch(reason -> reason.contains("candidateOriginalSide=BULLISH"));
    }

    @Test
    void aiPrimarySelectionWithSameDirectionKeepsExecutablePlanAndRecordsProvenance() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null,
                new LimitOrderAiAnalysisService(),
                new FixedAccountSnapshotService(),
                new PositionSizingService(),
                new LeverageDecisionService(),
                passingRiskService(),
                new AgentProperties(),
                new NoopTradePlanRecordService()
        );
        ContractSelectionDecision selection = new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE,
                "BTC-USDT-SWAP",
                DirectionBias.BULLISH,
                "MARKET",
                new BigDecimal("0.82"),
                List.of(), List.of("AI选择多头"), List.of(), ""
        );

        TradePlan plan = builder.buildSelectedPlan(autoTradeCandidate(), selection);

        assertThat(plan.action()).isEqualTo(TradePlanType.OPEN_LONG);
        assertThat(plan.reasonList()).anyMatch(reason -> reason.contains("candidateOriginalSide=BULLISH"));
        assertThat(plan.reasonList()).anyMatch(reason -> reason.contains("jevSelectedSide=BULLISH"));
        assertThat(plan.reasonList()).anyMatch(reason -> reason.contains("directionReanalysisPerformed=false"));
        assertThat(plan.reasonList()).anyMatch(reason -> reason.contains("stopDistanceSource=JAVA_CANDIDATE_DIRECTION_MATCH"));
    }

    @Test
    void aiPrimaryShortSelectionWithSameDirectionKeepsShortPlan() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null, new LimitOrderAiAnalysisService(), new FixedAccountSnapshotService(),
                new PositionSizingService(), new LeverageDecisionService(), passingRiskService(),
                new AgentProperties(), new NoopTradePlanRecordService());
        ContractSelectionDecision selection = new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE, "BTC-USDT-SWAP", DirectionBias.BEARISH,
                "MARKET", new BigDecimal("0.82"), List.of(), List.of("AI选择空头"), List.of(), "");

        TradePlan plan = builder.buildSelectedPlan(shortCandidate(), selection);

        assertThat(plan.action()).isEqualTo(TradePlanType.OPEN_SHORT);
        assertThat(plan.directionBias()).isEqualTo(DirectionBias.BEARISH);
        assertThat(plan.stopLossPrice()).isGreaterThan(plan.entryPrice());
        assertThat(plan.takeProfitPrice()).isLessThan(plan.entryPrice());
    }

    @Test
    void aiPrimaryLowScoreCandidateIsRejectedByFinalJavaHardGuard() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null, new LimitOrderAiAnalysisService(), new FixedAccountSnapshotService(),
                new PositionSizingService(), new LeverageDecisionService(),
                new com.example.quant.risk.ContractRiskService(), new AgentProperties(),
                new NoopTradePlanRecordService()
        );
        ContractSelectionDecision selection = new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE,
                "BTC-USDT-SWAP", DirectionBias.BULLISH, "MARKET", new BigDecimal("0.82"),
                List.of(), List.of("AI选择多头"), List.of(), ""
        );

        TradePlan plan = builder.buildSelectedPlan(lowScoreCandidate(), selection);

        assertThat(plan.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(plan.suggestedMargin()).isZero();
        assertThat(plan.riskList()).anyMatch(reason -> reason.contains("total_score_below_60"));
    }

    @Test
    void finalJavaHardGuardRejectsWideRebuiltStopAndHighNewsBeforeSizing() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null, new LimitOrderAiAnalysisService(), new FixedAccountSnapshotService(),
                new PositionSizingService(), new LeverageDecisionService(), passingRiskService(),
                new AgentProperties(), new NoopTradePlanRecordService());
        ContractSelectionDecision selection = longSelection();

        TradePlan wideStop = builder.buildSelectedPlan(
                candidateWithPrices(autoTradeCandidate(), "100", "94", "110"), selection);
        TradePlan highNews = builder.buildSelectedPlan(
                candidateWithNews(autoTradeCandidate(), ContractNewsRiskAnalysis.critical("test")), selection);

        assertThat(wideStop.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(wideStop.riskList()).anyMatch(reason -> reason.contains("final_stop_loss_pct_above_5"));
        assertThat(highNews.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(highNews.riskList()).anyMatch(reason -> reason.contains("news_risk_critical"));
    }

    private static ContractSelectionDecision longSelection() {
        return new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE,
                "BTC-USDT-SWAP", DirectionBias.BULLISH, "MARKET", new BigDecimal("0.82"),
                List.of(), List.of("AI选择多头"), List.of(), "");
    }

    @Test
    void ruleFallbackDoesNotCallAiAgain() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null,
                new FailingIfCalledAiAnalysisService(),
                new FixedAccountSnapshotService(),
                new PositionSizingService(),
                new LeverageDecisionService(),
                passingRiskService(),
                new AgentProperties(),
                new NoopTradePlanRecordService()
        );

        TradePlan plan = builder.buildRuleFallbackPlan(autoTradeCandidate());

        assertThat(plan.action()).isEqualTo(TradePlanType.OPEN_LONG);
    }

    @Test
    void forcesMarketEntryForAutoTradeEvenWhenAiSuggestsLimit() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null,
                new LimitOrderAiAnalysisService(),
                new FixedAccountSnapshotService(),
                new PositionSizingService(),
                new LeverageDecisionService(),
                passingRiskService(),
                new AgentProperties(),
                new NoopTradePlanRecordService()
        );

        TradePlan plan = builder.buildPlan(autoTradeCandidate());

        assertThat(plan.action()).isEqualTo(TradePlanType.OPEN_LONG);
        assertThat(plan.orderType()).isEqualTo("MARKET");
    }

    @Test
    void aiCannotOverrideDeterministicDirectionPricesOrLeverage() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null,
                new ReverseOrderAiAnalysisService(),
                new FixedAccountSnapshotService(),
                new PositionSizingService(),
                new LeverageDecisionService(),
                passingRiskService(),
                new AgentProperties(),
                new NoopTradePlanRecordService()
        );

        TradePlan plan = builder.buildPlan(autoTradeCandidate());

        assertThat(plan.action()).isEqualTo(TradePlanType.OPEN_LONG);
        assertThat(plan.directionBias()).isEqualTo(DirectionBias.BULLISH);
        assertThat(plan.entryPrice()).isEqualByComparingTo("100");
        assertThat(plan.stopLossPrice()).isEqualByComparingTo("98");
        assertThat(plan.takeProfitPrice()).isEqualByComparingTo("105");
        assertThat(plan.suggestedLeverage()).isLessThanOrEqualTo(3);
    }

    @Test
    void invalidLongPriceGeometryProducesWatchInsteadOfMinimumRiskReward() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null,
                new LimitOrderAiAnalysisService(),
                new FixedAccountSnapshotService(),
                new PositionSizingService(),
                new LeverageDecisionService(),
                passingRiskService(),
                new AgentProperties(),
                new NoopTradePlanRecordService()
        );

        TradePlan plan = builder.buildPlan(candidateWithPrices(autoTradeCandidate(), "100", "102", "105"));

        assertThat(plan.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(plan.suggestedMargin()).isZero();
        assertThat(plan.riskRewardRatio()).isZero();
    }

    @Test
    void ruleRiskRejectionCannotContinueAsExecutablePlan() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null, new LimitOrderAiAnalysisService(), new FixedAccountSnapshotService(),
                new PositionSizingService(), new LeverageDecisionService(),
                request -> RiskCheckResult.rejected("TEST_RISK_BLOCK", "hard risk rejected"),
                new AgentProperties(), new NoopTradePlanRecordService()
        );

        TradePlan plan = builder.buildPlan(autoTradeCandidate());

        assertThat(plan.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(plan.suggestedMargin()).isZero();
    }

    @Test
    void unavailableAccountCannotUseFallbackEquityForExecutablePlan() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null, new LimitOrderAiAnalysisService(), new UnavailableAccountSnapshotService(),
                new PositionSizingService(), new LeverageDecisionService(), passingRiskService(),
                new AgentProperties(), new NoopTradePlanRecordService()
        );

        TradePlan plan = builder.buildPlan(autoTradeCandidate());

        assertThat(plan.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(plan.suggestedMargin()).isZero();
    }

    @Test
    void staleRestCandidateCannotBecomeExecutablePlan() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null, new LimitOrderAiAnalysisService(), new FixedAccountSnapshotService(),
                new PositionSizingService(), new LeverageDecisionService(), new com.example.quant.risk.ContractRiskService(),
                new AgentProperties(), new NoopTradePlanRecordService()
        );

        TradePlan plan = builder.buildPlan(candidateWithCreatedAt(autoTradeCandidate(), Instant.now().minusSeconds(30)));

        assertThat(plan.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(plan.suggestedMargin()).isZero();
    }

    @Test
    void aiPrimaryMarketSelectionDefersFreshnessToFinalOrderBookCheck() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null, new LimitOrderAiAnalysisService(), new FixedAccountSnapshotService(),
                new PositionSizingService(), new LeverageDecisionService(), new com.example.quant.risk.ContractRiskService(),
                new AgentProperties(), new NoopTradePlanRecordService()
        );
        ContractSelectionDecision selection = new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE,
                "BTC-USDT-SWAP",
                DirectionBias.BULLISH,
                "MARKET",
                new BigDecimal("0.82"),
                List.of(),
                List.of("AI主选"),
                List.of(),
                ""
        );

        TradePlan plan = builder.buildSelectedPlan(
                candidateWithCreatedAt(nonAutoTradeCandidate(), Instant.now().minusSeconds(30)), selection);

        assertThat(plan.action()).isEqualTo(TradePlanType.OPEN_LONG);
        assertThat(plan.orderType()).isEqualTo("MARKET");
    }

    @Test
    void returnsWatchPlanWhenSmartEntryRequiresPullback() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null,
                new LimitOrderAiAnalysisService(),
                new FixedAccountSnapshotService(),
                new PositionSizingService(),
                new LeverageDecisionService(),
                passingRiskService(),
                new AgentProperties(),
                new NoopTradePlanRecordService()
        );

        TradePlan plan = builder.buildPlan(autoTradeCandidate(ContractSignalType.PULLBACK_LONG, "WAIT_PULLBACK",
                new BigDecimal("2.4")));

        assertThat(plan.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(plan.orderType()).isEqualTo("NO_ENTRY");
        assertThat(plan.riskList()).anyMatch(reason -> reason.contains("等待回踩"));
    }

    @Test
    void noRiskPlanIsRecordedAsNonExecutableShadowObservation() {
        CapturingTradePlanRecordService records = new CapturingTradePlanRecordService();
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null,
                new LimitOrderAiAnalysisService(),
                new FixedAccountSnapshotService(),
                new PositionSizingService(),
                new LeverageDecisionService(),
                passingRiskService(),
                new AgentProperties(),
                records
        );

        ContractCandidate candidate = nonAutoTradeCandidate();
        TradePlan plan = builder.buildNoRiskPlan(candidate);

        assertThat(plan.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(plan.orderType()).isEqualTo("NO_ENTRY");
        assertThat(plan.suggestedMargin()).isZero();
        assertThat(plan.reasonList()).anyMatch(reason -> reason.contains("Shadow"));
        assertThat(records.lastPayload)
                .containsEntry("strategyVersion", "contract-analysis-s1s2-v2")
                .containsEntry("signalPool", ContractSignalType.STRONG_LONG.name())
                .containsEntry("autoTradeEligible", false)
                .containsEntry("dataTimestamp", candidate.createdAt())
                .containsKey("analysisSource");
    }

    @Test
    void unknownNewsRiskIsRejectedByFinalJavaGate() {
        ContractTradePlanBuilder builder = new ContractTradePlanBuilder(
                null,
                new LimitOrderAiAnalysisService(),
                new FixedAccountSnapshotService(),
                new PositionSizingService(),
                new LeverageDecisionService(),
                passingRiskService(),
                new AgentProperties(),
                new NoopTradePlanRecordService()
        );

        ContractCandidate candidate = candidateWithNews(autoTradeCandidate(),
                ContractNewsRiskAnalysis.unknown("新闻源无返回", 20));
        TradePlan plan = builder.buildPlan(candidate);
        TradePlan jevSelectedPlan = builder.buildSelectedPlan(candidate, longSelection());

        assertThat(plan.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(plan.orderType()).isEqualTo("NO_ENTRY");
        assertThat(plan.riskList()).anyMatch(reason -> reason.contains("news_data_unavailable"));
        assertThat(jevSelectedPlan.action()).isEqualTo(TradePlanType.WATCH);
        assertThat(jevSelectedPlan.riskList()).anyMatch(reason -> reason.contains("news_data_unavailable"));
    }

    private static RiskService passingRiskService() {
        return request -> new RiskCheckResult(
                true,
                RiskLevel.LOW,
                null,
                null,
                List.of(),
                null,
                null,
                BigDecimal.TEN,
                Instant.now()
        );
    }

    private static ContractCandidate autoTradeCandidate() {
        return autoTradeCandidate(ContractSignalType.STRONG_LONG, "READY", new BigDecimal("0.8"));
    }

    private static ContractCandidate autoTradeCandidate(String instId, int score) {
        ContractCandidate base = autoTradeCandidate();
        String baseCurrency = instId.split("-")[0];
        return new ContractCandidate(
                base.marketType(),
                instId,
                baseCurrency,
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
                BigDecimal.valueOf(score),
                base.scoreBreakdown(),
                base.klineAnalysis(),
                base.newsAnalysis()
        );
    }

    private static ContractCandidate shortCandidate() {
        return new ContractCandidate(
                MarketType.OKX_SWAP, "BTC-USDT-SWAP", "BTC", "USDT", BigDecimal.valueOf(100),
                BigDecimal.valueOf(-5), BigDecimal.valueOf(-1), BigDecimal.valueOf(500_000_000),
                BigDecimal.valueOf(1.5), new BigDecimal("0.0001"), BigDecimal.valueOf(200_000_000),
                BigDecimal.ZERO, DirectionBias.BEARISH, new BigDecimal("0.015"), 92,
                new ContractFactorScore(90, 90, 90, 90, 90, 90, 90), BigDecimal.valueOf(100),
                BigDecimal.valueOf(102), BigDecimal.valueOf(95), 3, new BigDecimal("2.5"),
                BigDecimal.valueOf(2), BigDecimal.valueOf(200_000), BigDecimal.valueOf(200_000), BigDecimal.ONE,
                DirectionBias.BEARISH, DirectionBias.BEARISH, RiskLevel.LOW, List.of("20m空头结构清晰"), List.of(),
                Instant.now());
    }

    private static ContractCandidate autoTradeCandidate(ContractSignalType signalType,
                                                        String entryTiming5m,
                                                        BigDecimal distanceFromEma20Pct) {
        return new ContractCandidate(
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                "BTC",
                "USDT",
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(5),
                BigDecimal.valueOf(1),
                BigDecimal.valueOf(500_000_000),
                BigDecimal.valueOf(1.5),
                new BigDecimal("0.0001"),
                BigDecimal.valueOf(200_000_000),
                BigDecimal.ZERO,
                DirectionBias.BULLISH,
                new BigDecimal("0.015"),
                92,
                new ContractFactorScore(90, 90, 90, 90, 90, 90, 90),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(105),
                3,
                new BigDecimal("2.5"),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(200_000),
                BigDecimal.valueOf(200_000),
                BigDecimal.ONE,
                DirectionBias.BULLISH,
                DirectionBias.BULLISH,
                RiskLevel.LOW,
                List.of("20m多头结构清晰"),
                List.of(),
                Instant.now(),
                signalType,
                "AUTO_TRADE_ALLOWED",
                "MARKET",
                BigDecimal.valueOf(5),
                new BigDecimal("1.5"),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(92),
                new ContractScoreBreakdown(92, 90, 90, 90, 90, 90, 90),
                new ContractKlineAnalysis("UP", "UP", "UP", "BULLISH", "STRENGTHENING",
                        BigDecimal.valueOf(62), BigDecimal.valueOf(24), "HIGHER_HIGH_HIGHER_LOW", entryTiming5m,
                        BigDecimal.valueOf(1.5), new BigDecimal("1.5"), distanceFromEma20Pct, true),
                ContractNewsRiskAnalysis.low()
        );
    }

    private static ContractCandidate nonAutoTradeCandidate() {
        ContractCandidate base = autoTradeCandidate();
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
                70,
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
                "WAIT_CONFIRM",
                base.entryType(),
                base.todayChangePct(),
                base.atrPct20m(),
                base.stopLossPct(),
                BigDecimal.valueOf(70),
                new ContractScoreBreakdown(70, 70, 70, 70, 70, 70, 70),
                base.klineAnalysis(),
                base.newsAnalysis()
        );
    }

    private static ContractCandidate lowScoreCandidate() {
        ContractCandidate base = autoTradeCandidate();
        return new ContractCandidate(
                base.marketType(), base.instId(), base.baseCurrency(), base.quoteCurrency(), base.lastPrice(),
                base.changePercent24h(), base.changePercent5m(), base.volume24h(), base.volumeSpikeRatio(),
                base.fundingRate(), base.openInterest(), base.openInterestChange(), base.trendDirection(),
                base.volatility(), 50,
                new ContractFactorScore(50, 50, 50, 50, 50, 50, 50), base.entryPrice(), base.stopLossPrice(),
                base.takeProfitPrice(), base.suggestedLeverage(), base.riskRewardRatio(), base.spreadBps(),
                base.bidDepthUsdt(), base.askDepthUsdt(), base.estimatedSlippageBps(), base.btcTrend(), base.ethTrend(),
                base.marketRiskLevel(), base.candidateReasonList(), base.riskTagList(), base.createdAt(),
                base.signalType(), base.action(), base.entryType(), base.todayChangePct(), base.atrPct20m(),
                base.stopLossPct(), BigDecimal.valueOf(50),
                new ContractScoreBreakdown(50, 50, 50, 50, 50, 50, 50), base.klineAnalysis(), base.newsAnalysis()
        );
    }

    private static ContractCandidate candidateWithNews(ContractCandidate base, ContractNewsRiskAnalysis newsRisk) {
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
                base.score(),
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
                newsRisk
        );
    }

    private static ContractCandidate candidateWithPrices(ContractCandidate base, String entry, String stop, String takeProfit) {
        return new ContractCandidate(
                base.marketType(), base.instId(), base.baseCurrency(), base.quoteCurrency(), base.lastPrice(),
                base.changePercent24h(), base.changePercent5m(), base.volume24h(), base.volumeSpikeRatio(),
                base.fundingRate(), base.openInterest(), base.openInterestChange(), base.trendDirection(),
                base.volatility(), base.score(), base.factorScore(), new BigDecimal(entry), new BigDecimal(stop),
                new BigDecimal(takeProfit), base.suggestedLeverage(), base.riskRewardRatio(), base.spreadBps(),
                base.bidDepthUsdt(), base.askDepthUsdt(), base.estimatedSlippageBps(), base.btcTrend(),
                base.ethTrend(), base.marketRiskLevel(), base.candidateReasonList(), base.riskTagList(),
                base.createdAt(), base.signalType(), base.action(), base.entryType(), base.todayChangePct(),
                base.atrPct20m(), base.stopLossPct(), base.finalRankScore(), base.scoreBreakdown(),
                base.klineAnalysis(), base.newsAnalysis()
        );
    }

    private static ContractCandidate candidateWithCreatedAt(ContractCandidate base, Instant createdAt) {
        return new ContractCandidate(
                base.marketType(), base.instId(), base.baseCurrency(), base.quoteCurrency(), base.lastPrice(),
                base.changePercent24h(), base.changePercent5m(), base.volume24h(), base.volumeSpikeRatio(),
                base.fundingRate(), base.openInterest(), base.openInterestChange(), base.trendDirection(),
                base.volatility(), base.score(), base.factorScore(), base.entryPrice(), base.stopLossPrice(),
                base.takeProfitPrice(), base.suggestedLeverage(), base.riskRewardRatio(), base.spreadBps(),
                base.bidDepthUsdt(), base.askDepthUsdt(), base.estimatedSlippageBps(), base.btcTrend(),
                base.ethTrend(), base.marketRiskLevel(), base.candidateReasonList(), base.riskTagList(),
                createdAt, base.signalType(), base.action(), base.entryType(), base.todayChangePct(),
                base.atrPct20m(), base.stopLossPct(), base.finalRankScore(), base.scoreBreakdown(),
                base.klineAnalysis(), base.newsAnalysis()
        );
    }

    private static class LimitOrderAiAnalysisService extends AiAnalysisService {
        private final ObjectMapper objectMapper = new ObjectMapper();

        LimitOrderAiAnalysisService() {
            super(new AiProperties(false, null, null, null, null, 0, 0, null, null, null), new ObjectMapper());
        }

        @Override
        public JsonNode completeJson(String systemPrompt, String userPrompt) {
            try {
                return objectMapper.readTree("""
                        {
                          "action": "OPEN_LONG",
                          "orderType": "LIMIT",
                          "entryPrice": 100,
                          "stopLossPrice": 98,
                          "takeProfitPrice": 105,
                          "leverage": 3,
                          "confidence": 0.85,
                          "reasonList": ["AI建议限价入场"],
                          "riskList": ["限价可能部分成交"],
                          "invalidCondition": "20m结构失效"
                        }
                        """);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static class ReverseOrderAiAnalysisService extends AiAnalysisService {
        private final ObjectMapper objectMapper = new ObjectMapper();

        ReverseOrderAiAnalysisService() {
            super(new AiProperties(false, null, null, null, null, 0, 0, null, null, null), new ObjectMapper());
        }

        @Override
        public JsonNode completeJson(String systemPrompt, String userPrompt) {
            try {
                return objectMapper.readTree("""
                        {
                          "action": "OPEN_SHORT",
                          "orderType": "LIMIT",
                          "entryPrice": 150,
                          "stopLossPrice": 160,
                          "takeProfitPrice": 10,
                          "leverage": 100,
                          "confidence": 0.99,
                          "reasonList": ["AI反向建议"],
                          "riskList": ["测试反向覆盖"]
                        }
                        """);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static class FailingIfCalledAiAnalysisService extends AiAnalysisService {
        FailingIfCalledAiAnalysisService() {
            super(new AiProperties(false, null, null, null, null, 0, 0, null, null, null), new ObjectMapper());
        }

        @Override
        public JsonNode completeJson(String systemPrompt, String userPrompt) {
            throw new AssertionError("rule fallback must not call AI");
        }
    }

    private static class FixedAccountSnapshotService extends AccountSnapshotService {
        FixedAccountSnapshotService() {
            super(null);
        }

        @Override
        public AccountSummary summary() {
            return new AccountSummary(BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), "OKX_REAL", "ok");
        }
    }

    private static class UnavailableAccountSnapshotService extends AccountSnapshotService {
        UnavailableAccountSnapshotService() {
            super(null);
        }

        @Override
        public AccountSummary summary() {
            return new AccountSummary(BigDecimal.ZERO, BigDecimal.ZERO, "OKX_ERROR", "unavailable");
        }
    }

    private static class NoopTradePlanRecordService extends TradePlanRecordService {
        NoopTradePlanRecordService() {
            super(null, null, null, new ObjectMapper());
        }

        @Override
        public void record(TradePlan plan, TradePlanStatus status, String denyReason, Object aiPayload) {
        }
    }

    private static class CapturingTradePlanRecordService extends NoopTradePlanRecordService {
        private java.util.Map<String, Object> lastPayload;

        @Override
        @SuppressWarnings("unchecked")
        public void record(TradePlan plan, TradePlanStatus status, String denyReason, Object aiPayload) {
            this.lastPayload = (java.util.Map<String, Object>) aiPayload;
        }
    }

    private static ContractAnalysisService candidateSnapshot(List<ContractCandidate> candidates) {
        ContractAnalysisService service = mock(ContractAnalysisService.class);
        org.mockito.Mockito.when(service.candidates()).thenReturn(candidates);
        return service;
    }
}

package com.example.quant.tradeplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.agent.plan.TradePlanRecordService;
import com.example.quant.ai.AiAnalysisService;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.AiProperties;
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
import org.junit.jupiter.api.Test;

class ContractTradePlanBuilderTest {

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
    void noRiskPlanDoesNotRequireAutoTradeAllowedAction() {
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

        TradePlan plan = builder.buildNoRiskPlan(nonAutoTradeCandidate());

        assertThat(plan.action()).isEqualTo(TradePlanType.OPEN_LONG);
        assertThat(plan.orderType()).isEqualTo("LIMIT");
        assertThat(plan.reasonList()).anyMatch(reason -> reason.contains("无风控模式"));
    }

    @Test
    void unknownNewsRiskDoesNotForceWatchPlan() {
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

        TradePlan plan = builder.buildPlan(candidateWithNews(autoTradeCandidate(),
                ContractNewsRiskAnalysis.unknown("新闻源无返回", 20)));

        assertThat(plan.action()).isEqualTo(TradePlanType.OPEN_LONG);
        assertThat(plan.orderType()).isEqualTo("MARKET");
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

    private static class LimitOrderAiAnalysisService extends AiAnalysisService {
        private final ObjectMapper objectMapper = new ObjectMapper();

        LimitOrderAiAnalysisService() {
            super(new AiProperties(false, null, null, null, null, 0, 0), new ObjectMapper());
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

    private static class FixedAccountSnapshotService extends AccountSnapshotService {
        FixedAccountSnapshotService() {
            super(null);
        }

        @Override
        public AccountSummary summary() {
            return new AccountSummary(BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), "OKX_REAL", "ok");
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
}

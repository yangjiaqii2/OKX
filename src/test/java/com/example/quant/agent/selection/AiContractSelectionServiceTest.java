package com.example.quant.agent.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.ai.AiAnalysisService;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.dto.AthBreakoutSnapshot;
import com.example.quant.crypto.provider.dto.FundingHistorySnapshot;
import com.example.quant.crypto.provider.dto.FundingTrend;
import com.example.quant.crypto.provider.dto.OpenInterestHistorySnapshot;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import com.example.quant.crypto.selection.V2ScoreDimension;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;

class AiContractSelectionServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiAnalysisService ai;
    private AiContractSelectionService service;

    @BeforeEach
    void setUp() {
        ai = Mockito.mock(AiAnalysisService.class);
        service = new AiContractSelectionService(ai, objectMapper, new AgentProperties());
    }

    @Test
    void selectsAiCandidateOutsideRuleFirstPlaceWhenItBelongsToPool() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"TRADE","selectedInstId":"ETH-USDT-SWAP","direction":"SHORT",
                 "entryMode":"MARKET","confidence":0.82,
                 "rankedCandidates":[
                   {"instId":"ETH-USDT-SWAP","rank":1,"direction":"SHORT","entryMode":"MARKET","confidence":0.82},
                   {"instId":"BTC-USDT-SWAP","rank":2,"direction":"LONG","entryMode":"WAIT_PULLBACK","confidence":0.70}],
                 "reasonList":["ETH结构更清晰"],"riskList":["新闻不可用"]}
                """));

        ContractSelectionDecision result = service.select(List.of(
                candidate("BTC-USDT-SWAP", 90), candidate("ETH-USDT-SWAP", 80, DirectionBias.BEARISH)));

        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.AI_PRIMARY);
        assertThat(result.selectedInstId()).isEqualTo("ETH-USDT-SWAP");
        assertThat(result.direction()).isEqualTo(DirectionBias.BEARISH);
        assertThat(result.rankedCandidates()).extracting(ContractSelectionDecision.RankedCandidate::instId)
                .containsExactly("ETH-USDT-SWAP", "BTC-USDT-SWAP");
    }

    @Test
    void usesTypeSafeTypedAnswersForExecutableSelection() throws Exception {
        when(ai.isTypeSafeProvider()).thenReturn(true);
        when(ai.completeTyped(any(), any())).thenReturn(objectMapper.readTree("""
                {"model":"jev-1.13.0","answers":{
                  "decision":{"type":"choice","choice":"TRADE","confidence":0.92},
                  "selectedInstId":{"type":"choice","choice":"BTC-USDT-SWAP","confidence":0.88},
                  "direction":{"type":"choice","choice":"LONG","confidence":0.90},
                  "entryMode":{"type":"choice","choice":"MARKET","confidence":0.86},
                  "quality":{"type":"score","score":3.0,"confidence":0.82},
                  "risk_ok":{"type":"noul","noul":0.91}}}
                """));

        ContractSelectionDecision result = service.select(List.of(candidate("BTC-USDT-SWAP", 90)));

        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.AI_PRIMARY);
        assertThat(result.selectedInstId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(result.direction()).isEqualTo(DirectionBias.BULLISH);
        assertThat(result.confidence()).isEqualByComparingTo("0.82");
        verify(ai, never()).completeJson(anyString(), anyString());
    }

    @Test
    void TypeSafeLowRiskProbabilityFailsClosedToWait() throws Exception {
        when(ai.isTypeSafeProvider()).thenReturn(true);
        when(ai.completeTyped(any(), any())).thenReturn(objectMapper.readTree("""
                {"answers":{
                  "decision":{"type":"choice","choice":"TRADE","confidence":0.92},
                  "selectedInstId":{"type":"choice","choice":"BTC-USDT-SWAP","confidence":0.88},
                  "direction":{"type":"choice","choice":"LONG","confidence":0.90},
                  "entryMode":{"type":"choice","choice":"MARKET","confidence":0.86},
                  "quality":{"type":"score","score":3.0,"confidence":0.82},
                  "risk_ok":{"type":"noul","noul":0.40}}}
                """));

        ContractSelectionDecision result = service.select(List.of(candidate("BTC-USDT-SWAP", 90)));

        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.AI_WAIT);
        assertThat(result.decision()).isEqualTo(ContractSelectionDecision.SelectionAction.WAIT);
        assertThat(result.entryMode()).isEqualTo("WAIT_RETEST");
    }

    @Test
    void keepsReasonAndRiskOnTheirRankedCandidate() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"TRADE","selectedInstId":"HYPE-USDT-SWAP","direction":"LONG","entryMode":"MARKET","confidence":0.90,
                 "rankedCandidates":[
                   {"instId":"HYPE-USDT-SWAP","rank":1,"direction":"LONG","entryMode":"MARKET","confidence":0.90,"reasonList":["HYPE上涨"],"riskList":["HYPE拥挤"]},
                   {"instId":"BTC-USDT-SWAP","rank":2,"direction":"SHORT","entryMode":"MARKET","confidence":0.82,"reasonList":["BTC下行"],"riskList":["BTC超卖"]}],
                 "reasonList":["全局理由"],"riskList":["全局风险"]}
                """));
        ContractSelectionDecision result = service.select(List.of(
                candidate("BTC-USDT-SWAP", 90, DirectionBias.BEARISH), candidate("HYPE-USDT-SWAP", 80)));
        var btc = result.rankedCandidates().stream().filter(c -> c.instId().startsWith("BTC")).findFirst().orElseThrow();
        assertThat(btc.reasonList()).containsExactly("BTC下行");
        assertThat(btc.riskList()).containsExactly("BTC超卖");
    }

    @Test
    void validWaitDoesNotBecomeRuleFallback() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"WAIT","selectedInstId":"","direction":"LONG","entryMode":"WAIT_RETEST",
                 "confidence":0.55,"rankedCandidates":[],"reasonList":["结构不清晰"],"riskList":[]}
                """));

        ContractSelectionDecision result = service.select(List.of(candidate("BTC-USDT-SWAP", 90)));

        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.AI_WAIT);
        assertThat(result.decision()).isEqualTo(ContractSelectionDecision.SelectionAction.WAIT);
    }

    @Test
    void tradeWithWaitModeBecomesSelectedWaitInsteadOfExecutableTrade() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"TRADE","selectedInstId":"BTC-USDT-SWAP","direction":"LONG",
                 "entryMode":"WAIT_PULLBACK","confidence":0.76,
                 "rankedCandidates":[{"instId":"BTC-USDT-SWAP","rank":1,"direction":"LONG",
                   "entryMode":"WAIT_PULLBACK","confidence":0.76}],
                 "reasonList":["等待回踩"],"riskList":[]}
                """));

        ContractSelectionDecision result = service.select(List.of(candidate("BTC-USDT-SWAP", 90)));

        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.AI_WAIT);
        assertThat(result.decision()).isEqualTo(ContractSelectionDecision.SelectionAction.WAIT);
        assertThat(result.selectedInstId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(result.direction()).isEqualTo(DirectionBias.BULLISH);
        assertThat(result.entryMode()).isEqualTo("WAIT_PULLBACK");
        assertThat(result.rankedCandidates()).hasSize(1);
    }

    @Test
    void canonicalSelectedWaitKeepsSymbolAndDirection() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"WAIT","selectedInstId":"BTC-USDT-SWAP","direction":"SHORT",
                 "entryMode":"WAIT_RETEST","confidence":0.68,
                 "rankedCandidates":[{"instId":"BTC-USDT-SWAP","rank":1,"direction":"SHORT",
                   "entryMode":"WAIT_RETEST","confidence":0.68}],
                 "reasonList":["等待回测"],"riskList":[]}
                """));

        ContractSelectionDecision result = service.select(List.of(
                candidate("BTC-USDT-SWAP", 90, DirectionBias.BEARISH)));

        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.AI_WAIT);
        assertThat(result.selectedInstId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(result.direction()).isEqualTo(DirectionBias.BEARISH);
        assertThat(result.entryMode()).isEqualTo("WAIT_RETEST");
    }

    @Test
    void poolOutsideSymbolFallsBackToRules() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"TRADE","selectedInstId":"DOGE-USDT-SWAP","direction":"LONG",
                 "entryMode":"MARKET","confidence":0.90,
                 "rankedCandidates":[{"instId":"DOGE-USDT-SWAP","rank":1,"direction":"LONG","entryMode":"MARKET","confidence":0.90}],
                 "reasonList":[],"riskList":[]}
                """));

        ContractSelectionDecision result = service.select(List.of(candidate("BTC-USDT-SWAP", 90)));

        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.RULE_FALLBACK);
        assertThat(result.fallbackReason()).contains("selectedInstId");
    }

    @Test
    void topLevelAndFirstRankedDirectionConflictFallsBack() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"TRADE","selectedInstId":"BTC-USDT-SWAP","direction":"LONG",
                 "entryMode":"MARKET","confidence":0.90,
                 "rankedCandidates":[{"instId":"BTC-USDT-SWAP","rank":1,"direction":"SHORT",
                   "entryMode":"MARKET","confidence":0.90}],
                 "reasonList":[],"riskList":[]}
                """));

        ContractSelectionDecision result = service.select(List.of(candidate("BTC-USDT-SWAP", 90)));

        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.RULE_FALLBACK);
        assertThat(result.fallbackReason()).contains("direction");
    }

    @Test
    void aiDirectionOppositeToDeterministicCandidateFallsBackForBothSides() throws Exception {
        when(ai.completeJson(anyString(), anyString()))
                .thenReturn(objectMapper.readTree("""
                        {"decision":"TRADE","selectedInstId":"BTC-USDT-SWAP","direction":"SHORT",
                         "entryMode":"MARKET","confidence":0.90,
                         "rankedCandidates":[{"instId":"BTC-USDT-SWAP","rank":1,"direction":"SHORT",
                           "entryMode":"MARKET","confidence":0.90}],"reasonList":[],"riskList":[]}
                        """))
                .thenReturn(objectMapper.readTree("""
                        {"decision":"TRADE","selectedInstId":"ETH-USDT-SWAP","direction":"LONG",
                         "entryMode":"MARKET","confidence":0.90,
                         "rankedCandidates":[{"instId":"ETH-USDT-SWAP","rank":1,"direction":"LONG",
                           "entryMode":"MARKET","confidence":0.90}],"reasonList":[],"riskList":[]}
                        """));

        ContractSelectionDecision bullishConflict = service.select(List.of(
                candidate("BTC-USDT-SWAP", 90, DirectionBias.BULLISH)));
        ContractSelectionDecision bearishConflict = service.select(List.of(
                candidate("ETH-USDT-SWAP", 90, DirectionBias.BEARISH)));

        assertThat(bullishConflict.source()).isEqualTo(ContractSelectionDecision.DecisionSource.RULE_FALLBACK);
        assertThat(bearishConflict.source()).isEqualTo(ContractSelectionDecision.DecisionSource.RULE_FALLBACK);
        assertThat(bullishConflict.fallbackReason()).contains("deterministic candidate direction");
        assertThat(bearishConflict.fallbackReason()).contains("deterministic candidate direction");
    }

    @Test
    void payloadCarriesScannerV2ScoresAndHistoricalEvidenceWithoutLegacyOiDefault() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"WAIT","confidence":0.55,"entryMode":"WAIT_RETEST",
                 "reasonList":["等待"],"riskList":[]}
                """));
        Instant now = Instant.parse("2026-09-22T08:00:00Z");
        ContractCandidate base = candidate("BTC-USDT-SWAP", 90);
        ContractCandidate enriched = new ContractCandidate(
                base.marketType(), base.instId(), base.baseCurrency(), base.quoteCurrency(), base.lastPrice(),
                base.changePercent24h(), base.changePercent5m(), base.volume24h(), base.volumeSpikeRatio(),
                base.fundingRate(), base.openInterest(), null, base.trendDirection(), base.volatility(),
                base.score(), base.factorScore(), base.entryPrice(), base.stopLossPrice(), base.takeProfitPrice(),
                base.suggestedLeverage(), base.riskRewardRatio(), base.spreadBps(), base.bidDepthUsdt(),
                base.askDepthUsdt(), base.estimatedSlippageBps(), base.btcTrend(), base.ethTrend(),
                base.marketRiskLevel(), base.candidateReasonList(), base.riskTagList(), base.createdAt(),
                base.signalType(), base.action(), base.entryType(), base.todayChangePct(), base.atrPct20m(),
                base.stopLossPct(), base.finalRankScore(), base.scoreBreakdown(), base.klineAnalysis(),
                base.newsAnalysis(), base.marketCapUsd(), base.fdvUsd(), base.circulationRatio(),
                base.volumeMcapRatio(), base.marketQualityNote(),
                new OpenInterestHistorySnapshot(base.instId(), new BigDecimal("1234"),
                        new BigDecimal("1.1"), new BigDecimal("4.4"), new BigDecimal("9.9"), now, now, "okx"),
                new FundingHistorySnapshot(base.instId(), new BigDecimal("0.0002"),
                        new BigDecimal("0.0001"), 0.00002d, FundingTrend.HEATING, false,
                        List.of(new BigDecimal("0.0001"), new BigDecimal("0.00015"), new BigDecimal("0.0002")),
                        FundingHistorySnapshot.DataQuality.FRESH, now, now, "okx"),
                new RelativeStrengthSnapshot(base.instId(), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, new BigDecimal("2.1"), new BigDecimal("5.4"), new BigDecimal("8.8"),
                        DataQuality.FRESH, now),
                new AthBreakoutSnapshot(base.instId(), new BigDecimal("120"), base.lastPrice(),
                        new BigDecimal("16.67"), "MID_RANGE", DataQuality.FRESH, 2, false),
                new BigDecimal("123400"));
        Map<V2ScoreDimension, BigDecimal> dimensions = new java.util.EnumMap<>(V2ScoreDimension.class);
        dimensions.put(V2ScoreDimension.CAPITAL_FLOW, new BigDecimal("80"));
        dimensions.put(V2ScoreDimension.DERIVATIVES, new BigDecimal("91"));
        dimensions.put(V2ScoreDimension.RELATIVE_STRENGTH, new BigDecimal("87"));
        dimensions.put(V2ScoreDimension.MARKET_STRUCTURE, new BigDecimal("90"));
        dimensions.put(V2ScoreDimension.VOLUME_QUALITY, new BigDecimal("82"));
        dimensions.put(V2ScoreDimension.BREAKOUT, new BigDecimal("55"));
        V2SelectionScoreResult v2 = new V2SelectionScoreResult(
                new BigDecimal("84.5"), new BigDecimal("82.1"), new BigDecimal("95"),
                new BigDecimal("0.95"), Map.copyOf(dimensions), List.of(V2ScoreDimension.NEWS));

        service.select(List.of(enriched), Map.of(enriched.instId(), v2));

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(ai).completeJson(systemPrompt.capture(), userPrompt.capture());
        assertThat(systemPrompt.getValue())
                .contains("Java 已经筛选出的 eligible candidates")
                .contains("数据缺失不能被解释为中性证据")
                .contains("不得反转 deterministic candidate direction");
        assertThat(userPrompt.getValue())
                .contains("\"v2RankingScore\":82.1", "\"v2NormalizedScore\":84.5")
                .contains("\"v2CoverageRatio\":0.95", "\"v2AvailableWeight\":95")
                .contains("\"capitalFlowScore\":80", "\"derivativesScore\":91")
                .contains("\"relativeStrengthScore\":87", "\"marketStructureScore\":90")
                .contains("\"volumeQualityScore\":82", "\"breakoutScore\":55")
                .contains("\"newsScore\":null")
                .contains("\"legacyOpenInterestChange\":null")
                .contains("\"oiChange1h\":1.1", "\"oiChange4h\":4.4", "\"oiChange24h\":9.9")
                .contains("\"oiDirection\":\"LEADING\"")
                .contains("\"rs1h\":2.1", "\"rs4h\":5.4", "\"rs24h\":8.8")
                .contains("\"relativeStrengthDataQuality\":\"FRESH\"")
                .contains("\"fundingTrend\":\"HEATING\"", "\"fundingExtreme\":false")
                .contains("\"fundingDataQuality\":\"FRESH\"")
                .contains("\"athPrice\":120", "\"distanceFromAth\":16.67")
                .contains("\"breakoutLevel\":\"MID_RANGE\"", "\"breakoutApplicable\":true");
    }

    @Test
    void shortPayloadDistinguishesBreakoutNotApplicableFromMissingEvidence() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"WAIT","confidence":0.55,"entryMode":"WAIT_RETEST",
                 "reasonList":["等待"],"riskList":[]}
                """));
        ContractCandidate bearish = candidate("BTC-USDT-SWAP", 90, DirectionBias.BEARISH);
        V2SelectionScoreResult v2 = new V2SelectionScoreResult(
                new BigDecimal("78"), new BigDecimal("76"), new BigDecimal("90"),
                new BigDecimal("0.90"), Map.of(), List.of(V2ScoreDimension.BREAKOUT));

        service.select(List.of(bearish), Map.of(bearish.instId(), v2));

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(ai).completeJson(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue())
                .contains("\"breakoutApplicable\":false")
                .contains("\"breakoutLevel\":null")
                .contains("\"breakoutDataQuality\":\"UNAVAILABLE\"")
                .contains("\"rs1h\":null", "\"rs4h\":null", "\"rs24h\":null");
    }

    @Test
    void candidateLimitIsAppliedAfterScannerV2OrderingInsteadOfLegacyScoreOrdering() throws Exception {
        AgentProperties properties = new AgentProperties();
        AgentProperties.AiSelection aiSelection = new AgentProperties.AiSelection();
        aiSelection.setCandidateLimit(1);
        properties.setAiSelection(aiSelection);
        service = new AiContractSelectionService(ai, objectMapper, properties);
        ContractCandidate highV2LowV1 = candidate("ETH-USDT-SWAP", 70);
        ContractCandidate lowV2HighV1 = candidate("BTC-USDT-SWAP", 95);
        Map<String, V2SelectionScoreResult> scores = Map.of(
                highV2LowV1.instId(), new V2SelectionScoreResult(
                        new BigDecimal("94"), new BigDecimal("92"), new BigDecimal("100"), BigDecimal.ONE,
                        Map.of(), List.of()),
                lowV2HighV1.instId(), new V2SelectionScoreResult(
                        new BigDecimal("72"), new BigDecimal("70"), new BigDecimal("100"), BigDecimal.ONE,
                        Map.of(), List.of()));
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"WAIT","confidence":0.55,"entryMode":"WAIT_RETEST",
                 "reasonList":["等待"],"riskList":[]}
                """));

        service.select(List.of(lowV2HighV1, highV2LowV1), scores);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).completeJson(anyString(), prompt.capture());
        assertThat(prompt.getValue())
                .contains("ETH-USDT-SWAP", "\"v2RankingScore\":92")
                .doesNotContain("BTC-USDT-SWAP");
    }

    @Test
    void emptyPoolDoesNotCallAi() {
        ContractSelectionDecision result = service.select(List.of());

        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
        verify(ai, never()).completeJson(anyString(), anyString());
    }

    @Test
    void labelsRuleSignalsAsAdvisoryInsteadOfHardRisk() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"WAIT","confidence":0.55,"entryMode":"WAIT_RETEST",
                 "reasonList":["等待确认"],"riskList":[]}
                """));

        service.select(List.of(candidate("BTC-USDT-SWAP", 75)));

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(ai).completeJson(systemPrompt.capture(), userPrompt.capture());
        assertThat(systemPrompt.getValue()).contains("软规则不是硬否决");
        assertThat(userPrompt.getValue())
                .contains("softRuleSignal", "softRuleScore", "softRuleDiagnostics")
                .doesNotContain("\"riskTags\"");
    }

    @Test
    void promptPrefersExecutableEntryWithoutRequiringPerfectSoftRuleAlignment() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(objectMapper.readTree("""
                {"decision":"WAIT","confidence":0.60,"entryMode":"WAIT_PULLBACK",
                 "reasonList":["等待回踩"],"riskList":[]}
                """));

        service.select(List.of(candidate("BTC-USDT-SWAP", 75)));

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(ai).completeJson(systemPrompt.capture(), anyString());
        assertThat(systemPrompt.getValue())
                .contains("不要求所有周期完全一致")
                .contains("趋势方向明确", "流动性", "未明显过热")
                .contains("优先选择 MARKET 或 LIMIT")
                .contains("WAIT_PULLBACK / WAIT_RETEST 只用于")
                .contains("不得为了增加交易次数忽略");
    }

    private static ContractCandidate candidate(String instId, int score) {
        return candidate(instId, score, DirectionBias.BULLISH);
    }

    private static ContractCandidate candidate(String instId, int score, DirectionBias direction) {
        return new ContractCandidate(
                MarketType.OKX_SWAP, instId, instId.substring(0, instId.indexOf('-')), "USDT",
                new BigDecimal("100"), new BigDecimal("4"), new BigDecimal("0.5"),
                new BigDecimal("500000000"), new BigDecimal("1.5"), new BigDecimal("0.0001"),
                new BigDecimal("1000000"), new BigDecimal("2"), direction,
                new BigDecimal("0.02"), score, new ContractFactorScore(20, 20, 8, 15, 8, 8, 7),
                new BigDecimal("100"), new BigDecimal("98"), new BigDecimal("104"), 3,
                new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("500000"),
                new BigDecimal("500000"), BigDecimal.ONE, direction, direction,
                RiskLevel.LOW, List.of("结构完整"), List.of(), Instant.now()
        );
    }
}

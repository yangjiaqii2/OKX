package com.example.quant.agent.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.ai.AiAnalysisService;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.filter.CirculationFilter;
import com.example.quant.crypto.filter.LiquidityFilter;
import com.example.quant.crypto.filter.MarketCapFilter;
import com.example.quant.crypto.filter.PriceRangeFilter;
import com.example.quant.crypto.filter.UniverseHardFilter;
import com.example.quant.crypto.filter.VolumeMcapFilter;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.crypto.selection.V2ScoreDimension;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.risk.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Phase B V2 AI eligible pool 集成测试。
 *
 * <p>核心约束（用户强约束 8 + 9）：<ul>
 *   <li>AI eligible pool <b>必须</b> 走 {@link UniverseHardFilter} +
 *       {@link com.example.quant.agent.gate.ContractTradeGate#aiHardDenyReasons}</li>
 *   <li>不允许 AI 绕过任何 filter 收进 candidate</li>
 *   <li>测试不能仅靠上游 Scanner 干净来验证，必须直接构造 dirty candidate 喂进 {@link AiContractSelectionService#select}</li>
 * </ul>
 */
class AiContractSelectionServiceV2PoolTest {

    private static final BigDecimal MIN_MCAP = new BigDecimal("20000000");
    private static final BigDecimal MIN_CIRCULATION = new BigDecimal("0.50");
    private static final BigDecimal MIN_VOL_MCAP = new BigDecimal("0.20");
    private static final BigDecimal MAX_RANGE = new BigDecimal("40");
    private static final BigDecimal MAX_SPREAD = new BigDecimal("30");
    private static final BigDecimal MIN_DEPTH = new BigDecimal("50000");

    private final ObjectMapper om = new ObjectMapper();
    private AgentProperties props;
    private UniverseHardFilter hardFilter;
    private AiAnalysisService ai;
    private AiContractSelectionService service;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        hardFilter = new UniverseHardFilter(List.of(
                new MarketCapFilter(MIN_MCAP),
                new CirculationFilter(MIN_CIRCULATION),
                new VolumeMcapFilter(MIN_VOL_MCAP),
                new PriceRangeFilter(MAX_RANGE),
                new LiquidityFilter(MAX_SPREAD, MIN_DEPTH)
        ));
        ai = mock(AiAnalysisService.class);
        service = new AiContractSelectionService(ai, om, props, hardFilter);
    }

    /**
     * 基础 V2 合格 candidate：所有 V1/V2 硬字段都在 PASS 范围。
     */
    private ContractCandidate v2Eligible(String instId) {
        return new ContractCandidate(
                MarketType.OKX_SWAP,
                instId,
                "BTC",
                "USDT",
                new BigDecimal("50000"),
                new BigDecimal("0.05"),
                new BigDecimal("0.01"),
                new BigDecimal("5000000"),
                new BigDecimal("1.5"),
                new BigDecimal("0.0001"),
                new BigDecimal("10000"),
                new BigDecimal("0.02"),
                DirectionBias.BULLISH,
                new BigDecimal("0.02"),
                80,
                new ContractFactorScore(50, 50, 50, 50, 50, 50, 50),
                new BigDecimal("49500"),
                new BigDecimal("49000"),
                new BigDecimal("51000"),
                5,
                new BigDecimal("3"),
                new BigDecimal("5"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("2"),
                DirectionBias.BULLISH,
                DirectionBias.BULLISH,
                RiskLevel.LOW,
                List.of("test"),
                List.of(),
                Instant.now()
        ).withV2MarketQuality(
                new BigDecimal("20000000"),
                new BigDecimal("40000000"),
                new BigDecimal("0.50"),
                new BigDecimal("0.20"),
                "OK"
        );
    }

    private JsonNode aiTradeJson(String selectedInstId) throws Exception {
        return om.readTree("""
                {"decision":"TRADE","selectedInstId":"%s","direction":"LONG","entryMode":"MARKET",
                 "confidence":0.7,"rankedCandidates":[{"instId":"%s","rank":1,"direction":"LONG",
                 "entryMode":"MARKET","confidence":0.7}],"reasonList":[],"riskList":[]}
                """.formatted(selectedInstId, selectedInstId));
    }

    private static long countOccurrences(String text, String pattern) {
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    // ==============================================================
    // 重复 instId 去重
    // ==============================================================

    @Test
    @DisplayName("重复 instId 在 AI pool 内部去重，不重复调 AI")
    void duplicateInstIdsAreDedupedInPool() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(aiTradeJson("BTC-USDT-SWAP"));
        ContractCandidate btc = v2Eligible("BTC-USDT-SWAP");
        service.select(List.of(btc, btc, btc));
        // 3 个相同 instId → pool 应只 1 个 → AI 收到 1 个 candidate
        verify(ai, times(1)).completeJson(anyString(), anyString());
    }

    @Test
    @DisplayName("混合重复 + 独立：每个 instId 在 pool 内只出现一次")
    void mixOfDuplicateAndDistinctInstIds() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(aiTradeJson("BTC-USDT-SWAP"));
        ContractCandidate btc = v2Eligible("BTC-USDT-SWAP");
        ContractCandidate eth = v2Eligible("ETH-USDT-SWAP");
        service.select(List.of(btc, btc, eth, btc, eth));
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(ai, times(1)).completeJson(anyString(), userPrompt.capture());
        String prompt = userPrompt.getValue();
        // pool size == 2 (BTC, ETH) → 每个 instId 在 userPrompt 中只出现 1 次
        assertThat(countOccurrences(prompt, "BTC-USDT-SWAP")).isEqualTo(1);
        assertThat(countOccurrences(prompt, "ETH-USDT-SWAP")).isEqualTo(1);
    }

    // ==============================================================
    // candidateLimit 截断
    // ==============================================================

    @Test
    @DisplayName("candidateLimit=3 时 pool 被截断为前 3 个")
    void candidateLimitTruncatesPool() throws Exception {
        when(ai.completeJson(anyString(), anyString())).thenReturn(aiTradeJson("TOKEN01-USDT-SWAP"));
        AgentProperties.AiSelection cfg = new AgentProperties.AiSelection();
        cfg.setCandidateLimit(3);
        props.setAiSelection(cfg);
        service = new AiContractSelectionService(ai, om, props, hardFilter);

        // 13 个不同 instId：TOKEN01...TOKEN13
        List<ContractCandidate> many = new ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            many.add(v2Eligible(String.format("TOKEN%02d-USDT-SWAP", i)));
        }
        service.select(many);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(ai, times(1)).completeJson(anyString(), userPrompt.capture());
        String prompt = userPrompt.getValue();
        // pool 应为前 3 个：TOKEN01 / TOKEN02 / TOKEN03
        assertThat(prompt).contains("TOKEN01-USDT-SWAP", "TOKEN02-USDT-SWAP", "TOKEN03-USDT-SWAP");
        assertThat(prompt).doesNotContain("TOKEN04-USDT-SWAP");
        assertThat(prompt).doesNotContain("TOKEN10-USDT-SWAP");
        assertThat(prompt).doesNotContain("TOKEN13-USDT-SWAP");
    }

    // ==============================================================
    // UniverseHardFilter 单 filter 拒绝 → AI 不调
    // ==============================================================

    @Test
    @DisplayName("V2 marketCapUsd < 20M → UniverseHardFilter 拒 → AI 不调")
    void marketCapBelowThresholdTriggersUniverseHardFilterReject() throws Exception {
        ContractCandidate tooSmall = v2Eligible("BTC-USDT-SWAP")
                .withV2MarketQuality(
                        new BigDecimal("19999999"),
                        new BigDecimal("40000000"),
                        new BigDecimal("0.50"),
                        new BigDecimal("0.20"),
                        "MARKET_CAP_TOO_LOW"
                );
        var decision = service.select(List.of(tooSmall));
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(decision.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }

    @Test
    @DisplayName("V2 circulationRatio < 0.5 → UniverseHardFilter 拒 → AI 不调")
    void circulationBelowThresholdTriggersUniverseHardFilterReject() throws Exception {
        ContractCandidate illiquid = v2Eligible("ETH-USDT-SWAP")
                .withV2MarketQuality(
                        new BigDecimal("50000000"),
                        new BigDecimal("100000000"),
                        new BigDecimal("0.49"),
                        new BigDecimal("0.30"),
                        "CIRCULATION_TOO_LOW"
                );
        var decision = service.select(List.of(illiquid));
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(decision.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }

    @Test
    @DisplayName("V2 volumeMcapRatio < 0.2 → UniverseHardFilter 拒 → AI 不调")
    void volumeMcapBelowThresholdTriggersUniverseHardFilterReject() throws Exception {
        // volume24h 5_000_000，marketCapUsd 100_000_000 → ratio = 0.05 < 0.2
        ContractCandidate lowVol = new ContractCandidate(
                MarketType.OKX_SWAP,
                "ETH-USDT-SWAP",
                "ETH", "USDT",
                new BigDecimal("3000"),
                new BigDecimal("0.05"),
                new BigDecimal("0.01"),
                new BigDecimal("5000000"),
                new BigDecimal("1.5"),
                new BigDecimal("0.0001"),
                new BigDecimal("10000"),
                new BigDecimal("0.02"),
                DirectionBias.BULLISH,
                new BigDecimal("0.02"),
                80,
                new ContractFactorScore(50, 50, 50, 50, 50, 50, 50),
                new BigDecimal("2950"),
                new BigDecimal("2900"),
                new BigDecimal("3100"),
                5, new BigDecimal("3"),
                new BigDecimal("5"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("2"),
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW,
                List.of("test"), List.of(), Instant.now()
        ).withV2MarketQuality(
                new BigDecimal("100000000"),
                new BigDecimal("200000000"),
                null,
                new BigDecimal("0.05"),
                "VOLUME_MCAP_TOO_LOW"
        );
        var decision = service.select(List.of(lowVol));
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(decision.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }

    // ==============================================================
    // aiHardDenyReasons 多种 reason 路径
    // ==============================================================

    @Test
    @DisplayName("fundingRate = null → funding_unavailable → AI 不调")
    void fundingRateNullTriggersAiHardDeny() throws Exception {
        ContractCandidate noFunding = new ContractCandidate(
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                "BTC", "USDT",
                new BigDecimal("50000"),
                new BigDecimal("0.05"),
                new BigDecimal("0.01"),
                new BigDecimal("5000000"),
                new BigDecimal("1.5"),
                null,                                    // fundingRate = null
                new BigDecimal("10000"),
                new BigDecimal("0.02"),
                DirectionBias.BULLISH,
                new BigDecimal("0.02"),
                80,
                new ContractFactorScore(50, 50, 50, 50, 50, 50, 50),
                new BigDecimal("49500"),
                new BigDecimal("49000"),
                new BigDecimal("51000"),
                5, new BigDecimal("3"),
                new BigDecimal("5"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("2"),
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW,
                List.of("test"), List.of(), Instant.now()
        ).withV2MarketQuality(
                new BigDecimal("20000000"),
                new BigDecimal("40000000"),
                new BigDecimal("0.50"),
                new BigDecimal("0.20"),
                "OK"
        );
        V2SelectionScoreResult highV2 = new V2SelectionScoreResult(
                new BigDecimal("99"), new BigDecimal("98"), new BigDecimal("100"), BigDecimal.ONE,
                java.util.Map.of(V2ScoreDimension.CAPITAL_FLOW, new BigDecimal("99")), List.of());
        var decision = service.select(List.of(noFunding), java.util.Map.of(noFunding.instId(), highV2));
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(decision.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }

    @Test
    @DisplayName("spreadBps 极端大 → spread_bps_extreme → AI 不调")
    void spreadBpsExtremeTriggersAiHardDeny() throws Exception {
        // maxSpreadBps 默认值（看 AgentProperties.Market）— 这里用一个肯定极端的：500
        ContractCandidate wideSpread = new ContractCandidate(
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                "BTC", "USDT",
                new BigDecimal("50000"),
                new BigDecimal("0.05"),
                new BigDecimal("0.01"),
                new BigDecimal("5000000"),
                new BigDecimal("1.5"),
                new BigDecimal("0.0001"),
                new BigDecimal("10000"),
                new BigDecimal("0.02"),
                DirectionBias.BULLISH,
                new BigDecimal("0.02"),
                80,
                new ContractFactorScore(50, 50, 50, 50, 50, 50, 50),
                new BigDecimal("49500"),
                new BigDecimal("49000"),
                new BigDecimal("51000"),
                5, new BigDecimal("3"),
                new BigDecimal("500"),     // 极端 spread
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("2"),
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW,
                List.of("test"), List.of(), Instant.now()
        ).withV2MarketQuality(
                new BigDecimal("20000000"),
                new BigDecimal("40000000"),
                new BigDecimal("0.50"),
                new BigDecimal("0.20"),
                "OK"
        );
        var decision = service.select(List.of(wideSpread));
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(decision.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }

    @Test
    @DisplayName("marketRiskLevel = HIGH → market_risk_high → AI 不调")
    void marketRiskHighTriggersAiHardDeny() throws Exception {
        ContractCandidate risky = new ContractCandidate(
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                "BTC", "USDT",
                new BigDecimal("50000"),
                new BigDecimal("0.05"),
                new BigDecimal("0.01"),
                new BigDecimal("5000000"),
                new BigDecimal("1.5"),
                new BigDecimal("0.0001"),
                new BigDecimal("10000"),
                new BigDecimal("0.02"),
                DirectionBias.BULLISH,
                new BigDecimal("0.02"),
                80,
                new ContractFactorScore(50, 50, 50, 50, 50, 50, 50),
                new BigDecimal("49500"),
                new BigDecimal("49000"),
                new BigDecimal("51000"),
                5, new BigDecimal("3"),
                new BigDecimal("5"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("2"),
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.HIGH,
                List.of("test"), List.of(), Instant.now()
        ).withV2MarketQuality(
                new BigDecimal("20000000"),
                new BigDecimal("40000000"),
                new BigDecimal("0.50"),
                new BigDecimal("0.20"),
                "OK"
        );
        var decision = service.select(List.of(risky));
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(decision.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }

    @Test
    @DisplayName("lastPrice <= 0 → invalid_last_price → AI 不调")
    void invalidLastPriceTriggersAiHardDeny() throws Exception {
        ContractCandidate zero = new ContractCandidate(
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                "BTC", "USDT",
                BigDecimal.ZERO,        // lastPrice = 0
                new BigDecimal("0.05"),
                new BigDecimal("0.01"),
                new BigDecimal("5000000"),
                new BigDecimal("1.5"),
                new BigDecimal("0.0001"),
                new BigDecimal("10000"),
                new BigDecimal("0.02"),
                DirectionBias.BULLISH,
                new BigDecimal("0.02"),
                80,
                new ContractFactorScore(50, 50, 50, 50, 50, 50, 50),
                new BigDecimal("0"),
                new BigDecimal("0"),
                new BigDecimal("0"),
                5, new BigDecimal("3"),
                new BigDecimal("5"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("2"),
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW,
                List.of("test"), List.of(), Instant.now()
        ).withV2MarketQuality(
                new BigDecimal("20000000"),
                new BigDecimal("40000000"),
                new BigDecimal("0.50"),
                new BigDecimal("0.20"),
                "OK"
        );
        var decision = service.select(List.of(zero));
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(decision.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }

    @Test
    @DisplayName("非 USDT-SWAP instId → unsupported_instrument → AI 不调")
    void unsupportedInstrumentTriggersAiHardDeny() throws Exception {
        ContractCandidate nonSwap = v2Eligible("BTC-USD-SWAP");
        var decision = service.select(List.of(nonSwap));
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(decision.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }

    @Test
    @DisplayName("newsRiskLevel = HIGH → news_risk_high → AI 不调")
    void newsRiskHighTriggersAiHardDeny() throws Exception {
        ContractCandidate badNews = new ContractCandidate(
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                "BTC", "USDT",
                new BigDecimal("50000"),
                new BigDecimal("0.05"),
                new BigDecimal("0.01"),
                new BigDecimal("5000000"),
                new BigDecimal("1.5"),
                new BigDecimal("0.0001"),
                new BigDecimal("10000"),
                new BigDecimal("0.02"),
                DirectionBias.BULLISH,
                new BigDecimal("0.02"),
                80,
                new ContractFactorScore(50, 50, 50, 50, 50, 50, 50),
                new BigDecimal("49500"),
                new BigDecimal("49000"),
                new BigDecimal("51000"),
                5, new BigDecimal("3"),
                new BigDecimal("5"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("2"),
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW,
                List.of("test"), List.of(), Instant.now(),
                null, null, null, null, null, null, null, null, null,
                new ContractNewsRiskAnalysis(20, "HIGH", List.of(), List.of(), List.of(), null)
        ).withV2MarketQuality(
                new BigDecimal("20000000"),
                new BigDecimal("40000000"),
                new BigDecimal("0.50"),
                new BigDecimal("0.20"),
                "OK"
        );
        var decision = service.select(List.of(badNews));
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(decision.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }

    // ==============================================================
    // V2 字段全 null → UniverseHardFilter 兜底
    // ==============================================================

    @Test
    @DisplayName("V2 字段全 null → UniverseHardFilter.market_cap 拒 → AI 不调")
    void allV2FieldsNullTriggersUniverseHardFilterReject() throws Exception {
        // 完全没 withV2MarketQuality → 所有 V2 字段为 null
        ContractCandidate bare = new ContractCandidate(
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP",
                "BTC", "USDT",
                new BigDecimal("50000"),
                new BigDecimal("0.05"),
                new BigDecimal("0.01"),
                new BigDecimal("5000000"),
                new BigDecimal("1.5"),
                new BigDecimal("0.0001"),
                new BigDecimal("10000"),
                new BigDecimal("0.02"),
                DirectionBias.BULLISH,
                new BigDecimal("0.02"),
                80,
                new ContractFactorScore(50, 50, 50, 50, 50, 50, 50),
                new BigDecimal("49500"),
                new BigDecimal("49000"),
                new BigDecimal("51000"),
                5, new BigDecimal("3"),
                new BigDecimal("5"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("2"),
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW,
                List.of("test"), List.of(), Instant.now()
                // 不调 withV2MarketQuality → V2 5 字段全 null
        );
        var decision = service.select(List.of(bare));
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(decision.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }

    // ==============================================================
    // UniverseHardFilter 异常 → 安全拒绝
    // ==============================================================

    @Test
    @DisplayName("UniverseHardFilter 抛异常时 candidate 被安全拒绝 → AI 不调")
    void universeHardFilterExceptionIsolated() throws Exception {
        UniverseHardFilter boomFilter = new UniverseHardFilter(List.of(
                new com.example.quant.crypto.filter.UniverseFilter() {
                    @Override
                    public String name() { return "boom"; }
                    @Override
                    public com.example.quant.crypto.filter.FilterResult apply(
                            com.example.quant.crypto.filter.FilterContext ctx) {
                        throw new IllegalStateException("downstream provider exploded");
                    }
                }
        ));
        service = new AiContractSelectionService(ai, om, props, boomFilter);
        var decision = service.select(List.of(v2Eligible("BTC-USDT-SWAP")));
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(decision.source())
                .isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }
}

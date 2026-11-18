package com.example.quant.agent.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.ai.AiAnalysisService;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.filter.MarketCapFilter;
import com.example.quant.crypto.filter.UniverseHardFilter;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase B 必须：AI eligible pool 不绕 UniverseHardFilter。
 * <p>即便上游 scanner 漏过滤、即便 shadow mode / 单测直接调用本服务，
 * eligiblePool 内部会再次过 {@link UniverseHardFilter} +
 * {@link ContractTradeGate#aiHardDenyReasons}（后者作为 V1 trade-gate 兜底）。
 */
class AiContractSelectionServiceEligibilityTest {

    private static ContractCandidate legacyCandidate() {
        return new ContractCandidate(
                MarketType.OKX_SWAP,
                "BTC-USDT-SWAP", "BTC", "USDT",
                new BigDecimal("100"), new BigDecimal("4"), new BigDecimal("0.5"),
                new BigDecimal("500000000"), new BigDecimal("1.5"), new BigDecimal("0.0001"),
                new BigDecimal("1000000"), new BigDecimal("2"),
                DirectionBias.BULLISH, new BigDecimal("0.02"),
                75, new ContractFactorScore(0, 0, 0, 0, 0, 0, 0),
                new BigDecimal("100"), new BigDecimal("98"), new BigDecimal("104"), 3,
                new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("500000"),
                new BigDecimal("500000"), BigDecimal.ONE,
                DirectionBias.BULLISH, DirectionBias.BULLISH,
                RiskLevel.LOW, List.of("结构完整"), List.of(), Instant.now()
        );
    }

    private static ContractCandidate v2EligibleCandidate() {
        return legacyCandidate().withV2MarketQuality(
                new BigDecimal("20000000"),
                new BigDecimal("40000000"),
                new BigDecimal("0.50"),
                new BigDecimal("0.20"),
                "OK"
        );
    }

    @Test
    void legacyCandidateRejectedByUniverseHardFilterNeverCallsAi() {
        // V1 scanner 出来的 candidate，V2 字段全 null
        AiAnalysisService ai = mock(AiAnalysisService.class);
        UniverseHardFilter filter = new UniverseHardFilter(List.of(new MarketCapFilter(new BigDecimal("20000000"))));
        AiContractSelectionService service = new AiContractSelectionService(ai, new ObjectMapper(), new AgentProperties(), filter);

        ContractSelectionDecision result = service.select(List.of(legacyCandidate()));

        // 关键断言：AI 一次都没被调用 —— UniverseHardFilter 把 legacy candidate 挡在了 pool 外
        verify(ai, never()).completeJson(anyString(), anyString());
        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.NO_ELIGIBLE_CANDIDATE);
    }

    @Test
    void v2EligibleCandidatePassesFilterAndReachesAi() {
        // Phase B 期望：V2 字段全过阈值的 candidate 可以进入 AI 决策
        AiAnalysisService ai = mock(AiAnalysisService.class);
        // mock 让 AI 抛异常 → 走 fallback 路径
        when(ai.completeJson(anyString(), anyString())).thenThrow(new RuntimeException("simulated"));
        UniverseHardFilter filter = new UniverseHardFilter(List.of(new MarketCapFilter(new BigDecimal("20000000"))));
        AiContractSelectionService service = new AiContractSelectionService(ai, new ObjectMapper(), new AgentProperties(), filter);

        ContractSelectionDecision result = service.select(List.of(v2EligibleCandidate()));

        // AI 确实被调用（pool 通过了）
        verify(ai).completeJson(anyString(), anyString());
        // mock 抛异常 → fallback
        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.RULE_FALLBACK);
    }

    @Test
    void noopFilterBackwardsCompatible() {
        // 3 参 ctor 用 noop：V1 测试场景保持原行为，legacy candidate 也能进入 AI
        AiAnalysisService ai = mock(AiAnalysisService.class);
        when(ai.completeJson(anyString(), anyString())).thenThrow(new RuntimeException("simulated"));
        AiContractSelectionService service = new AiContractSelectionService(ai, new ObjectMapper(), new AgentProperties());

        ContractSelectionDecision result = service.select(List.of(legacyCandidate()));

        verify(ai).completeJson(anyString(), anyString());
        assertThat(result.source()).isEqualTo(ContractSelectionDecision.DecisionSource.RULE_FALLBACK);
    }
}

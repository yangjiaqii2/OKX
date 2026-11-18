package com.example.quant.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.quant.agent.selection.AiContractSelectionService;
import com.example.quant.agent.selection.ContractSelectionDecision;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShadowSelectionServiceTest {
    @Test
    void recordsFourPoliciesWithoutExecutionDependencies() {
        ShadowSelectionRepository repository = mock(ShadowSelectionRepository.class);
        List<ShadowSelectionEntity> saved = new ArrayList<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            ShadowSelectionEntity row = invocation.getArgument(0);
            saved.add(row);
            return row;
        });
        AiContractSelectionService ai = mock(AiContractSelectionService.class);
        when(ai.select(any())).thenReturn(new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE,
                "BTC-USDT-SWAP", DirectionBias.BULLISH, "MARKET", new BigDecimal("0.8"),
                List.of(new ContractSelectionDecision.RankedCandidate(
                        "BTC-USDT-SWAP", 1, DirectionBias.BULLISH, "MARKET", new BigDecimal("0.8"))),
                List.of(), List.of(), ""));
        Object forbiddenExecutionDependency = mock(Object.class);
        ShadowSelectionService service = new ShadowSelectionService(repository, ai, new AgentProperties());

        service.recordScan("scan-1", List.of(candidate("BTC-USDT-SWAP", 90)), "DEMO");

        assertThat(saved).extracting(ShadowSelectionEntity::getPolicy)
                .containsExactlyInAnyOrder("CURRENT_AI_OVERRIDE", "RULE_FIRST_AI_RANK", "RULE_ONLY", "LIMITED_AI_OVERRIDE");
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getScanId()).isEqualTo("scan-1");
            assertThat(row.getSignalVenue()).isEqualTo("PUBLIC_LIVE");
            assertThat(row.getExecutionVenue()).isEqualTo("DEMO");
        });
        verifyNoInteractions(forbiddenExecutionDependency);
    }

    private static ContractCandidate candidate(String instId, int score) {
        return new ContractCandidate(
                MarketType.OKX_SWAP, instId, "BTC", "USDT", new BigDecimal("100"),
                new BigDecimal("4"), new BigDecimal("0.5"), new BigDecimal("500000000"),
                new BigDecimal("1.5"), new BigDecimal("0.0001"), new BigDecimal("1000000"),
                new BigDecimal("2"), DirectionBias.BULLISH, new BigDecimal("0.02"), score,
                new ContractFactorScore(20, 20, 8, 15, 8, 8, 7), new BigDecimal("100"),
                new BigDecimal("98"), new BigDecimal("104"), 3, new BigDecimal("2"),
                new BigDecimal("2"), new BigDecimal("500000"), new BigDecimal("500000"),
                BigDecimal.ONE, DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW,
                List.of("结构完整"), List.of(), Instant.now());
    }
}

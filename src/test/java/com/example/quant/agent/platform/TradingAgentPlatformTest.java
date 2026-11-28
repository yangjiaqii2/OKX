package com.example.quant.agent.platform;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.StoredOkxCredential;
import com.example.quant.ai.AiAnalysisService;
import com.example.quant.ai.LlmRuntimeConfig;
import com.example.quant.ai.UserLlmProfileService;
import com.example.quant.crypto.ContractAnalysisService;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.order.PendingOrderService;
import com.example.quant.risk.RiskLevel;
import com.example.quant.tradeplan.TradePlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradingAgentPlatformTest {
    private final List<TradingAgentEntity> agents = new ArrayList<>();
    private final List<AgentRunEntity> runs = new ArrayList<>();
    private final AtomicLong agentSeq = new AtomicLong(1);
    private final AtomicLong runSeq = new AtomicLong(1);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TradingAgentRepository agentRepository;
    private AgentRunRepository runRepository;
    private UserLlmProfileService llmProfileService;
    private OkxCredentialStore okxCredentialStore;
    private AiAnalysisService aiAnalysisService;
    private TradingAgentService agentService;
    private TradingAgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        agents.clear();
        runs.clear();
        agentSeq.set(1);
        runSeq.set(1);

        agentRepository = mock(TradingAgentRepository.class);
        runRepository = mock(AgentRunRepository.class);
        llmProfileService = mock(UserLlmProfileService.class);
        okxCredentialStore = mock(OkxCredentialStore.class);
        aiAnalysisService = mock(AiAnalysisService.class);

        when(agentRepository.save(any(TradingAgentEntity.class))).thenAnswer(inv -> {
            TradingAgentEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(agentSeq.getAndIncrement());
                agents.add(e);
            }
            return e;
        });
        when(agentRepository.findByUserNameOrderByUpdatedAtDesc(anyString())).thenAnswer(inv -> {
            String u = inv.getArgument(0);
            return agents.stream().filter(a -> u.equals(a.getUserName())).toList();
        });
        when(agentRepository.findByIdAndUserName(anyLong(), anyString())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            String u = inv.getArgument(1);
            return agents.stream().filter(a -> id.equals(a.getId()) && u.equals(a.getUserName())).findFirst();
        });
        when(agentRepository.findByStatusOrderByUpdatedAtAsc(any(TradingAgentStatus.class))).thenAnswer(inv -> {
            TradingAgentStatus st = inv.getArgument(0);
            return agents.stream().filter(a -> a.getStatus() == st).toList();
        });
        when(agentRepository.countByUserName(anyString())).thenAnswer(inv -> {
            String u = inv.getArgument(0);
            return agents.stream().filter(a -> u.equals(a.getUserName())).count();
        });

        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(inv -> {
            AgentRunEntity r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(runSeq.getAndIncrement());
                runs.add(r);
            }
            return r;
        });

        agentService = new TradingAgentService(
                agentRepository, runRepository, llmProfileService, okxCredentialStore, objectMapper
        );
        orchestrator = new TradingAgentOrchestrator(
                agentRepository,
                runRepository,
                agentService,
                llmProfileService,
                aiAnalysisService,
                okxCredentialStore,
                mock(ContractAnalysisService.class),
                mock(TradePlanService.class),
                mock(PendingOrderService.class),
                objectMapper
        );
    }

    @Test
    void enforcesPlatformHardRiskCapsAndByokRequirementBeforeStart() {
        TradingAgentView created = agentService.saveForUser("alice", new SaveTradingAgentRequest(
                null, "HighRiskAttempt", "CUSTOM", "PAPER", null,
                new BigDecimal("200"), 10, 50, 60, 60,
                new BigDecimal("10.0"), new BigDecimal("25.0"),
                "BOTH", "", "", "test prompt"
        ));

        assertThat(created.maxLeverage()).isEqualTo(TradingAgentService.PLATFORM_HARD_MAX_LEVERAGE);
        assertThat(created.maxOpenPositions()).isEqualTo(TradingAgentService.PLATFORM_HARD_MAX_POSITIONS);
        assertThat(created.maxSingleLossPct()).isEqualByComparingTo(TradingAgentService.PLATFORM_HARD_MAX_SINGLE_LOSS_PCT);
        assertThat(created.maxDailyLossPct()).isEqualByComparingTo(TradingAgentService.PLATFORM_HARD_MAX_DAILY_LOSS_PCT);

        when(llmProfileService.resolveRuntimeConfig("alice", null)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> agentService.startForUser("alice", created.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大模型 API");

        when(llmProfileService.resolveRuntimeConfig("alice", null))
                .thenReturn(Optional.of(new LlmRuntimeConfig("https://api.deepseek.com/v1", "sk-123", "deepseek-chat")));
        TradingAgentView started = agentService.startForUser("alice", created.id());
        assertThat(started.status()).isEqualTo("RUNNING");

        assertThatThrownBy(() -> agentService.pauseForUser("bob", created.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void dispatchesSharedScanWithSameAccountSymbolExclusivityAndAntiHallucinationGuard() throws Exception {
        LlmRuntimeConfig aliceLlm = new LlmRuntimeConfig("https://api.deepseek.com/v1", "sk-alice", "deepseek-chat");
        when(llmProfileService.resolveRuntimeConfig(eq("alice"), any())).thenReturn(Optional.of(aliceLlm));
        when(okxCredentialStore.findActive("alice")).thenReturn(Optional.of(
                new StoredOkxCredential("k", "s", "p", "mask")
        ));

        TradingAgentView a1 = agentService.saveForUser("alice", new SaveTradingAgentRequest(
                null, "Trend-1", "TREND_BALANCED", "PAPER", null,
                new BigDecimal("100"), 2, 5, 60, 60,
                new BigDecimal("1.2"), new BigDecimal("3.0"),
                "BOTH", "", "", ""
        ));
        TradingAgentView a2 = agentService.saveForUser("alice", new SaveTradingAgentRequest(
                null, "Trend-2", "TREND_BALANCED", "PAPER", null,
                new BigDecimal("100"), 2, 5, 60, 60,
                new BigDecimal("1.2"), new BigDecimal("3.0"),
                "BOTH", "", "", ""
        ));
        agentService.startForUser("alice", a1.id());
        agentService.startForUser("alice", a2.id());

        when(aiAnalysisService.completeJson(eq(aliceLlm), anyString(), anyString()))
                .thenReturn(objectMapper.readTree("{\"action\":\"TRADE\",\"instId\":\"SOL-USDT-SWAP\",\"reason\":\"趋势清晰\"}"));

        ContractCandidate sol = sampleCandidate("SOL-USDT-SWAP", 82, new BigDecimal("80"));
        int dispatched = orchestrator.dispatchScan("scan-1", List.of(sol), Map.of());

        assertThat(dispatched).isEqualTo(2);
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).getDecision()).isEqualTo("PAPER_TRADE");
        assertThat(runs.get(0).getSelectedInstId()).isEqualTo("SOL-USDT-SWAP");
        // Second agent under same user must not duplicate SOL-USDT-SWAP
        assertThat(runs.get(1).getDecision()).isEqualTo("SKIP");

        // Anti-hallucination check: LLM returns a symbol not in candidate list
        when(aiAnalysisService.completeJson(eq(aliceLlm), anyString(), anyString()))
                .thenReturn(objectMapper.readTree("{\"action\":\"TRADE\",\"instId\":\"FAKE-USDT-SWAP\",\"reason\":\"hallucinated\"}"));
        orchestrator.dispatchScan("scan-2", List.of(sol), Map.of());
        assertThat(runs.get(2).getDecision()).isEqualTo("BLOCKED");
        assertThat(runs.get(2).getSummaryReason()).contains("FAKE-USDT-SWAP");
    }

    private static ContractCandidate sampleCandidate(String instId, int score, BigDecimal finalRank) {
        return new ContractCandidate(
                MarketType.OKX_SWAP,
                instId,
                "SOL",
                "USDT",
                new BigDecimal("150"),
                new BigDecimal("4.2"),
                new BigDecimal("0.6"),
                new BigDecimal("85000000"),
                new BigDecimal("2.1"),
                new BigDecimal("0.0001"),
                new BigDecimal("10000000"),
                new BigDecimal("0.05"),
                DirectionBias.BULLISH,
                new BigDecimal("2.5"),
                score,
                new ContractFactorScore(20, 18, 20, 8, 8, 4, 4),
                new BigDecimal("150"),
                new BigDecimal("148"),
                new BigDecimal("156"),
                5,
                new BigDecimal("3.0"),
                new BigDecimal("2.5"),
                new BigDecimal("20000"),
                new BigDecimal("20000"),
                new BigDecimal("4.0"),
                DirectionBias.BULLISH,
                DirectionBias.BULLISH,
                RiskLevel.LOW,
                List.of("trend_up"),
                List.of(),
                Instant.now(),
                ContractSignalType.STRONG_LONG,
                "TRADE",
                "LIMIT",
                new BigDecimal("4.2"),
                new BigDecimal("1.1"),
                new BigDecimal("1.3"),
                finalRank,
                null,
                null,
                null
        );
    }
}

package com.example.quant.agent.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.agent.audit.AgentAuditLogger;
import com.example.quant.market.DirectionBias;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanStatus;
import com.example.quant.tradeplan.TradePlanType;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import com.example.quant.tradeplan.CanonicalPlanJsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class TradePlanRecordServiceTest {

    @Test
    void shadowPlanIsNotTradeableAndDoesNotCreateTakeProfitRows() {
        TradePlanRepository plans = mock(TradePlanRepository.class);
        TradeTakeProfitPlanRepository takeProfits = mock(TradeTakeProfitPlanRepository.class);
        TradePlanRecordService service = new TradePlanRecordService(
                plans, takeProfits, mock(AgentAuditLogger.class), new ObjectMapper().findAndRegisterModules());
        TradePlan shadow = new TradePlan(
                UUID.randomUUID(), "BTC-USDT-SWAP", TradePlanType.WATCH, "NO_ENTRY", DirectionBias.BULLISH,
                BigDecimal.ONE, BigDecimal.valueOf(100), BigDecimal.valueOf(98), BigDecimal.valueOf(104), 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(2), 80,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.valueOf(1_000_000), "cross",
                List.of("Shadow"), List.of(), "observation only", false, Instant.now().plusSeconds(300));

        service.record(shadow, TradePlanStatus.CREATED, "SHADOW_ONLY", Map.of(
                "strategyVersion", "contract-analysis-v1",
                "signalPool", "STRONG_LONG",
                "autoTradeEligible", false,
                "dataTimestamp", Instant.parse("2026-08-10T00:00:00Z"),
                "analysisSource", "DETERMINISTIC_AI_REVIEW"));

        ArgumentCaptor<TradePlanEntity> saved = ArgumentCaptor.forClass(TradePlanEntity.class);
        verify(plans).save(saved.capture());
        assertThat(ReflectionTestUtils.getField(saved.getValue(), "allowTrade")).isEqualTo(false);
        assertThat((String) ReflectionTestUtils.getField(saved.getValue(), "planJson"))
                .contains("contract-analysis-v1", "STRONG_LONG", "analysisSource");
        verify(takeProfits, never()).saveAll(any());
    }

    @Test
    void canonicalSnapshotUsesTheExactCompiledAtJsonBoundByPendingOrder() {
        TradePlanRepository plans = mock(TradePlanRepository.class);
        TradeTakeProfitPlanRepository takeProfits = mock(TradeTakeProfitPlanRepository.class);
        TradePlanEntity entity = new TradePlanEntity();
        entity.setId(UUID.randomUUID().toString());
        when(plans.findById(entity.getId())).thenReturn(java.util.Optional.of(entity));
        TradePlanRecordService service = new TradePlanRecordService(
                plans, takeProfits, mock(AgentAuditLogger.class), new ObjectMapper().findAndRegisterModules());
        CanonicalExecutablePlan plan = new CanonicalExecutablePlan(
                UUID.fromString(entity.getId()), "BTC-USDT-SWAP", TradePlanType.OPEN_LONG,
                "strategy-v1", "canonical-plan-v1",
                new CanonicalExecutablePlan.Entry(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("100")),
                new CanonicalExecutablePlan.StopLoss(new BigDecimal("90"), new BigDecimal("90"), "last", "UNCHANGED"),
                List.of(
                        new CanonicalExecutablePlan.ExitLeg("TP1", new BigDecimal("110"), new BigDecimal("110"), new BigDecimal("0.3"), new BigDecimal("3"), BigDecimal.ONE, "first", "UNCHANGED"),
                        new CanonicalExecutablePlan.ExitLeg("TP2", new BigDecimal("120"), new BigDecimal("120"), new BigDecimal("0.4"), new BigDecimal("4"), new BigDecimal("2"), "second", "UNCHANGED"),
                        new CanonicalExecutablePlan.ExitLeg("TP3", new BigDecimal("118"), new BigDecimal("118"), new BigDecimal("0.3"), new BigDecimal("3"), new BigDecimal("1.8"), "final", "UNCHANGED")),
                new CanonicalExecutablePlan.Risk(new BigDecimal("100"), new BigDecimal("100")),
                new CanonicalExecutablePlan.InstrumentSnapshot("BTC-USDT-SWAP", BigDecimal.ONE, "BTC", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.1")),
                CanonicalExecutablePlan.CompilationStatus.ACCEPTED, List.of("ACCEPTED"));
        String snapshot = CanonicalPlanJsonCodec.write(plan, Instant.parse("2026-09-15T00:00:00Z"));

        service.recordCanonicalPlan(UUID.fromString(entity.getId()), plan, snapshot);

        assertThat(ReflectionTestUtils.getField(entity, "canonicalPlanJson")).isEqualTo(snapshot);
    }
}

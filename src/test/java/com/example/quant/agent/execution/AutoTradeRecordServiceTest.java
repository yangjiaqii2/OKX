package com.example.quant.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.agent.selection.ContractSelectionDecision;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

class AutoTradeRecordServiceTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "no_order_submitted: CANONICAL_PLAN_REJECTED: EXECUTABLE_RISK_EXCEEDS_RISK_BUDGET, CONFIRM",
            "no_order_submitted: NET_RISK_REWARD_AFTER_COSTS_TOO_LOW, CONFIRM",
            "no_candidate_passed_auto_gate: candidate_signal_not_tradable_NEUTRAL, SCAN"
    })
    void exposesActualStageForNewAndHistoricalSkippedRecords(String reason, String stage) {
        var repository = mock(AutoTradeRecordRepository.class);
        new AutoTradeRecordService(repository).record(AutoTradeService.AutoTradeResult.skipped(reason), 1, null);
        var saved = ArgumentCaptor.forClass(AutoTradeRecordEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStage()).isEqualTo(stage);
        var historical = saved.getValue();
        historical.setStage("SCAN");
        historical.setReasonMessage(null);
        assertThat(AutoTradeRecordView.from(historical).stage()).isEqualTo(stage);
    }

    @Test
    void selectedCandidateNeverFallsBackToDecisionLevelReason() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AutoTradeRecordService service = new AutoTradeRecordService(repository);
        ContractSelectionDecision selection = new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE,
                "BTC-USDT-SWAP",
                com.example.quant.market.DirectionBias.BULLISH,
                "MARKET",
                new BigDecimal("0.8"),
                List.of(new ContractSelectionDecision.RankedCandidate(
                        "BTC-USDT-SWAP", 1, com.example.quant.market.DirectionBias.BULLISH,
                        "MARKET", new BigDecimal("0.8"), List.of(), List.of())),
                List.of("decision-level-reason"), List.of(), "fallback");

        AutoTradeService.AutoTradeResult selected = new AutoTradeService.AutoTradeResult(
                "SKIPPED", "BTC-USDT-SWAP", null, null, null,
                null, null, null, null, null, "test", Instant.now());
        service.record(selected, 1, null, selection);

        ArgumentCaptor<AutoTradeRecordEntity> captor = ArgumentCaptor.forClass(AutoTradeRecordEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSelectionReason()).isNull();
    }

    @Test
    void listsRecordsByInsertionOrderWhenHistoricalTimestampsAreInconsistent() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        when(repository.findAll(Mockito.<Specification<AutoTradeRecordEntity>>any(), Mockito.any(Pageable.class)))
                .thenReturn(Page.empty());
        AutoTradeRecordService service = new AutoTradeRecordService(repository);

        service.list(null, null, 0, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(Mockito.<Specification<AutoTradeRecordEntity>>any(), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("id")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt")).isNull();
    }

    @Test
    void persistsAiSelectionSummaryWithExecutionRecord() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        AutoTradeRecordService service = new AutoTradeRecordService(repository);
        ContractSelectionDecision selection = new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_PRIMARY,
                ContractSelectionDecision.SelectionAction.TRADE,
                "BTC-USDT-SWAP",
                DirectionBias.BEARISH,
                "MARKET",
                new BigDecimal("0.82"),
                List.of(new ContractSelectionDecision.RankedCandidate(
                        "BTC-USDT-SWAP", 1, DirectionBias.BEARISH, "MARKET", new BigDecimal("0.82"),
                        List.of("AI主选BTC空头"), List.of("资金费率风险"))),
                List.of("AI主选BTC空头"),
                List.of("资金费率风险"),
                ""
        );
        AutoTradeService.AutoTradeResult result = new AutoTradeService.AutoTradeResult(
                "ENTRY_SUBMITTED", "BTC-USDT-SWAP", "plan-1", "pending-1", "ord-1",
                "OPEN_SHORT", "short", 2, new BigDecimal("10"), new BigDecimal("64000"),
                "submitted", Instant.now());

        service.record(result, 2, null, selection);

        ArgumentCaptor<AutoTradeRecordEntity> captor = ArgumentCaptor.forClass(AutoTradeRecordEntity.class);
        verify(repository).save(captor.capture());
        AutoTradeRecordEntity saved = captor.getValue();
        assertThat(saved.getDecisionSource()).isEqualTo("AI_PRIMARY");
        assertThat(saved.getAiConfidence()).isEqualByComparingTo("0.82");
        assertThat(saved.getAiDirection()).isEqualTo("BEARISH");
        assertThat(saved.getAiEntryMode()).isEqualTo("MARKET");
        assertThat(saved.getSelectionReason()).contains("AI主选BTC空头");
    }

    @Test
    void skippedRecordPreservesCandidateTrendWithoutImplyingAnOrder() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AutoTradeRecordService service = new AutoTradeRecordService(repository);
        ContractCandidate candidate = skippedCandidate(DirectionBias.BULLISH);
        AutoTradeService.AutoTradeResult skipped = AutoTradeService.AutoTradeResult.skipped(
                "account_available_balance_unavailable");

        service.record(new AutoTradeService.AutoTradeResult(
                skipped.status(), "SOL-USDT-SWAP", null, null, null,
                null, null, null, null, null, skipped.message(), Instant.now()), 3, candidate, null);

        ArgumentCaptor<AutoTradeRecordEntity> captor = ArgumentCaptor.forClass(AutoTradeRecordEntity.class);
        verify(repository).save(captor.capture());
        AutoTradeRecordEntity saved = captor.getValue();
        assertThat(saved.getAction()).isNull();
        assertThat(saved.getPosSide()).isNull();
        assertThat(saved.getTrendDirection()).isEqualTo("BULLISH");
        assertThat(saved.getSignalType()).isEqualTo("NO_TRADE");
        saved.setAction("OPEN_LONG");
        saved.setPosSide("long");
        AutoTradeRecordView historical = AutoTradeRecordView.from(saved);
        assertThat(historical.action()).isNull();
        assertThat(historical.posSide()).isNull();
        assertThat(historical.trendDirection()).isEqualTo("BULLISH");
    }

    private static ContractCandidate skippedCandidate(DirectionBias trend) {
        return new ContractCandidate(
                MarketType.OKX_SWAP,
                "SOL-USDT-SWAP",
                "SOL",
                "USDT",
                BigDecimal.valueOf(100),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.valueOf(100_000_000),
                BigDecimal.ONE,
                new BigDecimal("0.0001"),
                BigDecimal.valueOf(10_000_000),
                BigDecimal.ZERO,
                trend,
                new BigDecimal("0.01"),
                65,
                new ContractFactorScore(10, 10, 10, 10, 10, 10, 10),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(98),
                BigDecimal.valueOf(105),
                2,
                new BigDecimal("1.8"),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(50_000),
                BigDecimal.valueOf(50_000),
                BigDecimal.ONE,
                DirectionBias.BULLISH,
                DirectionBias.BULLISH,
                RiskLevel.LOW,
                List.of(),
                List.of(),
                Instant.now(),
                ContractSignalType.NO_TRADE,
                "WAIT_CONFIRM",
                "MARKET",
                BigDecimal.ONE,
                new BigDecimal("1.5"),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(60),
                null,
                null,
                null
        );
    }
}

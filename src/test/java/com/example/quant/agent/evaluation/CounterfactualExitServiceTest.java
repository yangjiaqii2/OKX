package com.example.quant.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.crypto.dto.ContractCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CounterfactualExitServiceTest {
    @Test
    void persistsSevenPoliciesOnlyAsPublicCounterfactualAndKeepsUnknownFeesNull() {
        CounterfactualExitRepository repository = mock(CounterfactualExitRepository.class);
        List<CounterfactualExitEntity> saved = new ArrayList<>();
        when(repository.findByUserNameAndPendingOrderIdAndPolicy(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> { saved.add(invocation.getArgument(0)); return invocation.getArgument(0); });
        CounterfactualExitService service = new CounterfactualExitService(repository);
        Instant entryTime = Instant.parse("2026-06-18T00:00:00Z");
        CounterfactualExitService.Entry entry = new CounterfactualExitService.Entry(
                "p1", "plan1", "BTC-USDT-SWAP", "LONG", entryTime,
                new BigDecimal("100"), BigDecimal.ONE, new BigDecimal("10"), new BigDecimal("130"));
        List<ContractCandle> candles = List.of(
                candle(entryTime.plus(5, ChronoUnit.MINUTES), "100", "102", "99", "101"),
                candle(entryTime.plus(60, ChronoUnit.MINUTES), "100", "105", "95", "102"),
                candle(entryTime.plus(120, ChronoUnit.MINUTES), "102", "131", "101", "130"));

        service.evaluateAndPersist(entry, candles);

        assertThat(saved).hasSize(7);
        assertThat(saved).extracting(CounterfactualExitEntity::getPolicy)
                .containsExactlyInAnyOrderElementsOf(CounterfactualExitService.POLICIES);
        assertThat(saved).allSatisfy(row -> assertThat(row.getNetPnl()).isNull());
        assertThat(saved).allSatisfy(row -> assertThat(row.getDataQuality()).contains("FEE_UNAVAILABLE"));
    }

    private static ContractCandle candle(Instant at, String open, String high, String low, String close) {
        return new ContractCandle(at, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), BigDecimal.ONE);
    }
}

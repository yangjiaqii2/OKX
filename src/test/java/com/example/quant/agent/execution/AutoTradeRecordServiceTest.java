package com.example.quant.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AutoTradeRecordServiceTest {

    @Test
    void persistsSkippedRejectedFailedAndUnknownRecordsForReview() {
        AutoTradeRecordRepository repository = mock(AutoTradeRecordRepository.class);
        when(repository.save(any(AutoTradeRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AutoTradeRecordService service = new AutoTradeRecordService(repository);

        service.record(result("SKIPPED", "score_below_threshold"), 5, null);
        service.record(result("REJECTED", "spread_bps_above_8"), 5, null);
        service.record(result("FAILED", "OKX HTTP 401"), 5, null);
        service.record(result("UNKNOWN_SUBMIT_STATUS", "OKX submit timeout"), 5, null);

        ArgumentCaptor<AutoTradeRecordEntity> captor = ArgumentCaptor.forClass(AutoTradeRecordEntity.class);
        verify(repository, org.mockito.Mockito.times(4)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AutoTradeRecordEntity::getStatus)
                .containsExactly("SKIPPED", "REJECTED", "FAILED", "UNKNOWN_SUBMIT_STATUS");
        assertThat(captor.getAllValues())
                .extracting(AutoTradeRecordEntity::getReasonCode)
                .containsExactly("score_below_threshold", "spread_bps_above_8", "OKX HTTP 401", "OKX submit timeout");
    }

    private static AutoTradeService.AutoTradeResult result(String status, String message) {
        return new AutoTradeService.AutoTradeResult(
                status,
                "BTC-USDT-SWAP",
                "plan-1",
                "pending-1",
                null,
                "OPEN_SHORT",
                "short",
                2,
                null,
                null,
                message,
                Instant.now()
        );
    }
}

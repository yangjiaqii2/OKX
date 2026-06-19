package com.example.quant.agent.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.auth.AuthUserContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TradeEventServiceTest {

    @Test
    void recordsLifecycleEventWithCurrentUserAndOkxIdentifiers() {
        TradeEventRepository repository = mock(TradeEventRepository.class);
        when(repository.save(any(TradeEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TradeEventService service = new TradeEventService(
                repository,
                Clock.fixed(Instant.parse("2026-06-19T08:00:00Z"), ZoneOffset.UTC)
        );

        AuthUserContext.callAs("userA", () -> service.record(new TradeEventPayload(
                null,
                "BTC-USDT-SWAP",
                "pending-1",
                11L,
                22L,
                TradeEventType.ENTRY_SUBMITTED,
                "SUBMITTING",
                "SUBMITTED",
                "OKX_ACCEPTED",
                "OKX order accepted",
                "okx-1",
                "cl-1",
                null
        )));

        ArgumentCaptor<TradeEventEntity> captor = ArgumentCaptor.forClass(TradeEventEntity.class);
        verify(repository).save(captor.capture());
        TradeEventEntity saved = captor.getValue();
        assertThat(saved.getUserName()).isEqualTo("userA");
        assertThat(saved.getInstId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(saved.getPendingOrderId()).isEqualTo("pending-1");
        assertThat(saved.getAutoTradeRecordId()).isEqualTo(11L);
        assertThat(saved.getTradeOrderId()).isEqualTo(22L);
        assertThat(saved.getEventType()).isEqualTo("ENTRY_SUBMITTED");
        assertThat(saved.getOldStatus()).isEqualTo("SUBMITTING");
        assertThat(saved.getNewStatus()).isEqualTo("SUBMITTED");
        assertThat(saved.getReasonCode()).isEqualTo("OKX_ACCEPTED");
        assertThat(saved.getOkxOrdId()).isEqualTo("okx-1");
        assertThat(saved.getClOrdId()).isEqualTo("cl-1");
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-06-19T08:00:00Z"));
    }
}

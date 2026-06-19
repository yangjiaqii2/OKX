package com.example.quant.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.ClosePositionRecordEntity;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TradeReviewServiceTest {

    @Test
    void createsReviewForClosedMaxHoldTrade() throws Exception {
        TradeReviewRepository repository = mock(TradeReviewRepository.class);
        when(repository.findFirstByClosePositionRecordId(100L)).thenReturn(Optional.empty());
        when(repository.save(any(TradeReviewEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TradeReviewService service = new TradeReviewService(
                repository,
                Clock.fixed(Instant.parse("2026-06-19T08:00:00Z"), ZoneOffset.UTC)
        );
        ClosePositionRecordEntity closeRecord = closeRecord("MAX_HOLD_TIMEOUT", new BigDecimal("-0.30"));
        setId(closeRecord, 100L);

        service.reviewClosedTrade(closeRecord);

        ArgumentCaptor<TradeReviewEntity> captor = ArgumentCaptor.forClass(TradeReviewEntity.class);
        verify(repository).save(captor.capture());
        TradeReviewEntity saved = captor.getValue();
        assertThat(saved.getClosePositionRecordId()).isEqualTo(100L);
        assertThat(saved.getReviewReason()).isEqualTo("最大持仓时间退出");
        assertThat(saved.getStrategyTag()).isEqualTo("MAX_HOLD_TIME_EXIT");
        assertThat(saved.getImprovementHint()).contains("持仓时间");
        assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-06-19T08:00:00Z"));
    }

    private static ClosePositionRecordEntity closeRecord(String source, BigDecimal realizedPnl) {
        ClosePositionRecordEntity entity = new ClosePositionRecordEntity();
        entity.setUserName("userA");
        entity.setInstId("BTC-USDT-SWAP");
        entity.setPendingOrderId("pending-1");
        entity.setStatus("CLOSED");
        entity.setSource(source);
        entity.setRealizedPnl(realizedPnl);
        entity.setCreatedAt(Instant.parse("2026-06-19T07:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-06-19T07:00:00Z"));
        return entity;
    }

    private static void setId(ClosePositionRecordEntity entity, Long id) throws Exception {
        Field field = ClosePositionRecordEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}

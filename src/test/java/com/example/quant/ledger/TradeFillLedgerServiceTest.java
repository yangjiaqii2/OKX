package com.example.quant.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.okxtrade.OkxCurrentOrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TradeFillLedgerServiceTest {
    @Test
    void persistsExchangeFillOnceByOwnerInstrumentAndTradeId() {
        TradeFillRepository repository = mock(TradeFillRepository.class);
        when(repository.findByUserNameAndInstIdAndTradeId("alice", "BTC-USDT-SWAP", "trade-1"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TradeOrderEntity order = new TradeOrderEntity();
        order.setUserName("alice");
        order.setTradePlanId("plan-1");
        order.setPendingOrderId("pending-1");
        order.setOkxOrdId("ord-1");
        order.setClOrdId("cl-1");
        order.setInstId("BTC-USDT-SWAP");
        TradeFillLedgerService service = new TradeFillLedgerService(repository);

        service.record(order, new OkxCurrentOrderService.Fill(
                "trade-1", "buy", "long", new BigDecimal("100.25"), new BigDecimal("4"),
                new BigDecimal("-0.02"), "USDT", new BigDecimal("1.50"),
                Instant.parse("2026-08-09T12:00:00Z")));

        ArgumentCaptor<TradeFillEntity> captor = ArgumentCaptor.forClass(TradeFillEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTradeId()).isEqualTo("trade-1");
        assertThat(captor.getValue().getFee()).isEqualByComparingTo("-0.02");
        assertThat(captor.getValue().getFillPnl()).isEqualByComparingTo("1.50");
    }

    @Test
    void repeatedWsAndRestReplayKeepsSignedFeeAndPersistsFillOnlyOnce() {
        TradeFillRepository repository = mock(TradeFillRepository.class);
        TradeFillEntity existing = new TradeFillEntity();
        when(repository.findByUserNameAndInstIdAndTradeId("alice", "XAU-USDT-SWAP", "xau-fill-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TradeOrderEntity order = new TradeOrderEntity();
        order.setUserName("alice");
        order.setPendingOrderId("pending-xau");
        order.setOkxOrdId("child-ord");
        order.setInstId("XAU-USDT-SWAP");
        OkxCurrentOrderService.Fill fill = new OkxCurrentOrderService.Fill(
                "xau-fill-1", "sell", "long", new BigDecimal("4416.4"), new BigDecimal("717"),
                new BigDecimal("-1.571664"), "USDT", new BigDecimal("-23.2472"),
                Instant.parse("2026-09-06T01:00:00Z"));
        TradeFillLedgerService service = new TradeFillLedgerService(repository);

        service.record(order, fill);
        service.record(order, fill);

        ArgumentCaptor<TradeFillEntity> captor = ArgumentCaptor.forClass(TradeFillEntity.class);
        verify(repository, times(1)).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getFee()).isEqualByComparingTo("-1.571664");
        assertThat(captor.getValue().getFillPnl()).isEqualByComparingTo("-23.2472");
    }
}

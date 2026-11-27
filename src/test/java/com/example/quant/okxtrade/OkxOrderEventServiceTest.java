package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.ledger.TradeFillLedgerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OkxOrderEventServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void ignoresDuplicateAndOlderEventsAfterFilled() throws Exception {
        TradeOrderEntity order = order("SUBMITTED", null);
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        TradeFillLedgerService ledger = mock(TradeFillLedgerService.class);
        when(repository.findFirstByUserNameAndOkxOrdId("alice", "ord-1")).thenReturn(Optional.of(order));
        OkxOrderEventService service = new OkxOrderEventService(repository, ledger);

        AuthUserContext.runAs("alice", () -> {
            assertThat(service.apply(event("filled", "10", "2000", "trade-1"))).isTrue();
            assertThat(service.apply(event("filled", "10", "2000", "trade-1"))).isFalse();
            assertThat(service.apply(event("live", "0", "1000", ""))).isFalse();
            assertThat(service.apply(event("filled", "10", "3000", ""))).isFalse();
        });

        assertThat(order.getStatus()).isEqualTo("FILLED");
        assertThat(order.getFilledSize()).isEqualByComparingTo("10");
        assertThat(order.getOkxUpdatedAt()).isEqualTo(Instant.ofEpochMilli(2000));
        verify(repository, times(1)).save(order);
        verify(ledger, times(1)).record(org.mockito.ArgumentMatchers.eq(order),
                org.mockito.ArgumentMatchers.any(OkxCurrentOrderService.Fill.class));
    }

    @Test
    void mapsPartialFillThenCancelWithoutLosingFilledSize() throws Exception {
        TradeOrderEntity order = order("SUBMITTED", null);
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findFirstByUserNameAndOkxOrdId("alice", "ord-1")).thenReturn(Optional.of(order));
        OkxOrderEventService service = new OkxOrderEventService(repository, mock(TradeFillLedgerService.class));

        AuthUserContext.runAs("alice", () -> {
            assertThat(service.apply(event("partially_filled", "4", "2000", "trade-1"))).isTrue();
            assertThat(service.apply(event("canceled", "4", "2000", ""))).isTrue();
        });

        assertThat(order.getStatus()).isEqualTo("PARTIALLY_FILLED_CANCELED");
        assertThat(order.getRemainingSize()).isEqualByComparingTo("6");
        verify(repository, times(2)).save(order);
    }

    @Test
    void keepsMultiFillTerminalEventReconcilingUntilRestFillsAreComplete() throws Exception {
        TradeOrderEntity order = order("SUBMITTED", null);
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        TradeFillLedgerService ledger = mock(TradeFillLedgerService.class);
        when(repository.findFirstByUserNameAndOkxOrdId("alice", "ord-1")).thenReturn(Optional.of(order));
        OkxOrderEventService service = new OkxOrderEventService(repository, ledger);
        JsonNode finalEvent = JSON.readTree("""
                {"instType":"SWAP","instId":"BTC-USDT-SWAP","ordId":"ord-1","clOrdId":"AUTO1",
                 "state":"filled","sz":"717","accFillSz":"717","avgPx":"100.5","uTime":"2000",
                 "tradeId":"trade-last","side":"buy","posSide":"long","fillPx":"100.5",
                 "fillSz":"12","fillFee":"-0.01","fillFeeCcy":"USDT","fillPnl":"0","fillTime":"2000"}
                """);

        boolean applied = AuthUserContext.callAs("alice", () -> service.apply(finalEvent));

        assertThat(applied).isTrue();
        assertThat(order.getStatus()).isEqualTo("RECONCILING");
        assertThat(order.getErrorMessage()).isEqualTo("TERMINAL_FILLS_INCOMPLETE");
        verify(ledger).record(org.mockito.ArgumentMatchers.eq(order),
                org.mockito.ArgumentMatchers.any(OkxCurrentOrderService.Fill.class));
    }

    @Test
    void refusesToAttachUnknownExchangeOrderToCurrentOwner() throws Exception {
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findFirstByUserNameAndOkxOrdId("alice", "ord-1")).thenReturn(Optional.empty());
        OkxOrderEventService service = new OkxOrderEventService(repository, mock(TradeFillLedgerService.class));

        boolean applied = AuthUserContext.callAs("alice", () -> service.apply(event("live", "0", "1000", "")));

        assertThat(applied).isFalse();
        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsOrderEventWithoutExchangeUpdateTimestamp() throws Exception {
        TradeOrderEntity order = order("SUBMITTED", null);
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        when(repository.findFirstByUserNameAndOkxOrdId("alice", "ord-1")).thenReturn(Optional.of(order));
        OkxOrderEventService service = new OkxOrderEventService(repository, mock(TradeFillLedgerService.class));
        JsonNode malformed = event("filled", "10", "", "trade-1");

        boolean applied = AuthUserContext.callAs("alice", () -> service.apply(malformed));

        assertThat(applied).isFalse();
        assertThat(order.getStatus()).isEqualTo("SUBMITTED");
        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static TradeOrderEntity order(String status, Instant updatedAt) {
        TradeOrderEntity order = new TradeOrderEntity();
        order.setUserName("alice");
        order.setOrderRole("ENTRY");
        order.setInstId("BTC-USDT-SWAP");
        order.setSide("buy");
        order.setPosSide("long");
        order.setOrdType("limit");
        order.setTdMode("cross");
        order.setSize(new java.math.BigDecimal("10"));
        order.setClOrdId("AUTO1");
        order.setOkxOrdId("ord-1");
        order.setStatus(status);
        order.setOkxUpdatedAt(updatedAt);
        order.setCreatedAt(Instant.EPOCH);
        order.setUpdatedAt(Instant.EPOCH);
        return order;
    }

    private static JsonNode event(String state, String accFillSz, String uTime, String tradeId) {
        try {
            return JSON.readTree("""
                    {"instId":"BTC-USDT-SWAP","ordId":"ord-1","clOrdId":"AUTO1","state":"%s",
                     "sz":"10","accFillSz":"%s","avgPx":"100.5","uTime":"%s",
                     "tradeId":"%s","side":"buy","posSide":"long","fillPx":"100.5",
                     "fillSz":"%s","fillFee":"-0.01","fillFeeCcy":"USDT","fillPnl":"1.25","fillTime":"%s"}
                    """.formatted(state, accFillSz, uTime, tradeId,
                    tradeId.isBlank() ? "0" : accFillSz, uTime));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}

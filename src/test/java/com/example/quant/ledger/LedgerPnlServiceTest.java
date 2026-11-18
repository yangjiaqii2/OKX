package com.example.quant.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerPnlServiceTest {
    @Test
    void calculatesNetFromFillPnlAndFillFeesPlusFundingOnly() {
        TradeFillRepository fills = mock(TradeFillRepository.class);
        TradeFillEntity fill = new TradeFillEntity();
        fill.setPendingOrderId("pending-1");
        fill.setInstId("BTC-USDT-SWAP");
        fill.setFillPnl(new BigDecimal("10"));
        fill.setFee(new BigDecimal("-0.20"));
        when(fills.findByUserName("alice")).thenReturn(List.of(fill));
        AccountBillRepository bills = mock(AccountBillRepository.class);
        AccountBillEntity funding = bill("FUNDING", "BTC-USDT-SWAP", "-1.20", "0");
        AccountBillEntity externalFunding = bill("FUNDING", "ETH-USDT-SWAP", "-9", "0");
        AccountBillEntity trade = bill("TRADE", "BTC-USDT-SWAP", "10", "-0.10");
        when(bills.findByUserName("alice")).thenReturn(List.of(funding, externalFunding, trade));
        LedgerPnlService service = new LedgerPnlService(fills, bills);

        LedgerPnlService.Summary summary = service.summary("alice", true);

        assertThat(summary.grossRealizedPnl()).isEqualByComparingTo("10");
        assertThat(summary.tradingFees()).isEqualByComparingTo("-0.20");
        assertThat(summary.funding()).isEqualByComparingTo("-1.20");
        assertThat(summary.netRealizedPnl()).isEqualByComparingTo("8.60");
        assertThat(summary.dataQuality()).isEqualTo("EXCHANGE_RECENT_LEDGER");
    }

    @Test
    void excludesUntraceableFillsAndTheirInstrumentFunding() {
        TradeFillRepository fills = mock(TradeFillRepository.class);
        TradeFillEntity external = new TradeFillEntity();
        external.setInstId("ETH-USDT-SWAP");
        external.setFillPnl(new BigDecimal("100"));
        external.setFee(new BigDecimal("-1"));
        when(fills.findByUserName("alice")).thenReturn(List.of(external));
        AccountBillRepository bills = mock(AccountBillRepository.class);
        when(bills.findByUserName("alice")).thenReturn(List.of(
                bill("FUNDING", "ETH-USDT-SWAP", "-5", "0")));

        LedgerPnlService.Summary summary = new LedgerPnlService(fills, bills).summary("alice", true);

        assertThat(summary.netRealizedPnl()).isEqualByComparingTo("0");
    }

    @Test
    void returnsUnavailableRatherThanZeroBeforeLedgerSync() {
        LedgerPnlService service = new LedgerPnlService(mock(TradeFillRepository.class),
                mock(AccountBillRepository.class));

        LedgerPnlService.Summary summary = service.summary("alice", false);

        assertThat(summary.netRealizedPnl()).isNull();
        assertThat(summary.dataQuality()).isEqualTo("LEDGER_SYNC_REQUIRED");
    }

    private static AccountBillEntity bill(String category, String instId, String pnl, String fee) {
        AccountBillEntity bill = new AccountBillEntity();
        bill.setCategory(category);
        bill.setInstId(instId);
        bill.setPnl(new BigDecimal(pnl));
        bill.setFee(new BigDecimal(fee));
        return bill;
    }
}

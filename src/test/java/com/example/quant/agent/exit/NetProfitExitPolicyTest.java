package com.example.quant.agent.exit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetProfitExitPolicyTest {
    @Test
    void quoteUsesAccountFeeGroupActualFundingAndTheFullBidDepth() throws Exception {
        var rest = org.mockito.Mockito.mock(com.example.quant.crypto.OkxRestClient.class);
        var order = org.mockito.Mockito.mock(com.example.quant.order.PendingOrder.class);
        var json = new ObjectMapper();
        String id = "BTC-USDT-SWAP";
        org.mockito.Mockito.when(order.instId()).thenReturn(id);
        org.mockito.Mockito.when(order.posSide()).thenReturn("long");
        org.mockito.Mockito.when(order.tdMode()).thenReturn("cross");
        org.mockito.Mockito.when(order.action()).thenReturn(com.example.quant.tradeplan.TradePlanType.OPEN_LONG);
        org.mockito.Mockito.when(rest.privateGet("/api/v5/account/positions?instId=" + id)).thenReturn(json.readTree(
                """
                {"data":[{"instId":"BTC-USDT-SWAP","posSide":"net","mgnMode":"cross","pos":"10","avgPx":"100","fee":"-0.5","fundingFee":"-0.1"}]}
                """));
        org.mockito.Mockito.when(rest.publicGet("/api/v5/public/instruments?instType=SWAP&instId=" + id))
                .thenReturn(json.readTree("""
                        {"data":[{"groupId":"3"}]}
                        """));
        org.mockito.Mockito.when(rest.privateGet("/api/v5/account/trade-fee?instType=SWAP"))
                .thenReturn(json.readTree("""
                        {"data":[{"feeGroup":[{"groupId":"3","taker":"-0.0005","maker":"-0.0002"}]}]}
                        """));
        org.mockito.Mockito.when(rest.publicGet("/api/v5/market/books?instId=" + id + "&sz=50"))
                .thenReturn(json.readTree("""
                        {"data":[{"ts":"1000","bids":[[100,2],[99,3]]}]}
                        """));
        var rules = new com.example.quant.okxtrade.OkxInstrumentRules(id, BigDecimal.ONE, "BTC",
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.01"));
        var quote = new NetProfitExitPolicy(rest).quote(order, rules, BigDecimal.valueOf(5), "TP1", Instant.ofEpochMilli(1000));
        assertThat(quote.limit()).isEqualByComparingTo("100.42");
        assertThat(quote.trigger()).isEqualByComparingTo("101.02");
    }

    @Test
    void bothDirectionsCoverEntryCostAndExitFeeOnExecutedNotional() {
        BigDecimal price = BigDecimal.valueOf(100);
        BigDecimal cost = new BigDecimal("0.06");
        BigDecimal rate = new BigDecimal("0.0005");
        BigDecimal target = new BigDecimal("0.003");
        BigDecimal tick = new BigDecimal("0.01");
        assertThat(NetProfitExitPolicy.targetPrice(price, cost, rate, target, false, tick))
                .isEqualByComparingTo("100.42");
        assertThat(NetProfitExitPolicy.targetPrice(price, cost, rate, target, true, tick))
                .isEqualByComparingTo("99.59");
    }

    @Test
    void depthUsesFullExitSizeAndRefusesInsufficientLiquidity() throws Exception {
        var bids = new ObjectMapper().readTree("[[100,2],[99,3]]");
        assertThat(NetProfitExitPolicy.bookImpact(bids, BigDecimal.valueOf(5))).isEqualByComparingTo("0.6");
        assertThatThrownBy(() -> NetProfitExitPolicy.bookImpact(bids, BigDecimal.TEN))
                .hasMessage("INSUFFICIENT_EXIT_DEPTH");
    }

    @Test
    void trendAndTimeUseContiguousClosedBarsAndInvertForShorts() {
        long period = 300000;
        Instant now = Instant.ofEpochMilli(30 * period);
        var rows = new ObjectMapper().createArrayNode();
        for (int i = 29; i >= 0; i--) {
            rows.addArray().add(i * period).add(0).add(0).add(0).add(100 + i).add(0).add(0).add(0).add("1");
        }
        assertThat(NetProfitExitPolicy.exitReason(rows, false, Instant.EPOCH, now, true)).isNull();
        assertThat(NetProfitExitPolicy.exitReason(rows, true, Instant.EPOCH, now, true)).isEqualTo("TWO_WEAK_EMA20_BARS");
        assertThat(NetProfitExitPolicy.exitReason(rows, false, Instant.EPOCH, now, false)).isEqualTo("SIX_BARS_WITHOUT_FIRST_EXIT");
        rows.remove(2);
        assertThat(NetProfitExitPolicy.exitReason(rows, true, Instant.EPOCH, now, false)).isNull();
    }
}

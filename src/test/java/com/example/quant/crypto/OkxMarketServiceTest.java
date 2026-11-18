package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OkxMarketServiceTest {
    @Test
    void historicalRequestAnchorsThePageAndKeepsOnlyClosedBars() throws Exception {
        OkxRestClient client = mock(OkxRestClient.class);
        when(client.publicGet("/api/v5/market/history-candles?instId=BTC-USDT-SWAP&bar=15m&limit=100&after=1786254000000"))
                .thenReturn(new ObjectMapper().readTree("""
                        {"code":"0","data":[
                          ["1786253100000","101","103","100","102","12","0","0","0"],
                          ["1786252800000","100","102","99","101","10","0","0","1"]
                        ]}
                        """));

        var candles = new OkxMarketService(client)
                .historicalCandlesBefore("BTC-USDT-SWAP", "15m", Instant.ofEpochMilli(1786254000000L));

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).timestamp()).isEqualTo(Instant.ofEpochMilli(1786252800000L));
        assertThat(candles.get(0).close()).isEqualByComparingTo("101");
    }

    @Test
    void excludesUnclosedOkxCandlesFromStrategyInput() throws Exception {
        OkxRestClient client = mock(OkxRestClient.class);
        when(client.publicGet("/api/v5/market/candles?instId=BTC-USDT-SWAP&bar=5m&limit=2"))
                .thenReturn(new ObjectMapper().readTree("""
                        {"code":"0","data":[
                          ["1786253100000","101","103","100","102","12","0","0","0"],
                          ["1786252800000","100","102","99","101","10","0","0","1"]
                        ]}
                        """));

        var candles = new OkxMarketService(client).candles("BTC-USDT-SWAP", "5m", 2);

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).timestamp()).isEqualTo(Instant.ofEpochMilli(1786252800000L));
        assertThat(candles.get(0).close()).isEqualByComparingTo("101");
    }
}

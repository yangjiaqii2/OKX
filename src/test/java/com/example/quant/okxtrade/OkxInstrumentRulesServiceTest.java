package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OkxInstrumentRulesServiceTest {
    private static final String BULK_PATH = "/api/v5/public/instruments?instType=SWAP";
    private static final String SINGLE_PATH = "/api/v5/public/instruments?instType=SWAP&instId=BTC-USDT-SWAP";

    @Test
    void failsClosedWhenOkxInstrumentRulesCannotBeLoaded() {
        OkxRestClient client = mock(OkxRestClient.class);
        when(client.publicGet(BULK_PATH)).thenThrow(new IllegalStateException("network unavailable"));
        when(client.publicGet(SINGLE_PATH)).thenThrow(new IllegalStateException("network unavailable"));

        assertThatThrownBy(() -> new OkxInstrumentRulesService(client).rules("BTC-USDT-SWAP"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法加载OKX合约规则");
    }

    @Test
    void rejectsIncompleteInstrumentMetadataInsteadOfInventingDefaults() throws Exception {
        OkxRestClient client = mock(OkxRestClient.class);
        when(client.publicGet(BULK_PATH)).thenReturn(new ObjectMapper().readTree("""
                {"code":"0","data":[]}
                """));
        when(client.publicGet(SINGLE_PATH)).thenReturn(new ObjectMapper().readTree("""
                {"code":"0","data":[{"instId":"BTC-USDT-SWAP","instType":"SWAP","ctValCcy":"BTC"}]}
                """));

        assertThatThrownBy(() -> new OkxInstrumentRulesService(client).rules("BTC-USDT-SWAP"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("规则字段无效");
    }

    @Test
    void rejectsMetadataForUnexpectedInstrument() throws Exception {
        OkxRestClient client = mock(OkxRestClient.class);
        when(client.publicGet(BULK_PATH)).thenReturn(new ObjectMapper().readTree("""
                {"code":"0","data":[]}
                """));
        when(client.publicGet(SINGLE_PATH)).thenReturn(new ObjectMapper().readTree("""
                {"code":"0","data":[{"instId":"BTC-USDT","instType":"SPOT","ctVal":"1","ctValCcy":"BTC","lotSz":"1","minSz":"1","tickSz":"0.1"}]}
                """));

        assertThatThrownBy(() -> new OkxInstrumentRulesService(client).rules("BTC-USDT-SWAP"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法加载OKX合约规则");
    }

    @Test
    void expiredSymbolMetadataIsRefetched() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OkxRestClient client = mock(OkxRestClient.class);
        when(client.publicGet(BULK_PATH)).thenReturn(
                mapper.readTree(bulkInstrumentJson("1")),
                mapper.readTree(bulkInstrumentJson("2"))
        );
        OkxInstrumentRulesService service = new OkxInstrumentRulesService(client, Duration.ZERO);

        assertThat(service.rules("BTC-USDT-SWAP").contractValue()).isEqualByComparingTo("1");
        assertThat(service.rules("BTC-USDT-SWAP").contractValue()).isEqualByComparingTo("2");
        verify(client, times(2)).publicGet(BULK_PATH);
    }

    @Test
    void bulkCacheHitAvoidsSingleInstrumentRequests() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OkxRestClient client = mock(OkxRestClient.class);
        when(client.publicGet(BULK_PATH)).thenReturn(mapper.readTree(bulkInstrumentJson("1")));
        OkxInstrumentRulesService service = new OkxInstrumentRulesService(client);

        service.rules("BTC-USDT-SWAP");
        service.rules("BTC-USDT-SWAP");

        verify(client, times(1)).publicGet(BULK_PATH);
        verify(client, org.mockito.Mockito.never()).publicGet(SINGLE_PATH);
    }

    private static String bulkInstrumentJson(String contractValue) {
        return "{\"code\":\"0\",\"data\":[{\"instId\":\"BTC-USDT-SWAP\",\"instType\":\"SWAP\","
                + "\"ctVal\":\"" + contractValue + "\",\"ctValCcy\":\"BTC\",\"lotSz\":\"1\","
                + "\"minSz\":\"1\",\"tickSz\":\"0.1\"}]}";
    }
}

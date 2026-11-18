package com.example.quant.news;

import com.example.quant.crypto.OkxRestClient;
import com.example.quant.market.MarketType;
import com.example.quant.news.dto.NewsItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OkxAnnouncementNewsSourceAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void returnsMatchingOfficialDelistingAnnouncementAsNegativeNews() throws Exception {
        FakeOkxRestClient client = new FakeOkxRestClient("""
                {
                  "code":"0",
                  "data":[{
                    "details":[
                      {"annType":"announcements-delistings","title":"OKX to delist GODS spot trading pairs","url":"https://www.okx.com/help/delist-gods","pTime":"1786089609991","businessPTime":"1786089600000"},
                      {"annType":"announcements-delistings","title":"OKX to delist BTC spot trading pairs","url":"https://www.okx.com/help/delist-btc","pTime":"1786089609992","businessPTime":"1786089600000"}
                    ],
                    "totalPage":"1"
                  }],
                  "msg":""
                }
                """);
        OkxAnnouncementNewsSourceAdapter adapter = new OkxAnnouncementNewsSourceAdapter(
                client);

        List<NewsItem> items = adapter.search(MarketType.OKX_SWAP, "GODS-USDT-SWAP", "GODS",
                List.of("GODS-USDT-SWAP", "GODS"), 10, 24 * 365 * 10);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).contains("GODS");
        assertThat(items.get(0).source()).isEqualTo("OKX_OFFICIAL");
        assertThat(items.get(0).sentiment()).isEqualTo("NEGATIVE");
        assertThat(items.get(0).importanceScore()).isEqualTo(100);
        assertThat(items.get(0).riskKeywords()).contains("DELISTING");
        assertThat(client.paths()).contains("/api/v5/support/announcements?annType=announcements-delistings&page=1");
    }

    @Test
    void returnsEmptyForUnrelatedAnnouncement() throws Exception {
        FakeOkxRestClient client = new FakeOkxRestClient("""
                {"code":"0","data":[{"details":[
                  {"annType":"announcements-delistings","title":"OKX to delist ABC spot trading pairs","url":"https://example.test/abc","pTime":"1786089609991"}
                ]}],"msg":""}
                """);
        OkxAnnouncementNewsSourceAdapter adapter = new OkxAnnouncementNewsSourceAdapter(
                client);

        assertThat(adapter.search(MarketType.OKX_SWAP, "GODS-USDT-SWAP", "GODS",
                List.of("GODS-USDT-SWAP", "GODS"), 10, 24 * 365 * 10)).isEmpty();
    }

    @Test
    void rejectsNonSuccessBusinessCodeInsteadOfReturningSuccessfulEmptyResult() throws Exception {
        OkxAnnouncementNewsSourceAdapter adapter = new OkxAnnouncementNewsSourceAdapter(
                new FakeOkxRestClient("{\"code\":\"50001\",\"data\":[],\"msg\":\"unavailable\"}"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.search(
                        MarketType.OKX_SWAP, "GODS-USDT-SWAP", "GODS", List.of("GODS"), 10, 24))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("code=50001");
    }

    @Test
    void rejectsMalformedSuccessPayloadInsteadOfTreatingItAsNoMatch() throws Exception {
        OkxAnnouncementNewsSourceAdapter adapter = new OkxAnnouncementNewsSourceAdapter(
                new FakeOkxRestClient("{\"code\":\"0\",\"data\":{}}"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.search(
                        MarketType.OKX_SWAP, "GODS-USDT-SWAP", "GODS", List.of("GODS"), 10, 24))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void propagatesTransportFailureSoAnalysisCanMarkDataUnavailable() {
        OkxAnnouncementNewsSourceAdapter adapter = new OkxAnnouncementNewsSourceAdapter(new OkxRestClient() {
            @Override
            public JsonNode publicGet(String requestPath) {
                throw new IllegalStateException("timeout");
            }
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.search(
                        MarketType.OKX_SWAP, "GODS-USDT-SWAP", "GODS", List.of("GODS"), 10, 24))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void cachesOnlySuccessfulFetchesWithoutRefreshingFailedFetchAge() throws Exception {
        FakeOkxRestClient success = new FakeOkxRestClient("{\"code\":\"0\",\"data\":[]}");
        OkxAnnouncementNewsSourceAdapter successfulAdapter = new OkxAnnouncementNewsSourceAdapter(success);
        successfulAdapter.search(MarketType.OKX_SWAP, "GODS-USDT-SWAP", "GODS", List.of("GODS"), 10, 24);
        successfulAdapter.search(MarketType.OKX_SWAP, "GODS-USDT-SWAP", "GODS", List.of("GODS"), 10, 24);
        assertThat(success.callCount()).isEqualTo(1);

        FakeOkxRestClient failure = new FakeOkxRestClient("{\"code\":\"50001\",\"data\":[]}");
        OkxAnnouncementNewsSourceAdapter failedAdapter = new OkxAnnouncementNewsSourceAdapter(failure);
        for (int attempt = 0; attempt < 2; attempt++) {
            org.assertj.core.api.Assertions.catchThrowable(() -> failedAdapter.search(
                    MarketType.OKX_SWAP, "GODS-USDT-SWAP", "GODS", List.of("GODS"), 10, 24));
        }
        assertThat(failure.callCount()).isEqualTo(2);
    }

    private static final class FakeOkxRestClient extends OkxRestClient {
        private final JsonNode response;
        private final java.util.ArrayList<String> paths = new java.util.ArrayList<>();

        private FakeOkxRestClient(String response) throws Exception {
            this.response = JSON.readTree(response);
        }

        @Override
        public JsonNode publicGet(String requestPath) {
            paths.add(requestPath);
            return response;
        }

        private List<String> paths() {
            return paths;
        }

        private int callCount() {
            return paths.size();
        }
    }
}

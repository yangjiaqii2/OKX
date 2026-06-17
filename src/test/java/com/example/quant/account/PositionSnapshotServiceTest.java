package com.example.quant.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.OkxRestClient;
import com.example.quant.account.dto.PositionSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PositionSnapshotServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsOkxPositionMarginFieldsFromLiveSnapshot() throws Exception {
        JsonNode root = objectMapper.readTree("""
                {
                  "code": "0",
                  "data": [
                    {
                      "instId": "BTC-USDT-SWAP",
                      "posSide": "long",
                      "pos": "2",
                      "avgPx": "65000",
                      "upl": "12.5",
                      "margin": "31.2",
                      "imr": "28.9",
                      "mgnMode": "isolated",
                      "lever": "5",
                      "liqPx": "59000",
                      "notionalUsd": "156",
                      "mgnRatio": "12.3"
                    }
                  ]
                }
                """);
        PositionSnapshotService service = new PositionSnapshotService(new FixedOkxRestClient(root));

        List<PositionSummary> positions = service.positions();

        assertThat(positions).hasSize(1);
        JsonNode serialized = objectMapper.valueToTree(positions.get(0));
        assertThat(serialized.path("margin").decimalValue()).isEqualByComparingTo("31.2");
        assertThat(serialized.path("initialMargin").decimalValue()).isEqualByComparingTo("28.9");
        assertThat(serialized.path("marginMode").asText()).isEqualTo("isolated");
        assertThat(serialized.path("leverage").decimalValue()).isEqualByComparingTo("5");
        assertThat(serialized.path("liquidationPrice").decimalValue()).isEqualByComparingTo("59000");
        assertThat(serialized.path("notionalUsd").decimalValue()).isEqualByComparingTo("156");
        assertThat(serialized.path("marginRatio").decimalValue()).isEqualByComparingTo("12.3");
    }

    @Test
    void fallsBackToInitialMarginWhenOkxMarginIsBlankForCrossPosition() throws Exception {
        JsonNode root = objectMapper.readTree("""
                {
                  "code": "0",
                  "data": [
                    {
                      "instId": "ETH-USDT-SWAP",
                      "posSide": "short",
                      "pos": "3",
                      "avgPx": "3500",
                      "upl": "-1.5",
                      "margin": "",
                      "imr": "18.4",
                      "mgnMode": "cross"
                    }
                  ]
                }
                """);
        PositionSnapshotService service = new PositionSnapshotService(new FixedOkxRestClient(root));

        JsonNode serialized = objectMapper.valueToTree(service.positions().get(0));

        assertThat(serialized.path("margin").decimalValue()).isEqualByComparingTo("18.4");
        assertThat(serialized.path("initialMargin").decimalValue()).isEqualByComparingTo("18.4");
        assertThat(serialized.path("marginMode").asText()).isEqualTo("cross");
    }

    private static class FixedOkxRestClient extends OkxRestClient {
        private final JsonNode response;

        FixedOkxRestClient(JsonNode response) {
            super(null, null, null, null);
            this.response = response;
        }

        @Override
        public JsonNode privateGet(String requestPath) {
            assertThat(requestPath).isEqualTo("/api/v5/account/positions?instType=SWAP");
            return response;
        }

        @Override
        public boolean hasCredentials() {
            return true;
        }
    }
}

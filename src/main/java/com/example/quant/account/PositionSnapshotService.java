package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PositionSnapshotService {
    private final OkxRestClient okxRestClient;

    public PositionSnapshotService(OkxRestClient okxRestClient) {
        this.okxRestClient = okxRestClient;
    }

    public List<PositionSummary> positions() {
        try {
            JsonNode data = okxRestClient.privateGet("/api/v5/account/positions?instType=SWAP").path("data");
            List<PositionSummary> positions = new ArrayList<>();
            for (JsonNode item : data) {
                positions.add(new PositionSummary(
                        item.path("instId").asText(),
                        item.path("posSide").asText(),
                        item.path("pos").asText(),
                        "OKX_REAL"
                ));
            }
            return positions;
        } catch (IllegalStateException ex) {
            if (okxRestClient.hasCredentials()) {
                throw ex;
            }
            return List.of();
        }
    }
}

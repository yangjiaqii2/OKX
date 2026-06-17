package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
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
                BigDecimal initialMargin = decimal(item, "imr");
                BigDecimal margin = effectiveMargin(decimal(item, "margin"), initialMargin);
                positions.add(new PositionSummary(
                        item.path("instId").asText(),
                        item.path("posSide").asText(),
                        item.path("pos").asText(),
                        decimal(item, "avgPx"),
                        decimal(item, "upl"),
                        margin,
                        initialMargin,
                        item.path("mgnMode").asText(""),
                        decimal(item, "lever"),
                        decimal(item, "liqPx"),
                        decimal(item, "notionalUsd"),
                        decimal(item, "mgnRatio"),
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

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = node.path(field).asText("0");
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal effectiveMargin(BigDecimal margin, BigDecimal initialMargin) {
        return margin.signum() > 0 ? margin : initialMargin;
    }
}

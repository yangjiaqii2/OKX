package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PositionSnapshotService {
    private final OkxRestClient okxRestClient;
    private ExchangePositionRepository ownership;
    private TradingProperties trading;

    public PositionSnapshotService(OkxRestClient okxRestClient) {
        this.okxRestClient = okxRestClient;
    }

    @Autowired(required = false)
    void configureOwnership(ExchangePositionRepository ownership) {
        this.ownership = ownership;
    }

    @Autowired(required = false)
    void configureTrading(TradingProperties trading) {
        this.trading = trading;
    }

    public List<PositionSummary> positions() {
        try {
            JsonNode data = okxRestClient.privateGet("/api/v5/account/positions?instType=SWAP").path("data");
            List<PositionSummary> positions = new ArrayList<>();
            for (JsonNode item : data) {
                BigDecimal initialMargin = nullableDecimal(item, "imr");
                BigDecimal margin = effectiveMargin(nullableDecimal(item, "margin"), initialMargin);
                String instId = item.path("instId").asText();
                String posSide = item.path("posSide").asText();
                OwnershipState state = ownership(instId, posSide);
                positions.add(new PositionSummary(
                        instId,
                        posSide,
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
                        trading != null && trading.mode() == TradingMode.DEMO ? "OKX_DEMO" : "OKX_REAL",
                        state.ownership(),
                        state.reason(),
                        decimal(item, "markPx"),
                        decimal(item, "last")
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

    private OwnershipState ownership(String instId, String posSide) {
        if (ownership == null) {
            return new OwnershipState("NOT_RECONCILED", "OWNERSHIP_UNKNOWN");
        }
        String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        return ownership.findByUserNameAndInstIdAndPosSide(owner, instId,
                        posSide == null || posSide.isBlank() ? "net" : posSide.trim().toLowerCase())
                .map(row -> new OwnershipState(row.getOwnership(), row.getReason()))
                .orElseGet(() -> new OwnershipState("NOT_RECONCILED", "OWNERSHIP_UNKNOWN"));
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        BigDecimal value = nullableDecimal(node, field);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal nullableDecimal(JsonNode node, String field) {
        String value = node.path(field).asText("");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal effectiveMargin(BigDecimal margin, BigDecimal initialMargin) {
        return margin != null && (margin.signum() > 0 || initialMargin == null) ? margin : initialMargin;
    }

    private record OwnershipState(String ownership, String reason) { }
}

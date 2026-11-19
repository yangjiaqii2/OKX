package com.example.quant.account;

import com.example.quant.crypto.OkxRestClient;
import com.example.quant.ledger.TradeFillEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Attribute a funding total only when the exchange position cycle matches the whole local round trip. */
@Service
public class ClosedPositionFunding {
    private final OkxRestClient client;

    public ClosedPositionFunding(OkxRestClient client) {
        this.client = client;
    }

    public Optional<BigDecimal> lookup(ClosePositionRecordEntity close, List<TradeFillEntity> fills,
                                       BigDecimal quantity, BigDecimal tradingNet) {
        if (fills.isEmpty() || fills.stream().anyMatch(fill -> fill.getFillTime() == null)) return Optional.empty();
        Instant first = fills.stream().map(TradeFillEntity::getFillTime).min(Instant::compareTo).orElseThrow();
        Instant last = fills.stream().map(TradeFillEntity::getFillTime).max(Instant::compareTo).orElseThrow();
        JsonNode rows = client.privateGet("/api/v5/account/positions-history?instType=SWAP&instId="
                + OkxRestClient.encode(close.getInstId()) + "&limit=100").path("data");
        BigDecimal funding = null;
        for (JsonNode row : rows) {
            String side = row.path("posSide").asText();
            String direction = "net".equals(side) ? row.path("direction").asText() : side;
            if (!close.getInstId().equals(row.path("instId").asText())
                    || !row.path("mgnMode").asText().equals(close.getMarginMode())
                    || !direction.equalsIgnoreCase(close.getPosSide())
                    || Math.abs(row.path("cTime").asLong() - first.toEpochMilli()) > 1000
                    || Math.abs(row.path("uTime").asLong() - last.toEpochMilli()) > 1000) continue;
            BigDecimal closed = new BigDecimal(row.path("closeTotalPos").asText());
            BigDecimal feesAndPnl = new BigDecimal(row.path("fee").asText())
                    .add(new BigDecimal(row.path("pnl").asText()));
            if (closed.compareTo(quantity) != 0 || feesAndPnl.compareTo(tradingNet) != 0) continue;
            BigDecimal value = new BigDecimal(row.path("fundingFee").asText());
            if (funding != null || tradingNet.add(value).compareTo(new BigDecimal(row.path("realizedPnl").asText())) != 0)
                return Optional.empty();
            funding = value;
        }
        return Optional.ofNullable(funding);
    }
}

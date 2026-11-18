package com.example.quant.agent.execution;

import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.OkxMarketService;
import com.example.quant.crypto.OkxRestClient;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractCandle;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Fetches a small, independent market snapshot after semantic selection. */
@Service
public class LiveMarketRefreshService {
    private final OkxRestClient okxRestClient;
    private final OkxMarketService okxMarketService;
    private final OrderBookLiquidityService orderBookLiquidityService;
    private final AgentProperties agentProperties;

    public LiveMarketRefreshService(OkxRestClient okxRestClient, OkxMarketService okxMarketService,
                                    OrderBookLiquidityService orderBookLiquidityService,
                                    AgentProperties agentProperties) {
        this.okxRestClient = okxRestClient;
        this.okxMarketService = okxMarketService;
        this.orderBookLiquidityService = orderBookLiquidityService;
        this.agentProperties = agentProperties;
    }

    public LiveMarketRefreshResult refresh(ContractCandidate candidate) {
        return refresh(candidate, Instant.now());
    }

    public LiveMarketRefreshResult refresh(ContractCandidate candidate, Instant jevDecisionTimestamp) {
        Instant refreshedAt = Instant.now();
        if (candidate == null || candidate.instId() == null || candidate.instId().isBlank()) {
            return LiveMarketRefreshResult.rejected(refreshedAt, "LIVE_REFRESH_CANDIDATE_INVALID");
        }
        try {
            JsonNode ticker = okxRestClient.publicGet("/api/v5/market/ticker?instId="
                    + OkxRestClient.encode(candidate.instId()), Duration.ofSeconds(4));
            JsonNode tickerData = ticker.path("data");
            if (!tickerData.isArray() || tickerData.isEmpty()) {
                return LiveMarketRefreshResult.rejected(refreshedAt, "LIVE_REFRESH_TICKER_EMPTY");
            }
            JsonNode item = tickerData.get(0);
            BigDecimal last = decimal(item, "last");
            BigDecimal mark = decimal(item, "markPx");
            BigDecimal livePrice = mark.signum() > 0 ? mark : last;
            if (livePrice.signum() <= 0) {
                return LiveMarketRefreshResult.rejected(refreshedAt, "LIVE_REFRESH_PRICE_INVALID");
            }

            OrderBookLiquiditySnapshot book = orderBookLiquidityService.snapshot(candidate.instId());
            if (book == null || !book.tradable()) {
                return LiveMarketRefreshResult.rejected(refreshedAt,
                        "LIVE_REFRESH_ORDERBOOK_UNAVAILABLE_" + (book == null ? "null" : book.denyReason()));
            }

            JsonNode fundingRoot = okxRestClient.publicGet("/api/v5/public/funding-rate?instId="
                    + OkxRestClient.encode(candidate.instId()), Duration.ofSeconds(4));
            JsonNode fundingData = fundingRoot.path("data");
            BigDecimal funding = fundingData.isArray() && !fundingData.isEmpty()
                    ? decimal(fundingData.get(0), "fundingRate") : null;
            if (funding == null) {
                return LiveMarketRefreshResult.rejected(refreshedAt, "LIVE_REFRESH_FUNDING_UNAVAILABLE");
            }

            List<ContractCandle> candles = okxMarketService.candles(candidate.instId(), "5m", 2,
                    Duration.ofSeconds(4));
            ContractCandle latest = candles == null || candles.isEmpty() ? null : candles.get(candles.size() - 1);
            Instant latestCandleTimestamp = latest == null ? null : latest.timestamp();
            if (latestCandleTimestamp == null) {
                return LiveMarketRefreshResult.rejected(refreshedAt, "LIVE_REFRESH_CANDLE_TIMESTAMP_UNAVAILABLE");
            }
            Instant originalCandleTimestamp = candidate.klineAnalysis() == null
                    ? null : candidate.klineAnalysis().decisionCandleTimestamp();
            if (originalCandleTimestamp == null) {
                return LiveMarketRefreshResult.rejected(refreshedAt, "LIVE_REFRESH_ORIGINAL_CANDLE_TIMESTAMP_UNAVAILABLE");
            }
            if (latestCandleTimestamp.isAfter(originalCandleTimestamp)) {
                return LiveMarketRefreshResult.rejected(refreshedAt,
                        "LIVE_REFRESH_CANDLE_CHANGED_REANALYZE_REQUIRED");
            }

            List<String> reasons = new ArrayList<>();
            BigDecimal deviationPct = livePrice.subtract(candidate.entryPrice()).abs()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(candidate.entryPrice(), 8, java.math.RoundingMode.HALF_UP);
            if (deviationPct.compareTo(agentProperties.preConfirmRefresh().maxPriceDeviationPct()) > 0) {
                reasons.add("LIVE_REFRESH_PRICE_DEVIATION_TOO_WIDE_" + deviationPct + "%");
            }
            if (book.spreadBps().compareTo(agentProperties.market().maxSpreadBps()) > 0) {
                reasons.add("LIVE_REFRESH_SPREAD_TOO_WIDE_" + book.spreadBps() + "bps");
            }
            if (funding.abs().compareTo(agentProperties.market().maxFundingAbs()) > 0) {
                reasons.add("LIVE_REFRESH_FUNDING_CROWDED_" + funding);
            }
            if (!reasons.isEmpty()) {
            return new LiveMarketRefreshResult(false, null, candidate.createdAt(), jevDecisionTimestamp,
                    refreshedAt, latestCandleTimestamp, reasons);
            }
            return new LiveMarketRefreshResult(true, candidate.withLiveMarketData(
                    livePrice, funding, null, book.spreadBps(), book.bidDepthUsdt(), book.askDepthUsdt(),
                    book.estimatedSlippageBps(), refreshedAt, latestCandleTimestamp, jevDecisionTimestamp),
                    candidate.createdAt(), jevDecisionTimestamp, refreshedAt,
                    latestCandleTimestamp, List.of("LIVE_REFRESH_COMPLETED"));
        } catch (RuntimeException ex) {
            return LiveMarketRefreshResult.rejected(refreshedAt,
                    "LIVE_REFRESH_FAILED_" + compact(ex.getMessage()));
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static String compact(String message) {
        if (message == null || message.isBlank()) return "unknown";
        return message.replaceAll("\\s+", " ").trim();
    }
}

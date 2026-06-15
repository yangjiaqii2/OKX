package com.example.quant.crypto;

import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractSignal;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OkxContractScanner {
    private final OkxRestClient okxRestClient;
    private final OkxMarketService okxMarketService;
    private final ContractSignalAnalyzer signalAnalyzer;

    public OkxContractScanner(OkxRestClient okxRestClient, OkxMarketService okxMarketService,
                              ContractSignalAnalyzer signalAnalyzer) {
        this.okxRestClient = okxRestClient;
        this.okxMarketService = okxMarketService;
        this.signalAnalyzer = signalAnalyzer;
    }

    public List<ContractCandidate> scan() {
        JsonNode root = okxRestClient.publicGet("/api/v5/market/tickers?instType=SWAP");
        List<TickerSnapshot> snapshots = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            String instId = item.path("instId").asText();
            if (!instId.endsWith("-USDT-SWAP")) {
                continue;
            }
            BigDecimal last = decimal(item, "last");
            BigDecimal open24h = decimal(item, "open24h");
            BigDecimal volCcy24h = decimal(item, "volCcy24h");
            BigDecimal change24h = BigDecimal.ZERO;
            if (open24h.signum() > 0) {
                change24h = last.subtract(open24h)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(open24h, 4, java.math.RoundingMode.HALF_UP);
            }
            snapshots.add(new TickerSnapshot(instId, baseCurrency(instId), last, open24h, volCcy24h, change24h));
        }
        return snapshots.stream()
                .sorted(Comparator.comparing(TickerSnapshot::volume24h, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(60)
                .map(this::analyzeSnapshot)
                .sorted(Comparator.comparing(ContractCandidate::score, Comparator.reverseOrder())
                        .thenComparing(ContractCandidate::volume24h, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(20)
                .toList();
    }

    private ContractCandidate analyzeSnapshot(TickerSnapshot snapshot) {
        BigDecimal fundingRate = safeFundingRate(snapshot.instId());
        BigDecimal openInterest = safeOpenInterest(snapshot.instId());
        ContractSignal signal = signalAnalyzer.analyze(
                snapshot.instId(),
                snapshot.lastPrice(),
                snapshot.open24h(),
                snapshot.volume24h(),
                fundingRate,
                openInterest,
                BigDecimal.ZERO,
                safeCandles(snapshot.instId())
        );
        return new ContractCandidate(
                    MarketType.OKX_SWAP,
                    snapshot.instId(),
                    snapshot.baseCurrency(),
                    "USDT",
                    snapshot.lastPrice(),
                    snapshot.change24h(),
                    signal.changePercent5m(),
                    snapshot.volume24h(),
                    signal.volumeSpikeRatio(),
                    signal.fundingRate(),
                    signal.openInterest(),
                    signal.openInterestChange(),
                    signal.directionBias(),
                    signal.volatility(),
                    signal.score(),
                    signal.entryPrice(),
                    signal.stopLossPrice(),
                    signal.takeProfitPrice(),
                    signal.suggestedLeverage(),
                    signal.riskRewardRatio(),
                    mergedReasons(snapshot.change24h(), snapshot.volume24h(), signal.directionBias(), signal.reasonList()),
                    signal.riskTagList(),
                    Instant.now()
        );
    }

    private List<com.example.quant.crypto.dto.ContractCandle> safeCandles(String instId) {
        try {
            return okxMarketService.candles(instId, "15m");
        } catch (Exception ex) {
            return List.of();
        }
    }

    private BigDecimal safeFundingRate(String instId) {
        try {
            JsonNode root = okxRestClient.publicGet("/api/v5/public/funding-rate?instId=" + OkxRestClient.encode(instId));
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                return decimal(data.get(0), "fundingRate");
            }
        } catch (Exception ignored) {
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal safeOpenInterest(String instId) {
        try {
            JsonNode root = okxRestClient.publicGet("/api/v5/public/open-interest?instType=SWAP&instId="
                    + OkxRestClient.encode(instId));
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                BigDecimal oiCcy = decimal(data.get(0), "oiCcy");
                return oiCcy.signum() > 0 ? oiCcy : decimal(data.get(0), "oi");
            }
        } catch (Exception ignored) {
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = node.path(field).asText("0");
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    private static String baseCurrency(String instId) {
        int index = instId.indexOf('-');
        return index > 0 ? instId.substring(0, index) : instId;
    }

    private static List<String> mergedReasons(BigDecimal change24h, BigDecimal volume24h, DirectionBias direction,
                                              List<String> signalReasons) {
        List<String> reasons = new ArrayList<>();
        reasons.add(direction == DirectionBias.BULLISH ? "24h价格强于开盘价" : "24h价格弱于开盘价");
        if (change24h.abs().compareTo(BigDecimal.valueOf(8)) >= 0) {
            reasons.add("24h波动幅度较大");
        } else if (change24h.abs().compareTo(BigDecimal.valueOf(2)) >= 0) {
            reasons.add("24h方向变化明显");
        }
        if (volume24h.compareTo(BigDecimal.valueOf(1_000_000_000L)) >= 0) {
            reasons.add("24h成交量进入流动性候选池");
        }
        reasons.addAll(signalReasons);
        return reasons;
    }

    private record TickerSnapshot(
            String instId,
            String baseCurrency,
            BigDecimal lastPrice,
            BigDecimal open24h,
            BigDecimal volume24h,
            BigDecimal change24h
    ) {
    }
}

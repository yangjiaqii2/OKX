package com.example.quant.crypto;

import com.example.quant.crypto.dto.ContractCandidate;
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

    public OkxContractScanner(OkxRestClient okxRestClient) {
        this.okxRestClient = okxRestClient;
    }

    public List<ContractCandidate> scan() {
        JsonNode root = okxRestClient.publicGet("/api/v5/market/tickers?instType=SWAP");
        List<ContractCandidate> candidates = new ArrayList<>();
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
            DirectionBias direction = change24h.signum() >= 0 ? DirectionBias.BULLISH : DirectionBias.BEARISH;
            candidates.add(new ContractCandidate(
                    MarketType.OKX_SWAP,
                    instId,
                    baseCurrency(instId),
                    "USDT",
                    last,
                    change24h,
                    BigDecimal.ZERO,
                    volCcy24h,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    direction,
                    BigDecimal.ZERO,
                    reasonList(change24h, volCcy24h, direction),
                    List.of(),
                    Instant.now()
            ));
        }
        return candidates.stream()
                .sorted(Comparator.comparing(ContractCandidate::volume24h, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(20)
                .toList();
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

    private static List<String> reasonList(BigDecimal change24h, BigDecimal volume24h, DirectionBias direction) {
        List<String> reasons = new ArrayList<>();
        reasons.add(direction == DirectionBias.BULLISH ? "24h价格强于开盘价" : "24h价格弱于开盘价");
        if (change24h.abs().compareTo(BigDecimal.valueOf(8)) >= 0) {
            reasons.add("24h波动幅度较大");
        } else if (change24h.abs().compareTo(BigDecimal.valueOf(2)) >= 0) {
            reasons.add("24h方向变化明显");
        }
        if (volume24h.compareTo(BigDecimal.valueOf(1_000_000_000L)) >= 0) {
            reasons.add("24h成交量排名靠前");
        }
        return reasons;
    }
}

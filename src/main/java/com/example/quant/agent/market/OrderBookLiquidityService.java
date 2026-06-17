package com.example.quant.agent.market;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class OrderBookLiquidityService {
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private final OkxRestClient okxRestClient;
    private final AgentProperties agentProperties;
    private final ConcurrentMap<String, ContractSpec> contractSpecs = new ConcurrentHashMap<>();

    public OrderBookLiquidityService(OkxRestClient okxRestClient, AgentProperties agentProperties) {
        this.okxRestClient = okxRestClient;
        this.agentProperties = agentProperties;
    }

    public OrderBookLiquiditySnapshot snapshot(String instId) {
        try {
            JsonNode root = okxRestClient.publicGet("/api/v5/market/books?instId="
                    + OkxRestClient.encode(instId)
                    + "&sz=50");
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return OrderBookLiquiditySnapshot.unavailable(instId, "OKX订单簿为空");
            }
            JsonNode book = data.get(0);
            JsonNode bids = book.path("bids");
            JsonNode asks = book.path("asks");
            if (!bids.isArray() || bids.isEmpty() || !asks.isArray() || asks.isEmpty()) {
                return OrderBookLiquiditySnapshot.unavailable(instId, "OKX订单簿缺少买卖盘");
            }

            BigDecimal bestBid = price(bids.get(0));
            BigDecimal bestAsk = price(asks.get(0));
            if (bestBid.signum() <= 0 || bestAsk.signum() <= 0 || bestAsk.compareTo(bestBid) <= 0) {
                return OrderBookLiquiditySnapshot.unavailable(instId, "OKX订单簿买卖一价格无效");
            }

            BigDecimal mid = bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), 12, RoundingMode.HALF_UP);
            BigDecimal spreadBps = bestAsk.subtract(bestBid)
                    .multiply(TEN_THOUSAND)
                    .divide(mid, 4, RoundingMode.HALF_UP);
            BigDecimal maxSpreadBps = agentProperties.market().maxSpreadBps();
            BigDecimal bidFloor = mid.multiply(BigDecimal.ONE.subtract(maxSpreadBps.divide(TEN_THOUSAND, 12, RoundingMode.HALF_UP)));
            BigDecimal askCeiling = mid.multiply(BigDecimal.ONE.add(maxSpreadBps.divide(TEN_THOUSAND, 12, RoundingMode.HALF_UP)));
            ContractSpec contractSpec = contractSpec(instId);
            BigDecimal bidDepth = depthWithin(bids, bidFloor, true, contractSpec);
            BigDecimal askDepth = depthWithin(asks, askCeiling, false, contractSpec);
            BigDecimal minDepth = agentProperties.market().minDepthUsdt();
            String denyReason = "";
            if (spreadBps.compareTo(maxSpreadBps) > 0) {
                denyReason = "spread_bps_above_" + maxSpreadBps.stripTrailingZeros().toPlainString();
            } else if (bidDepth.min(askDepth).compareTo(minDepth) < 0) {
                denyReason = "depth_usdt_below_" + minDepth.stripTrailingZeros().toPlainString();
            }
            boolean tradable = denyReason.isBlank();
            BigDecimal estimatedSlippage = tradable
                    ? spreadBps.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP)
                    : maxSpreadBps.add(BigDecimal.ONE);
            return new OrderBookLiquiditySnapshot(
                    instId,
                    bestBid,
                    bestAsk,
                    mid,
                    spreadBps,
                    bidDepth,
                    askDepth,
                    estimatedSlippage,
                    tradable,
                    denyReason,
                    Instant.now()
            );
        } catch (RuntimeException ex) {
            return OrderBookLiquiditySnapshot.unavailable(instId, "OKX订单簿获取失败：" + ex.getMessage());
        }
    }

    private ContractSpec contractSpec(String instId) {
        return contractSpecs.computeIfAbsent(instId, this::fetchContractSpec);
    }

    private ContractSpec fetchContractSpec(String instId) {
        try {
            JsonNode root = okxRestClient.publicGet("/api/v5/public/instruments?instType=SWAP&instId="
                    + OkxRestClient.encode(instId));
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                JsonNode instrument = data.get(0);
                BigDecimal ctVal = decimal(instrument, "ctVal");
                if (ctVal.signum() > 0) {
                    String ctValCcy = instrument.path("ctValCcy").asText(baseCurrency(instId));
                    return new ContractSpec(ctVal, ctValCcy == null || ctValCcy.isBlank()
                            ? baseCurrency(instId)
                            : ctValCcy);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return ContractSpec.defaultFor(instId);
    }

    private static BigDecimal depthWithin(JsonNode levels, BigDecimal boundaryPrice, boolean bidSide,
                                          ContractSpec contractSpec) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode level : levels) {
            BigDecimal price = price(level);
            if (price.signum() <= 0) {
                continue;
            }
            boolean included = bidSide ? price.compareTo(boundaryPrice) >= 0 : price.compareTo(boundaryPrice) <= 0;
            if (!included) {
                continue;
            }
            sum = sum.add(contractSpec.notionalUsdt(price, size(level)));
        }
        return sum.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal price(JsonNode level) {
        return decimal(level, 0);
    }

    private static BigDecimal size(JsonNode level) {
        return decimal(level, 1);
    }

    private static BigDecimal decimal(JsonNode node, int index) {
        if (!node.isArray() || node.size() <= index) {
            return BigDecimal.ZERO;
        }
        String value = node.get(index).asText("0");
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
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

    private record ContractSpec(BigDecimal ctVal, String ctValCcy) {
        static ContractSpec defaultFor(String instId) {
            return new ContractSpec(BigDecimal.ONE, baseCurrency(instId));
        }

        BigDecimal notionalUsdt(BigDecimal price, BigDecimal size) {
            BigDecimal contractValue = size.multiply(ctVal);
            if (valueAlreadyInQuoteCurrency()) {
                return contractValue;
            }
            return price.multiply(contractValue);
        }

        private boolean valueAlreadyInQuoteCurrency() {
            String normalized = ctValCcy == null ? "" : ctValCcy.toUpperCase(Locale.ROOT);
            return "USDT".equals(normalized) || "USD".equals(normalized);
        }
    }
}

package com.example.quant.agent.market;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.OkxRestClient;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxInstrumentRulesProvider;
import com.example.quant.okxtrade.OkxInstrumentRulesService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderBookLiquidityService {
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private final OkxRestClient okxRestClient;
    private final AgentProperties agentProperties;
    private final OkxInstrumentRulesProvider instrumentRulesProvider;

    public OrderBookLiquidityService(OkxRestClient okxRestClient, AgentProperties agentProperties) {
        this(okxRestClient, agentProperties, new OkxInstrumentRulesService(okxRestClient));
    }

    @Autowired
    public OrderBookLiquidityService(OkxRestClient okxRestClient, AgentProperties agentProperties,
                                     OkxInstrumentRulesProvider instrumentRulesProvider) {
        this.okxRestClient = okxRestClient;
        this.agentProperties = agentProperties;
        this.instrumentRulesProvider = instrumentRulesProvider;
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
            OkxInstrumentRules rules = instrumentRulesProvider.rules(instId);
            BigDecimal bidDepth = depthWithin(bids, bidFloor, true, rules);
            BigDecimal askDepth = depthWithin(asks, askCeiling, false, rules);
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
            return OrderBookLiquiditySnapshot.unavailable(instId, "OKX订单簿或合约规则获取失败：" + ex.getMessage());
        }
    }

    private static BigDecimal depthWithin(JsonNode levels, BigDecimal boundaryPrice, boolean bidSide,
                                          OkxInstrumentRules rules) {
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
            BigDecimal contracts = size(level);
            BigDecimal baseQuantity = contracts.multiply(rules.contractValue());
            boolean quoteValue = rules.contractValueCurrency().equalsIgnoreCase("USDT")
                    || rules.contractValueCurrency().equalsIgnoreCase("USD")
                    || rules.contractValueCurrency().equalsIgnoreCase("USDC");
            sum = sum.add(quoteValue ? baseQuantity : price.multiply(baseQuantity));
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

}

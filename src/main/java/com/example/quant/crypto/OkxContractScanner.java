package com.example.quant.crypto;

import com.example.quant.agent.market.MarketEnvironment;
import com.example.quant.agent.market.MarketEnvironmentService;
import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineSet;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignal;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final ContractNewsAnalysisService contractNewsAnalysisService;
    private final OrderBookLiquidityService orderBookLiquidityService;
    private final MarketEnvironmentService marketEnvironmentService;
    private final AgentProperties agentProperties;

    public OkxContractScanner(OkxRestClient okxRestClient, OkxMarketService okxMarketService,
                              ContractSignalAnalyzer signalAnalyzer,
                              ContractNewsAnalysisService contractNewsAnalysisService,
                              OrderBookLiquidityService orderBookLiquidityService,
                              MarketEnvironmentService marketEnvironmentService,
                              AgentProperties agentProperties) {
        this.okxRestClient = okxRestClient;
        this.okxMarketService = okxMarketService;
        this.signalAnalyzer = signalAnalyzer;
        this.contractNewsAnalysisService = contractNewsAnalysisService;
        this.orderBookLiquidityService = orderBookLiquidityService;
        this.marketEnvironmentService = marketEnvironmentService;
        this.agentProperties = agentProperties;
    }

    public List<ContractCandidate> scan() {
        MarketEnvironment environment = marketEnvironmentService.current();
        JsonNode root = okxRestClient.publicGet("/api/v5/market/tickers?instType=SWAP");
        List<TickerSnapshot> snapshots = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            String instId = item.path("instId").asText();
            if (!instId.endsWith("-USDT-SWAP")) {
                continue;
            }
            BigDecimal last = decimal(item, "last");
            BigDecimal open24h = decimal(item, "open24h");
            BigDecimal high24h = decimal(item, "high24h");
            BigDecimal low24h = decimal(item, "low24h");
            BigDecimal volCcy24h = decimal(item, "volCcy24h");
            BigDecimal vol24h = decimal(item, "vol24h");
            BigDecimal bidPx = decimal(item, "bidPx");
            BigDecimal askPx = decimal(item, "askPx");
            BigDecimal change24h = BigDecimal.ZERO;
            if (open24h.signum() > 0) {
                change24h = last.subtract(open24h)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(open24h, 4, java.math.RoundingMode.HALF_UP);
            }
            BigDecimal turnover24h = volCcy24h.signum() > 0 ? volCcy24h : vol24h.multiply(last);
            BigDecimal range24h = last.signum() > 0 && high24h.signum() > 0 && low24h.signum() > 0
                    ? high24h.subtract(low24h).multiply(BigDecimal.valueOf(100)).divide(last, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal spreadPct = last.signum() > 0 && bidPx.signum() > 0 && askPx.signum() > 0
                    ? askPx.subtract(bidPx).multiply(BigDecimal.valueOf(100)).divide(last, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            snapshots.add(new TickerSnapshot(instId, baseCurrency(instId), last, open24h, turnover24h,
                    change24h, range24h, spreadPct));
        }
        return snapshots.stream()
                .filter(snapshot -> snapshot.volume24h().compareTo(agentProperties.market().minVolume24hUsdt()) >= 0)
                .filter(snapshot -> snapshot.range24hPct().signum() == 0
                        || snapshot.range24hPct().compareTo(agentProperties.market().max24hRangePct()) <= 0)
                .sorted(Comparator.comparing(TickerSnapshot::volume24h, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(30)
                .map(snapshot -> analyzeSnapshot(snapshot, environment))
                .sorted(Comparator.comparing((ContractCandidate candidate) -> firstPlaceEligible(candidate) ? 1 : 0)
                        .reversed()
                        .thenComparing(ContractCandidate::finalRankScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ContractCandidate::score, Comparator.reverseOrder())
                        .thenComparing(ContractCandidate::volume24h, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(agentProperties.topCandidateLimit())
                .toList();
    }

    private ContractCandidate analyzeSnapshot(TickerSnapshot snapshot, MarketEnvironment environment) {
        BigDecimal fundingRate = safeFundingRate(snapshot.instId());
        BigDecimal openInterest = safeOpenInterest(snapshot.instId());
        OrderBookLiquiditySnapshot liquidity = orderBookLiquidityService.snapshot(snapshot.instId());
        ContractNewsRiskAnalysis newsRisk = contractNewsAnalysisService.analyze(snapshot.instId());
        ContractSignal signal = signalAnalyzer.analyze(
                snapshot.instId(),
                snapshot.lastPrice(),
                snapshot.open24h(),
                snapshot.volume24h(),
                fundingRate,
                openInterest,
                BigDecimal.ZERO,
                safeKlines(snapshot.instId())
        );
        ContractScoreBreakdown scoreBreakdown = adjustedScoreBreakdown(signal.scoreBreakdown(), liquidity, environment,
                signal.directionBias(), newsRisk, snapshot.volume24h());
        ContractFactorScore factorScore = scoreBreakdown.weightedFactorScore();
        int adjustedScore = Math.max(0, Math.min(100, scoreBreakdown.weightedTotal()));
        BigDecimal finalRankScore = finalRankScore(adjustedScore, scoreBreakdown, signal, liquidity);
        String action = adjustedAction(signal, newsRisk, liquidity, finalRankScore);
        int leverage = "AUTO_TRADE_ALLOWED".equals(action) || "WAIT_CONFIRM".equals(action)
                ? signal.suggestedLeverage()
                : 0;
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
                    adjustedScore,
                    factorScore,
                    signal.entryPrice(),
                    signal.stopLossPrice(),
                    signal.takeProfitPrice(),
                    leverage,
                    signal.riskRewardRatio(),
                    liquidity.spreadBps(),
                    liquidity.bidDepthUsdt(),
                    liquidity.askDepthUsdt(),
                    liquidity.estimatedSlippageBps(),
                    environment.btcTrend(),
                    environment.ethTrend(),
                    environment.riskLevel(),
                    mergedReasons(snapshot.change24h(), snapshot.volume24h(), signal.directionBias(),
                            signal.reasonList(), liquidity, environment, signal.signalType(), newsRisk),
                    mergedRisks(signal.riskTagList(), liquidity, environment, signal.directionBias(), newsRisk, action),
                    Instant.now(),
                    signal.signalType(),
                    action,
                    signal.entryType(),
                    signal.todayChangePct(),
                    signal.atrPct20m(),
                    signal.stopLossPct(),
                    finalRankScore,
                    scoreBreakdown,
                    signal.klineAnalysis(),
                    newsRisk
        );
    }

    private ContractScoreBreakdown adjustedScoreBreakdown(
            ContractScoreBreakdown score,
            OrderBookLiquiditySnapshot liquidity,
            MarketEnvironment environment,
            DirectionBias direction,
            ContractNewsRiskAnalysis newsRisk,
            BigDecimal turnover24h
    ) {
        int liquidityScore = liquidityScore(liquidity, turnover24h);
        int marketScore = Math.max(0, Math.min(100, environment.scoreFor(direction) * 10));
        return new ContractScoreBreakdown(
                score.trend(),
                score.volume(),
                liquidityScore,
                score.volatility(),
                score.oiFunding(),
                marketScore,
                newsRisk.newsRiskScore()
        );
    }

    private int liquidityScore(OrderBookLiquiditySnapshot liquidity, BigDecimal turnover24h) {
        int volumeScore;
        if (turnover24h.compareTo(BigDecimal.valueOf(1_500_000_000L)) >= 0) {
            volumeScore = 96;
        } else if (turnover24h.compareTo(BigDecimal.valueOf(500_000_000L)) >= 0) {
            volumeScore = 88;
        } else if (turnover24h.compareTo(BigDecimal.valueOf(100_000_000L)) >= 0) {
            volumeScore = 75;
        } else if (turnover24h.compareTo(agentProperties.market().minVolume24hUsdt()) >= 0) {
            volumeScore = 55;
        } else {
            volumeScore = 20;
        }
        int spreadScore;
        if (!liquidity.tradable()) {
            spreadScore = 20;
        } else if (liquidity.spreadBps().compareTo(BigDecimal.valueOf(4)) <= 0
                && liquidity.availableDepthUsdt().compareTo(BigDecimal.valueOf(500_000)) >= 0) {
            spreadScore = 95;
        } else if (liquidity.spreadBps().compareTo(BigDecimal.valueOf(6)) <= 0
                && liquidity.availableDepthUsdt().compareTo(BigDecimal.valueOf(200_000)) >= 0) {
            spreadScore = 82;
        } else {
            spreadScore = 65;
        }
        int depthScore = liquidity.availableDepthUsdt().compareTo(BigDecimal.valueOf(500_000)) >= 0 ? 95
                : liquidity.availableDepthUsdt().compareTo(agentProperties.market().minDepthUsdt()) >= 0 ? 80 : 25;
        return Math.max(0, Math.min(100, (int) Math.round(volumeScore * 0.40 + spreadScore * 0.30 + depthScore * 0.30)));
    }

    private ContractKlineSet safeKlines(String instId) {
        List<com.example.quant.crypto.dto.ContractCandle> fiveMinute = safeCandles(instId, "5m", 260);
        List<com.example.quant.crypto.dto.ContractCandle> twentyMinute = aggregate20m(fiveMinute);
        List<com.example.quant.crypto.dto.ContractCandle> oneHour = safeCandles(instId, "1H", 72);
        List<com.example.quant.crypto.dto.ContractCandle> fourHour = safeCandles(instId, "4H", 60);
        List<com.example.quant.crypto.dto.ContractCandle> oneDay = safeCandles(instId, "1D", 30);
        return new ContractKlineSet(twentyMinute, fiveMinute, oneHour, fourHour, oneDay);
    }

    private List<com.example.quant.crypto.dto.ContractCandle> safeCandles(String instId, String bar, int limit) {
        try {
            return okxMarketService.candles(instId, bar, limit);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static List<com.example.quant.crypto.dto.ContractCandle> aggregate20m(
            List<com.example.quant.crypto.dto.ContractCandle> fiveMinute
    ) {
        if (fiveMinute == null || fiveMinute.size() < 4) {
            return List.of();
        }
        int start = fiveMinute.size() % 4;
        List<com.example.quant.crypto.dto.ContractCandle> result = new ArrayList<>();
        for (int i = start; i + 3 < fiveMinute.size(); i += 4) {
            List<com.example.quant.crypto.dto.ContractCandle> group = fiveMinute.subList(i, i + 4);
            BigDecimal high = group.stream().map(com.example.quant.crypto.dto.ContractCandle::high)
                    .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            BigDecimal low = group.stream().map(com.example.quant.crypto.dto.ContractCandle::low)
                    .min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            BigDecimal volume = group.stream().map(com.example.quant.crypto.dto.ContractCandle::volume)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(new com.example.quant.crypto.dto.ContractCandle(
                    group.get(0).timestamp(),
                    group.get(0).open(),
                    high,
                    low,
                    group.get(3).close(),
                    volume
            ));
        }
        int from = Math.max(0, result.size() - 60);
        return result.subList(from, result.size());
    }

    private BigDecimal finalRankScore(int adjustedScore, ContractScoreBreakdown scoreBreakdown, ContractSignal signal,
                                      OrderBookLiquiditySnapshot liquidity) {
        BigDecimal rrScore = signal.riskRewardRatio().compareTo(BigDecimal.valueOf(2)) >= 0 ? BigDecimal.valueOf(100)
                : signal.riskRewardRatio().multiply(BigDecimal.valueOf(50)).min(BigDecimal.valueOf(100));
        BigDecimal finalScore = BigDecimal.valueOf(adjustedScore).multiply(new BigDecimal("0.70"))
                .add(rrScore.multiply(new BigDecimal("0.10")))
                .add(BigDecimal.valueOf(scoreBreakdown.liquidity()).multiply(new BigDecimal("0.08")))
                .add(BigDecimal.valueOf(scoreBreakdown.volume()).multiply(new BigDecimal("0.07")))
                .add(BigDecimal.valueOf(scoreBreakdown.market()).multiply(new BigDecimal("0.05")));
        if (!liquidity.tradable() || !isTradableSignal(signal.signalType())) {
            finalScore = finalScore.min(BigDecimal.valueOf(60));
        }
        if (signal.signalType() == ContractSignalType.WAIT_OVERHEATED
                || signal.signalType() == ContractSignalType.WAIT_OVERSOLD) {
            finalScore = finalScore.min(BigDecimal.valueOf(55));
        }
        return finalScore.setScale(2, RoundingMode.HALF_UP);
    }

    private String adjustedAction(ContractSignal signal, ContractNewsRiskAnalysis newsRisk,
                                  OrderBookLiquiditySnapshot liquidity, BigDecimal finalRankScore) {
        if (!liquidity.tradable() || "CRITICAL".equals(newsRisk.newsRiskLevel()) || "HIGH".equals(newsRisk.newsRiskLevel())) {
            return "NO_TRADE";
        }
        if ("UNKNOWN".equals(newsRisk.newsRiskLevel()) || "MEDIUM".equals(newsRisk.newsRiskLevel())) {
            return "WAIT_CONFIRM";
        }
        if (finalRankScore.compareTo(BigDecimal.valueOf(agentProperties.ranking().minFinalRankScore())) < 0) {
            return "WAIT";
        }
        return signal.action();
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
                                              List<String> signalReasons,
                                              OrderBookLiquiditySnapshot liquidity,
                                              MarketEnvironment environment,
                                              ContractSignalType signalType,
                                              ContractNewsRiskAnalysis newsRisk) {
        List<String> reasons = new ArrayList<>();
        reasons.add("信号类型：" + signalType);
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
        if (liquidity.tradable()) {
            reasons.add("订单簿价差与深度满足流动性门控");
        }
        if (!environment.conflictsWith(direction)) {
            reasons.add("BTC/ETH市场环境未与候选方向明显冲突");
        }
        reasons.add("新闻风险等级：" + newsRisk.newsRiskLevel() + "，安全分=" + newsRisk.newsRiskScore());
        return reasons;
    }

    private static List<String> mergedRisks(List<String> signalRisks,
                                            OrderBookLiquiditySnapshot liquidity,
                                            MarketEnvironment environment,
                                            DirectionBias direction,
                                            ContractNewsRiskAnalysis newsRisk,
                                            String action) {
        List<String> risks = new ArrayList<>(signalRisks);
        if (!liquidity.tradable()) {
            risks.add("订单簿流动性不足：" + liquidity.denyReason());
        }
        if (environment.conflictsWith(direction)) {
            risks.add("BTC/ETH市场环境与候选方向冲突");
        } else if (environment.partiallyConflictsWith(direction)) {
            risks.add("BTC/ETH市场环境存在单边冲突");
        }
        if (environment.riskLevel().ordinal() >= com.example.quant.risk.RiskLevel.HIGH.ordinal()) {
            risks.add("BTC/ETH短周期市场风险偏高");
        }
        if (!newsRisk.decision().allowTrade()) {
            risks.add("新闻风险限制：" + newsRisk.decision().reason());
        }
        if (!"AUTO_TRADE_ALLOWED".equals(action)) {
            risks.add("候选动作不是AUTO_TRADE_ALLOWED：" + action);
        }
        return risks;
    }

    private boolean firstPlaceEligible(ContractCandidate candidate) {
        return isTradableSignal(candidate.signalType())
                && "AUTO_TRADE_ALLOWED".equals(candidate.action())
                && candidate.scoreBreakdown().liquidity() >= 70
                && candidate.scoreBreakdown().volume() >= 60
                && candidate.riskRewardRatio().compareTo(agentProperties.ranking().minRiskReward()) >= 0
                && candidate.stopLossPct().compareTo(BigDecimal.valueOf(5)) <= 0
                && candidate.fundingRate().abs().compareTo(agentProperties.market().maxFundingAbs().multiply(BigDecimal.valueOf(2))) <= 0;
    }

    private static boolean isTradableSignal(ContractSignalType signalType) {
        return signalType == ContractSignalType.STRONG_LONG
                || signalType == ContractSignalType.PULLBACK_LONG
                || signalType == ContractSignalType.TREND_SHORT
                || signalType == ContractSignalType.REVERSAL_SHORT;
    }

    private record TickerSnapshot(
            String instId,
            String baseCurrency,
            BigDecimal lastPrice,
            BigDecimal open24h,
            BigDecimal volume24h,
            BigDecimal change24h,
            BigDecimal range24hPct,
            BigDecimal spreadPct
    ) {
    }
}

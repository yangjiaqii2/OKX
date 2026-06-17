package com.example.quant.crypto;

import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractKlineSet;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignal;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.market.DirectionBias;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ContractSignalAnalyzer {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal FUNDING_CROWDED = new BigDecimal("0.0015");
    private static final BigDecimal FUNDING_EXTREME = new BigDecimal("0.0030");
    private static final BigDecimal MIN_STOP_DISTANCE_RATE = new BigDecimal("0.006");
    private static final BigDecimal MAX_STOP_LOSS_PCT = new BigDecimal("5");
    private static final BigDecimal MIN_RISK_REWARD = new BigDecimal("1.50");
    private static final int MIN_20M_CANDLES = 50;

    public ContractSignal analyze(
            String instId,
            BigDecimal lastPrice,
            BigDecimal open24h,
            BigDecimal volume24h,
            BigDecimal fundingRate,
            BigDecimal openInterest,
            BigDecimal previousOpenInterest,
            List<ContractCandle> candles
    ) {
        return analyze(instId, lastPrice, open24h, volume24h, fundingRate, openInterest, previousOpenInterest,
                ContractKlineSet.fromSingleInterval(candles));
    }

    public ContractSignal analyze(
            String instId,
            BigDecimal lastPrice,
            BigDecimal open24h,
            BigDecimal volume24h,
            BigDecimal fundingRate,
            BigDecimal openInterest,
            BigDecimal previousOpenInterest,
            ContractKlineSet klineSet
    ) {
        ContractKlineSet safeSet = klineSet == null ? ContractKlineSet.fromSingleInterval(List.of()) : klineSet;
        List<ContractCandle> twentyMinute = clean(safeSet.twentyMinute());
        List<ContractCandle> fiveMinute = clean(safeSet.fiveMinute());
        BigDecimal entry = positive(lastPrice) ? lastPrice : lastClose(twentyMinute);
        BigDecimal todayOpen = defaultPositive(open24h, entry);
        BigDecimal todayChangePct = percent(entry.subtract(todayOpen), todayOpen);
        BigDecimal oiChange = percent(openInterest.subtract(previousOpenInterest), previousOpenInterest);
        BigDecimal change5m = changeOverBars(fiveMinute.isEmpty() ? twentyMinute : fiveMinute, 1);
        ContractNewsRiskAnalysis newsRisk = ContractNewsRiskAnalysis.low();

        if (twentyMinute.size() < MIN_20M_CANDLES) {
            return noTradeSignal(entry, todayChangePct, change5m, fundingRate, openInterest, oiChange,
                    "20m K线数量不足，不能进入最终交易计划");
        }

        IndicatorSnapshot main = indicators(twentyMinute);
        IndicatorSnapshot fast = indicators(fiveMinute.isEmpty() ? twentyMinute : fiveMinute);
        IndicatorSnapshot oneHour = indicators(clean(safeSet.oneHour()));
        IndicatorSnapshot fourHour = indicators(clean(safeSet.fourHour()));
        StructureSnapshot structure = structure(twentyMinute);
        String trend20m = trendDirection(main, structure);
        String trend1h = trendDirection(oneHour, structure(clean(safeSet.oneHour())));
        String trend4h = trendDirection(fourHour, structure(clean(safeSet.fourHour())));
        BigDecimal distanceFromEma20Pct = positive(main.ema20())
                ? entry.subtract(main.ema20()).abs().multiply(HUNDRED).divide(entry, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        ContractSignalType signalType = signalType(
                todayChangePct,
                fundingRate,
                trend20m,
                trend1h,
                trend4h,
                main,
                fast,
                structure,
                distanceFromEma20Pct
        );
        DirectionBias direction = directionFor(signalType, trend20m, entry, main);
        String entryTiming5m = entryTiming(signalType, fast, direction, distanceFromEma20Pct);
        ContractKlineAnalysis klineAnalysis = new ContractKlineAnalysis(
                trend20m,
                trend1h,
                trend4h,
                emaState(main),
                macdState(main),
                scale(main.rsi()),
                scale(main.adx()),
                structure.name(),
                entryTiming5m,
                scale(main.volumeRatio()),
                scale(main.atrPct()),
                scale(distanceFromEma20Pct),
                structure.clearFor(direction)
        );

        ContractScoreBreakdown scores = scoreBreakdown(
                signalType,
                direction,
                todayChangePct,
                volume24h,
                fundingRate,
                openInterest,
                oiChange,
                main,
                fast,
                structure,
                trend1h,
                trend4h,
                newsRisk
        );
        ContractFactorScore factorScore = scores.weightedFactorScore();
        int score = scores.weightedTotal();

        PlanPrices prices = planPrices(entry, direction, main, structure, signalType, score);
        BigDecimal riskReward = safeRiskReward(direction, prices.entry(), prices.stopLoss(), prices.takeProfit());
        BigDecimal stopLossPct = prices.entry().subtract(prices.stopLoss()).abs()
                .multiply(HUNDRED)
                .divide(prices.entry(), 4, RoundingMode.HALF_UP);

        List<String> reasons = reasons(signalType, todayChangePct, main, klineAnalysis, scores, newsRisk);
        List<String> risks = risks(signalType, todayChangePct, fundingRate, main, fast, klineAnalysis, newsRisk);
        if (stopLossPct.compareTo(MAX_STOP_LOSS_PCT) > 0) {
            risks.add("止损距离超过5%，不进入自动交易");
        }
        if (riskReward.compareTo(MIN_RISK_REWARD) < 0) {
            risks.add("盈亏比不足1.5");
        }

        if (isTradableSignal(signalType)
                && (stopLossPct.compareTo(MAX_STOP_LOSS_PCT) > 0 || riskReward.compareTo(MIN_RISK_REWARD) < 0)) {
            signalType = ContractSignalType.NO_TRADE;
            direction = DirectionBias.NEUTRAL;
            score = Math.min(score, 55);
            reasons = List.of("20m结构存在方向，但止损或盈亏比不满足自动交易要求");
        }

        String action = action(signalType, score, entryTiming5m, newsRisk);
        int leverage = leverage(signalType, action, score, todayChangePct, main.atrPct(), fundingRate, newsRisk);
        String entryType = entryType(signalType, entryTiming5m, action);

        return new ContractSignal(
                score,
                direction,
                scale(prices.entry()),
                scale(prices.stopLoss().max(BigDecimal.ZERO)),
                scale(prices.takeProfit().max(BigDecimal.ZERO)),
                leverage,
                riskReward,
                scale(change5m),
                scale(main.volumeRatio()),
                fundingRate,
                openInterest,
                oiChange,
                scale(main.atrPct().divide(HUNDRED, 8, RoundingMode.HALF_UP)),
                factorScore,
                List.copyOf(reasons),
                List.copyOf(risks),
                signalType,
                action,
                entryType,
                scale(todayChangePct),
                scale(todayChangePct),
                scale(main.atrPct()),
                scale(stopLossPct),
                BigDecimal.valueOf(score),
                scores,
                klineAnalysis,
                newsRisk
        );
    }

    private static ContractSignal noTradeSignal(BigDecimal entry, BigDecimal todayChangePct, BigDecimal change5m,
                                                BigDecimal fundingRate, BigDecimal openInterest,
                                                BigDecimal oiChange, String reason) {
        BigDecimal safeEntry = positive(entry) ? entry : BigDecimal.ONE;
        BigDecimal stopLoss = safeEntry.multiply(new BigDecimal("0.99"));
        BigDecimal takeProfit = safeEntry.multiply(new BigDecimal("1.02"));
        ContractScoreBreakdown scores = new ContractScoreBreakdown(0, 30, 50, 30, 50, 50, 60);
        return new ContractSignal(
                20,
                DirectionBias.NEUTRAL,
                scale(safeEntry),
                scale(stopLoss),
                scale(takeProfit),
                0,
                BigDecimal.ZERO,
                scale(change5m),
                BigDecimal.ZERO,
                fundingRate,
                openInterest,
                oiChange,
                BigDecimal.ZERO,
                scores.weightedFactorScore(),
                List.of("数据不完整，跳过最终计划"),
                List.of(reason),
                ContractSignalType.NO_TRADE,
                "NO_TRADE",
                "NO_ENTRY",
                scale(todayChangePct),
                scale(todayChangePct),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.valueOf(20),
                scores,
                ContractKlineAnalysis.unavailable(reason),
                ContractNewsRiskAnalysis.low()
        );
    }

    private static ContractSignalType signalType(BigDecimal todayChangePct, BigDecimal fundingRate,
                                                 String trend20m, String trend1h, String trend4h,
                                                 IndicatorSnapshot main, IndicatorSnapshot fast,
                                                 StructureSnapshot structure,
                                                 BigDecimal distanceFromEma20Pct) {
        if (todayChangePct.compareTo(new BigDecimal("15")) > 0
                && (main.rsi().compareTo(BigDecimal.valueOf(75)) > 0
                || distanceFromEma20Pct.compareTo(new BigDecimal("3")) > 0
                || main.consecutiveUpperWicks() >= 2
                || fundingRate.compareTo(new BigDecimal("0.0008")) > 0)) {
            return ContractSignalType.WAIT_OVERHEATED;
        }
        if (todayChangePct.compareTo(new BigDecimal("-15")) < 0
                && (main.rsi().compareTo(BigDecimal.valueOf(25)) < 0
                || distanceFromEma20Pct.compareTo(new BigDecimal("3")) > 0
                || main.consecutiveLowerWicks() >= 2
                || fundingRate.compareTo(new BigDecimal("-0.0008")) < 0)) {
            return ContractSignalType.WAIT_OVERSOLD;
        }
        if (todayChangePct.compareTo(new BigDecimal("12")) >= 0
                && (main.upperWickPct().compareTo(new BigDecimal("0.80")) >= 0 || main.consecutiveUpperWicks() >= 2)
                && (main.macdHistogram().compareTo(main.previousMacdHistogram()) < 0
                || "DOWN".equals(trend20m)
                || "DOWN".equals(trendDirection(fast, structure)))) {
            return ContractSignalType.REVERSAL_SHORT;
        }
        if (todayChangePct.compareTo(new BigDecimal("-3")) <= 0
                && todayChangePct.compareTo(new BigDecimal("-10")) >= 0
                && "DOWN".equals(trend20m)
                && structure.lowerStructure()
                && main.volumeRatio().compareTo(new BigDecimal("1.2")) >= 0
                && main.rsi().compareTo(BigDecimal.valueOf(20)) >= 0
                && main.rsi().compareTo(BigDecimal.valueOf(55)) <= 0) {
            return ContractSignalType.TREND_SHORT;
        }
        if (todayChangePct.compareTo(new BigDecimal("2")) >= 0
                && todayChangePct.compareTo(new BigDecimal("10")) <= 0
                && "UP".equals(trend20m)
                && structure.higherStructure()
                && main.volumeRatio().compareTo(new BigDecimal("1.2")) >= 0
                && main.rsi().compareTo(BigDecimal.valueOf(45)) >= 0
                && main.rsi().compareTo(BigDecimal.valueOf(82)) <= 0) {
            return ContractSignalType.STRONG_LONG;
        }
        if (todayChangePct.compareTo(new BigDecimal("5")) >= 0
                && todayChangePct.compareTo(new BigDecimal("15")) <= 0
                && "UP".equals(trend20m)
                && structure.higherStructure()
                && !"DOWN".equals(trend1h)
                && !"DOWN".equals(trend4h)) {
            return ContractSignalType.PULLBACK_LONG;
        }
        if (todayChangePct.compareTo(new BigDecimal("-2")) >= 0
                && todayChangePct.compareTo(new BigDecimal("2")) <= 0
                && ("NEUTRAL".equals(trend20m) || main.adx().compareTo(BigDecimal.valueOf(18)) < 0)) {
            return ContractSignalType.NEUTRAL;
        }
        if (todayChangePct.compareTo(new BigDecimal("25")) > 0 || todayChangePct.compareTo(new BigDecimal("-18")) < 0) {
            return ContractSignalType.NO_TRADE;
        }
        return ContractSignalType.NO_TRADE;
    }

    private static ContractScoreBreakdown scoreBreakdown(ContractSignalType signalType,
                                                         DirectionBias direction,
                                                         BigDecimal todayChangePct,
                                                         BigDecimal volume24h,
                                                         BigDecimal fundingRate,
                                                         BigDecimal openInterest,
                                                         BigDecimal oiChange,
                                                         IndicatorSnapshot main,
                                                         IndicatorSnapshot fast,
                                                         StructureSnapshot structure,
                                                         String trend1h,
                                                         String trend4h,
                                                         ContractNewsRiskAnalysis newsRisk) {
        int trend = 35;
        if (signalType == ContractSignalType.STRONG_LONG || signalType == ContractSignalType.TREND_SHORT) {
            trend += 35;
        } else if (signalType == ContractSignalType.PULLBACK_LONG || signalType == ContractSignalType.REVERSAL_SHORT) {
            trend += 25;
        } else if (signalType == ContractSignalType.NEUTRAL) {
            trend = 45;
        } else {
            trend = 25;
        }
        if (structure.clearFor(direction)) {
            trend += 10;
        }
        if (macdFollows(direction, main)) {
            trend += 8;
        }
        trend += Math.round(todayChangeScore(direction, todayChangePct) * 0.12f);
        if ((direction == DirectionBias.BULLISH && ("DOWN".equals(trend1h) || "DOWN".equals(trend4h)))
                || (direction == DirectionBias.BEARISH && ("UP".equals(trend1h) || "UP".equals(trend4h)))) {
            trend -= 12;
        }

        int volumeRatioScore = volumeRatioScore(main.volumeRatio());
        int volumeDirection = priceVolumeMatch(direction, main) ? 85 : 45;
        int volume = (int) Math.round(volumeRatioScore * 0.50 + volumeDirection * 0.30
                + (abnormalVolume(main) ? 35 : 80) * 0.20);

        int liquidity = volume24hScore(volume24h);

        int atrScore;
        if (main.atrPct().compareTo(new BigDecimal("0.30")) < 0) {
            atrScore = 35;
        } else if (main.atrPct().compareTo(new BigDecimal("0.50")) >= 0
                && main.atrPct().compareTo(new BigDecimal("2.50")) <= 0) {
            atrScore = 90;
        } else if (main.atrPct().compareTo(new BigDecimal("4")) > 0) {
            atrScore = 45;
        } else {
            atrScore = 70;
        }
        int wickRisk = wickRiskScore(direction, main);
        int volatilityExpansion = main.bollingerBandwidth().compareTo(new BigDecimal("8")) > 0 ? 65 : 80;
        int volatility = (int) Math.round(atrScore * 0.50 + wickRisk * 0.25 + volatilityExpansion * 0.25);

        int fundingHealth = fundingHealthScore(direction, fundingRate);
        int oiConsistency = oiConsistencyScore(direction, todayChangePct, openInterest, oiChange);
        int crowding = fundingRate.abs().compareTo(FUNDING_EXTREME) >= 0 ? 20
                : fundingRate.abs().compareTo(FUNDING_CROWDED) > 0 ? 45 : 85;
        int oiFunding = (int) Math.round(oiConsistency * 0.50 + fundingHealth * 0.30 + crowding * 0.20);

        int market = 70;
        if (direction == DirectionBias.BULLISH && "UP".equals(trend1h)) {
            market += 10;
        } else if (direction == DirectionBias.BEARISH && "DOWN".equals(trend1h)) {
            market += 10;
        }
        if (direction == DirectionBias.BULLISH && "UP".equals(trend4h)) {
            market += 8;
        } else if (direction == DirectionBias.BEARISH && "DOWN".equals(trend4h)) {
            market += 8;
        }
        if ((direction == DirectionBias.BULLISH && "DOWN".equals(trend1h) && "DOWN".equals(trend4h))
                || (direction == DirectionBias.BEARISH && "UP".equals(trend1h) && "UP".equals(trend4h))) {
            market -= 25;
        }

        int news = newsRisk.newsRiskScore();
        if (signalType == ContractSignalType.WAIT_OVERHEATED || signalType == ContractSignalType.WAIT_OVERSOLD) {
            trend -= 20;
            volume -= 10;
        }
        if (signalType == ContractSignalType.NO_TRADE) {
            trend = Math.min(trend, 35);
        }
        return new ContractScoreBreakdown(trend, volume, liquidity, volatility, oiFunding, market, news);
    }

    private static PlanPrices planPrices(BigDecimal entry, DirectionBias direction, IndicatorSnapshot main,
                                         StructureSnapshot structure, ContractSignalType signalType, int score) {
        BigDecimal safeEntry = positive(entry) ? entry : BigDecimal.ONE;
        BigDecimal atr = positive(main.atr()) ? main.atr() : safeEntry.multiply(new BigDecimal("0.01"));
        BigDecimal minDistance = safeEntry.multiply(MIN_STOP_DISTANCE_RATE);
        BigDecimal distance = atr.multiply(signalType == ContractSignalType.REVERSAL_SHORT
                        ? new BigDecimal("1.20")
                        : new BigDecimal("1.50"))
                .max(minDistance);
        BigDecimal stopLoss;
        if (direction == DirectionBias.BEARISH) {
            BigDecimal structureStop = positive(structure.recentSwingHigh())
                    ? structure.recentSwingHigh().add(atr.multiply(new BigDecimal("0.20")))
                    : safeEntry.add(distance);
            stopLoss = positive(structureStop) && structureStop.compareTo(safeEntry) > 0
                    ? structureStop.min(safeEntry.add(distance.multiply(new BigDecimal("2.5"))))
                    .min(safeEntry.multiply(new BigDecimal("1.048")))
                    : safeEntry.add(distance);
        } else if (direction == DirectionBias.BULLISH) {
            BigDecimal structureStop = positive(structure.recentSwingLow())
                    ? structure.recentSwingLow().subtract(atr.multiply(new BigDecimal("0.20")))
                    : safeEntry.subtract(distance);
            stopLoss = positive(structureStop) && structureStop.compareTo(safeEntry) < 0
                    ? structureStop.max(safeEntry.subtract(distance.multiply(new BigDecimal("2.5"))))
                    .max(safeEntry.multiply(new BigDecimal("0.952")))
                    : safeEntry.subtract(distance);
        } else {
            stopLoss = safeEntry.subtract(distance);
        }
        BigDecimal risk = safeEntry.subtract(stopLoss).abs().max(minDistance);
        BigDecimal rr = score >= 80 ? new BigDecimal("2.20") : new BigDecimal("1.80");
        BigDecimal takeProfit = direction == DirectionBias.BEARISH
                ? safeEntry.subtract(risk.multiply(rr))
                : safeEntry.add(risk.multiply(rr));
        return new PlanPrices(safeEntry, stopLoss, takeProfit);
    }

    private static String action(ContractSignalType signalType, int score, String entryTiming5m,
                                 ContractNewsRiskAnalysis newsRisk) {
        if (signalType == ContractSignalType.NO_TRADE || "CRITICAL".equals(newsRisk.newsRiskLevel())
                || "HIGH".equals(newsRisk.newsRiskLevel())) {
            return "NO_TRADE";
        }
        if (signalType == ContractSignalType.WAIT_OVERHEATED
                || signalType == ContractSignalType.WAIT_OVERSOLD
                || signalType == ContractSignalType.NEUTRAL) {
            return "WAIT";
        }
        if ("UNKNOWN".equals(newsRisk.newsRiskLevel()) || "MEDIUM".equals(newsRisk.newsRiskLevel())) {
            return "WAIT_CONFIRM";
        }
        if (score >= 80 && "READY".equals(entryTiming5m)) {
            return "AUTO_TRADE_ALLOWED";
        }
        return "WAIT_CONFIRM";
    }

    private static int leverage(ContractSignalType signalType, String action, int score, BigDecimal todayChangePct,
                                BigDecimal atrPct, BigDecimal fundingRate, ContractNewsRiskAnalysis newsRisk) {
        if (!"AUTO_TRADE_ALLOWED".equals(action) && !"WAIT_CONFIRM".equals(action)) {
            return 0;
        }
        int leverage = switch (signalType) {
            case STRONG_LONG, TREND_SHORT -> score >= 86 ? 5 : score >= 80 ? 4 : 3;
            case PULLBACK_LONG -> score >= 82 ? 4 : 2;
            case REVERSAL_SHORT -> 3;
            default -> 0;
        };
        if (signalType == ContractSignalType.REVERSAL_SHORT) {
            leverage = Math.min(leverage, 3);
        }
        if (todayChangePct.compareTo(new BigDecimal("10")) >= 0 && signalType != ContractSignalType.REVERSAL_SHORT) {
            leverage -= 1;
        }
        if (atrPct.compareTo(new BigDecimal("3")) > 0) {
            leverage -= 1;
        }
        if (fundingRate.abs().compareTo(FUNDING_CROWDED) > 0) {
            leverage -= 1;
        }
        if (!"LOW".equals(newsRisk.newsRiskLevel())) {
            leverage -= 1;
        }
        if (signalType == ContractSignalType.STRONG_LONG
                || signalType == ContractSignalType.PULLBACK_LONG
                || signalType == ContractSignalType.TREND_SHORT) {
            leverage = Math.max(2, leverage);
        }
        if (signalType == ContractSignalType.REVERSAL_SHORT) {
            leverage = Math.max(1, leverage);
        }
        return Math.max(0, Math.min(5, leverage));
    }

    private static String entryType(ContractSignalType signalType, String entryTiming5m, String action) {
        if ("NO_TRADE".equals(action) || signalType == ContractSignalType.NO_TRADE) {
            return "NO_ENTRY";
        }
        if ("WAIT".equals(action)) {
            return switch (signalType) {
                case WAIT_OVERHEATED -> "WAIT_PULLBACK";
                case WAIT_OVERSOLD -> "WAIT_RETEST";
                default -> "NO_ENTRY";
            };
        }
        if ("READY".equals(entryTiming5m)) {
            return "LIMIT";
        }
        if ("WAIT_RETEST".equals(entryTiming5m)) {
            return "WAIT_RETEST";
        }
        return "WAIT_PULLBACK";
    }

    private static String entryTiming(ContractSignalType signalType, IndicatorSnapshot fast, DirectionBias direction,
                                      BigDecimal distanceFromEma20Pct) {
        if (!isTradableSignal(signalType)) {
            return "NOT_READY";
        }
        boolean fastUp = fast.close().compareTo(fast.ema20()) > 0 && fast.ema20().compareTo(fast.ema60()) >= 0;
        boolean fastDown = fast.close().compareTo(fast.ema20()) < 0 && fast.ema20().compareTo(fast.ema60()) <= 0;
        boolean sharpMove = fast.atrPct().compareTo(new BigDecimal("2.8")) > 0
                || distanceFromEma20Pct.compareTo(new BigDecimal("5")) > 0;
        if (direction == DirectionBias.BULLISH) {
            if (fast.consecutiveUpperWicks() >= 2 || (sharpMove && fastUp)) {
                return "WAIT_PULLBACK";
            }
            return fastUp ? "READY" : "NOT_READY";
        }
        if (direction == DirectionBias.BEARISH) {
            if (fast.consecutiveLowerWicks() >= 2 || (sharpMove && fastDown)) {
                return "WAIT_RETEST";
            }
            return fastDown ? "READY" : "NOT_READY";
        }
        return "NOT_READY";
    }

    private static List<String> reasons(ContractSignalType signalType, BigDecimal todayChangePct,
                                        IndicatorSnapshot main, ContractKlineAnalysis klineAnalysis,
                                        ContractScoreBreakdown scores, ContractNewsRiskAnalysis newsRisk) {
        List<String> reasons = new ArrayList<>();
        reasons.add("signalType=" + signalType);
        reasons.add("今日涨跌幅=" + scale(todayChangePct) + "%");
        reasons.add("20m趋势=" + klineAnalysis.trend20m() + ", 1h趋势=" + klineAnalysis.trend1h()
                + ", 4h趋势=" + klineAnalysis.trend4h());
        reasons.add("20m量能倍数=" + scale(main.volumeRatio()) + ", RSI=" + scale(main.rsi())
                + ", ATRPct=" + scale(main.atrPct()));
        reasons.add("EMA20/EMA60趋势结构已纳入评分");
        reasons.add("RSI已纳入动量过滤");
        reasons.add("综合评分=" + scores.weightedTotal() + ", 新闻风险=" + newsRisk.newsRiskLevel());
        if (signalType == ContractSignalType.STRONG_LONG) {
            reasons.add("今日涨幅适中，20m多头结构确认且成交量放大");
        } else if (signalType == ContractSignalType.TREND_SHORT) {
            reasons.add("今日跌幅处于顺势空区间，20m空头结构确认且成交量放大");
        } else if (signalType == ContractSignalType.REVERSAL_SHORT) {
            reasons.add("涨幅偏高后20m出现上影和动能转弱，按反转空低杠杆处理");
        }
        return reasons;
    }

    private static List<String> risks(ContractSignalType signalType, BigDecimal todayChangePct, BigDecimal fundingRate,
                                      IndicatorSnapshot main, IndicatorSnapshot fast,
                                      ContractKlineAnalysis klineAnalysis, ContractNewsRiskAnalysis newsRisk) {
        List<String> risks = new ArrayList<>();
        if (signalType == ContractSignalType.WAIT_OVERHEATED) {
            risks.add("今日涨幅过热，禁止直接追高做多");
        }
        if (signalType == ContractSignalType.WAIT_OVERSOLD) {
            risks.add("今日跌幅过深，禁止直接追空");
        }
        if (fundingRate.abs().compareTo(FUNDING_CROWDED) > 0) {
            risks.add("资金费率拥挤，降低评分和杠杆");
        }
        if (main.atrPct().compareTo(new BigDecimal("4")) > 0) {
            risks.add("波动率过高");
        }
        if (main.consecutiveUpperWicks() >= 2) {
            risks.add("20m连续上影线，做多追高风险");
        }
        if (main.consecutiveLowerWicks() >= 2) {
            risks.add("20m连续下影线，做空追低风险");
        }
        if (!"READY".equals(klineAnalysis.entryTiming5m()) && isTradableSignal(signalType)) {
            risks.add("5m入场节奏未完全就绪：" + klineAnalysis.entryTiming5m());
        }
        if (fast.atrPct().compareTo(new BigDecimal("3")) > 0) {
            risks.add("5m短线波动偏大");
        }
        if (!"LOW".equals(newsRisk.newsRiskLevel())) {
            risks.add("新闻风险限制：" + newsRisk.decision().reason());
        }
        if (todayChangePct.compareTo(new BigDecimal("25")) > 0) {
            risks.add("今日涨幅超过25%，不能成为追多第一名");
        }
        return risks;
    }

    private static IndicatorSnapshot indicators(List<ContractCandle> candles) {
        List<ContractCandle> safe = clean(candles);
        if (safe.isEmpty()) {
            return IndicatorSnapshot.empty();
        }
        BigDecimal close = lastClose(safe);
        BigDecimal ema20 = ema(safe, 20);
        BigDecimal ema60 = ema(safe, 60);
        MacdSnapshot macd = macd(safe);
        BigDecimal rsi = rsi(safe, 14);
        BigDecimal atr = atr(safe, 14);
        BigDecimal atrPct = positive(close) ? atr.multiply(HUNDRED).divide(close, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal adx = adx(safe, atr);
        BollingerSnapshot bollinger = bollinger(safe);
        BigDecimal volumeMa20 = volumeAverage(safe, 20, true);
        BigDecimal volumeRatio = positive(volumeMa20)
                ? safe.get(safe.size() - 1).volume().divide(volumeMa20, 4, RoundingMode.HALF_UP)
                : BigDecimal.ONE;
        ContractCandle last = safe.get(safe.size() - 1);
        BigDecimal bodyPct = percent(last.close().subtract(last.open()).abs(), last.close());
        BigDecimal upperWickPct = percent(last.high().subtract(last.open().max(last.close())).max(BigDecimal.ZERO), last.close());
        BigDecimal lowerWickPct = percent(last.open().min(last.close()).subtract(last.low()).max(BigDecimal.ZERO), last.close());
        BigDecimal range = last.high().subtract(last.low());
        BigDecimal closePosition = positive(range) ? last.close().subtract(last.low()).divide(range, 4, RoundingMode.HALF_UP)
                : new BigDecimal("0.50");
        return new IndicatorSnapshot(
                close,
                ema20,
                ema60,
                macd.histogram(),
                macd.previousHistogram(),
                rsi,
                atr,
                atrPct,
                adx,
                bollinger.bandwidth(),
                volumeMa20,
                volumeRatio,
                bodyPct,
                upperWickPct,
                lowerWickPct,
                closePosition,
                consecutiveWicks(safe, true),
                consecutiveWicks(safe, false)
        );
    }

    private static StructureSnapshot structure(List<ContractCandle> candles) {
        List<ContractCandle> safe = clean(candles);
        if (safe.size() < 20) {
            return StructureSnapshot.range(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        int split = safe.size() - 20;
        int previousFrom = Math.max(0, split - 20);
        List<ContractCandle> previous = safe.subList(previousFrom, split);
        List<ContractCandle> recent = safe.subList(split, safe.size());
        BigDecimal recentHigh = max(recent.stream().map(ContractCandle::high).toList());
        BigDecimal recentLow = min(recent.stream().map(ContractCandle::low).toList());
        BigDecimal previousHigh = max(previous.stream().map(ContractCandle::high).toList());
        BigDecimal previousLow = min(previous.stream().map(ContractCandle::low).toList());
        boolean higherHigh = recentHigh.compareTo(previousHigh) > 0;
        boolean higherLow = recentLow.compareTo(previousLow) > 0;
        boolean lowerHigh = recentHigh.compareTo(previousHigh) < 0;
        boolean lowerLow = recentLow.compareTo(previousLow) < 0;
        String name;
        if (higherHigh && higherLow) {
            name = "HIGHER_HIGH_HIGHER_LOW";
        } else if (lowerHigh && lowerLow) {
            name = "LOWER_HIGH_LOWER_LOW";
        } else {
            name = "RANGE";
        }
        return new StructureSnapshot(name, recentHigh, recentLow, higherHigh, higherLow, lowerHigh, lowerLow);
    }

    private static String trendDirection(IndicatorSnapshot indicators, StructureSnapshot structure) {
        if (indicators.close().compareTo(indicators.ema20()) > 0
                && indicators.ema20().compareTo(indicators.ema60()) > 0
                && indicators.macdHistogram().compareTo(indicators.previousMacdHistogram()) >= 0
                && !structure.lowerStructure()) {
            return "UP";
        }
        if (indicators.close().compareTo(indicators.ema20()) < 0
                && indicators.ema20().compareTo(indicators.ema60()) < 0
                && indicators.macdHistogram().compareTo(indicators.previousMacdHistogram()) <= 0
                && !structure.higherStructure()) {
            return "DOWN";
        }
        return "NEUTRAL";
    }

    private static DirectionBias directionFor(ContractSignalType signalType, String trend20m, BigDecimal entry,
                                              IndicatorSnapshot main) {
        return switch (signalType) {
            case STRONG_LONG, PULLBACK_LONG -> DirectionBias.BULLISH;
            case REVERSAL_SHORT, TREND_SHORT -> DirectionBias.BEARISH;
            default -> {
                if ("UP".equals(trend20m) || entry.compareTo(main.ema20()) > 0) {
                    yield DirectionBias.BULLISH;
                }
                if ("DOWN".equals(trend20m) || entry.compareTo(main.ema20()) < 0) {
                    yield DirectionBias.BEARISH;
                }
                yield DirectionBias.NEUTRAL;
            }
        };
    }

    private static String emaState(IndicatorSnapshot main) {
        if (main.close().compareTo(main.ema20()) > 0 && main.ema20().compareTo(main.ema60()) > 0) {
            return "BULLISH";
        }
        if (main.close().compareTo(main.ema20()) < 0 && main.ema20().compareTo(main.ema60()) < 0) {
            return "BEARISH";
        }
        return "MIXED";
    }

    private static String macdState(IndicatorSnapshot main) {
        int compare = main.macdHistogram().compareTo(main.previousMacdHistogram());
        if (compare > 0) {
            return "STRENGTHENING";
        }
        if (compare < 0) {
            return "WEAKENING";
        }
        return "NEUTRAL";
    }

    private static boolean macdFollows(DirectionBias direction, IndicatorSnapshot main) {
        if (direction == DirectionBias.BULLISH) {
            return main.macdHistogram().compareTo(main.previousMacdHistogram()) >= 0;
        }
        if (direction == DirectionBias.BEARISH) {
            return main.macdHistogram().compareTo(main.previousMacdHistogram()) <= 0;
        }
        return false;
    }

    private static int volumeRatioScore(BigDecimal volumeRatio) {
        if (volumeRatio.compareTo(new BigDecimal("0.8")) < 0) {
            return 30;
        }
        if (volumeRatio.compareTo(new BigDecimal("1.2")) < 0) {
            return 50;
        }
        if (volumeRatio.compareTo(new BigDecimal("2.0")) < 0) {
            return 75;
        }
        if (volumeRatio.compareTo(new BigDecimal("4.0")) < 0) {
            return 90;
        }
        return 70;
    }

    private static int volume24hScore(BigDecimal volume24h) {
        if (volume24h == null || volume24h.compareTo(BigDecimal.valueOf(30_000_000L)) < 0) {
            return 25;
        }
        if (volume24h.compareTo(BigDecimal.valueOf(100_000_000L)) < 0) {
            return 55;
        }
        if (volume24h.compareTo(BigDecimal.valueOf(500_000_000L)) < 0) {
            return 75;
        }
        if (volume24h.compareTo(BigDecimal.valueOf(1_500_000_000L)) < 0) {
            return 88;
        }
        return 96;
    }

    private static boolean priceVolumeMatch(DirectionBias direction, IndicatorSnapshot main) {
        if (direction == DirectionBias.BULLISH) {
            return main.close().compareTo(main.ema20()) > 0 && main.volumeRatio().compareTo(new BigDecimal("1.2")) >= 0;
        }
        if (direction == DirectionBias.BEARISH) {
            return main.close().compareTo(main.ema20()) < 0 && main.volumeRatio().compareTo(new BigDecimal("1.2")) >= 0;
        }
        return false;
    }

    private static boolean abnormalVolume(IndicatorSnapshot main) {
        return main.volumeRatio().compareTo(new BigDecimal("4")) >= 0
                && main.bodyPct().compareTo(new BigDecimal("0.12")) < 0;
    }

    private static int wickRiskScore(DirectionBias direction, IndicatorSnapshot main) {
        if (direction == DirectionBias.BULLISH && main.consecutiveUpperWicks() >= 2) {
            return 35;
        }
        if (direction == DirectionBias.BEARISH && main.consecutiveLowerWicks() >= 2) {
            return 35;
        }
        if (main.upperWickPct().max(main.lowerWickPct()).compareTo(new BigDecimal("1.5")) > 0) {
            return 55;
        }
        return 85;
    }

    private static int fundingHealthScore(DirectionBias direction, BigDecimal fundingRate) {
        if (fundingRate.abs().compareTo(FUNDING_EXTREME) >= 0) {
            return 20;
        }
        if (direction == DirectionBias.BULLISH && fundingRate.compareTo(new BigDecimal("0.001")) > 0) {
            return 45;
        }
        if (direction == DirectionBias.BEARISH && fundingRate.compareTo(new BigDecimal("-0.001")) < 0) {
            return 45;
        }
        return 85;
    }

    private static int oiConsistencyScore(DirectionBias direction, BigDecimal todayChangePct,
                                          BigDecimal openInterest, BigDecimal oiChange) {
        if (openInterest == null || openInterest.signum() <= 0) {
            return 60;
        }
        if (direction == DirectionBias.BULLISH && todayChangePct.signum() > 0 && oiChange.signum() >= 0) {
            return 85;
        }
        if (direction == DirectionBias.BEARISH && todayChangePct.signum() < 0 && oiChange.signum() >= 0) {
            return 85;
        }
        if (oiChange.signum() < 0) {
            return 60;
        }
        return 55;
    }

    private static int todayChangeScore(DirectionBias direction, BigDecimal todayChangePct) {
        if (direction == DirectionBias.BEARISH) {
            if (todayChangePct.compareTo(new BigDecimal("-10")) < 0) {
                return 70;
            }
            if (todayChangePct.compareTo(new BigDecimal("-5")) <= 0) {
                return 90;
            }
            if (todayChangePct.compareTo(new BigDecimal("-2")) <= 0) {
                return 75;
            }
            if (todayChangePct.compareTo(BigDecimal.ZERO) <= 0) {
                return 50;
            }
            return 25;
        }
        if (todayChangePct.signum() < 0) {
            return 20;
        }
        if (todayChangePct.compareTo(new BigDecimal("2")) < 0) {
            return 50;
        }
        if (todayChangePct.compareTo(new BigDecimal("5")) < 0) {
            return 80;
        }
        if (todayChangePct.compareTo(new BigDecimal("10")) <= 0) {
            return 95;
        }
        if (todayChangePct.compareTo(new BigDecimal("15")) <= 0) {
            return 75;
        }
        if (todayChangePct.compareTo(new BigDecimal("25")) <= 0) {
            return 45;
        }
        return 10;
    }

    private static MacdSnapshot macd(List<ContractCandle> candles) {
        if (candles.size() < 2) {
            return new MacdSnapshot(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        List<BigDecimal> closes = candles.stream().map(ContractCandle::close).toList();
        List<BigDecimal> ema12 = emaSeries(closes, 12);
        List<BigDecimal> ema26 = emaSeries(closes, 26);
        List<BigDecimal> dif = new ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            dif.add(ema12.get(i).subtract(ema26.get(i)));
        }
        List<BigDecimal> dea = emaValues(dif, 9);
        BigDecimal current = dif.get(dif.size() - 1).subtract(dea.get(dea.size() - 1));
        BigDecimal previous = dif.size() >= 2 ? dif.get(dif.size() - 2).subtract(dea.get(dea.size() - 2)) : current;
        return new MacdSnapshot(current, previous);
    }

    private static BigDecimal ema(List<ContractCandle> candles, int period) {
        if (candles.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return emaSeries(candles.stream().map(ContractCandle::close).toList(), period).get(candles.size() - 1);
    }

    private static List<BigDecimal> emaSeries(List<BigDecimal> values, int period) {
        if (values.isEmpty()) {
            return List.of();
        }
        return emaValues(values, period);
    }

    private static List<BigDecimal> emaValues(List<BigDecimal> values, int period) {
        BigDecimal multiplier = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1L), 10, RoundingMode.HALF_UP);
        List<BigDecimal> result = new ArrayList<>();
        BigDecimal ema = values.get(0);
        result.add(ema);
        for (int i = 1; i < values.size(); i++) {
            ema = values.get(i).subtract(ema).multiply(multiplier).add(ema);
            result.add(ema);
        }
        return result;
    }

    private static BigDecimal rsi(List<ContractCandle> candles, int period) {
        if (candles.size() <= period) {
            return BigDecimal.valueOf(50);
        }
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            BigDecimal diff = candles.get(i).close().subtract(candles.get(i - 1).close());
            if (diff.signum() >= 0) {
                gains = gains.add(diff);
            } else {
                losses = losses.add(diff.abs());
            }
        }
        if (losses.signum() == 0) {
            return BigDecimal.valueOf(100);
        }
        BigDecimal rs = gains.divide(losses, 8, RoundingMode.HALF_UP);
        return HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(rs), 4, RoundingMode.HALF_UP));
    }

    private static BigDecimal atr(List<ContractCandle> candles, int period) {
        if (candles.size() < 2) {
            return lastClose(candles).multiply(new BigDecimal("0.01"));
        }
        List<BigDecimal> ranges = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            ContractCandle current = candles.get(i);
            BigDecimal previousClose = candles.get(i - 1).close();
            BigDecimal trueRange = List.of(
                    current.high().subtract(current.low()).abs(),
                    current.high().subtract(previousClose).abs(),
                    current.low().subtract(previousClose).abs()
            ).stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            ranges.add(trueRange);
        }
        int from = Math.max(0, ranges.size() - period);
        return average(ranges.subList(from, ranges.size()));
    }

    private static BigDecimal adx(List<ContractCandle> candles, BigDecimal atr) {
        if (candles.size() < 15 || atr.signum() <= 0) {
            return BigDecimal.valueOf(15);
        }
        BigDecimal now = candles.get(candles.size() - 1).close();
        BigDecimal before = candles.get(candles.size() - 15).close();
        BigDecimal directionalMovePct = percent(now.subtract(before).abs(), before);
        if (directionalMovePct.compareTo(new BigDecimal("3")) >= 0) {
            return BigDecimal.valueOf(32);
        }
        if (directionalMovePct.compareTo(new BigDecimal("1.5")) >= 0) {
            return BigDecimal.valueOf(24);
        }
        if (directionalMovePct.compareTo(new BigDecimal("0.6")) >= 0) {
            return BigDecimal.valueOf(19);
        }
        return BigDecimal.valueOf(12);
    }

    private static BollingerSnapshot bollinger(List<ContractCandle> candles) {
        if (candles.size() < 20) {
            return new BollingerSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        List<BigDecimal> closes = candles.subList(candles.size() - 20, candles.size()).stream()
                .map(ContractCandle::close)
                .toList();
        BigDecimal middle = average(closes);
        BigDecimal variance = closes.stream()
                .map(close -> close.subtract(middle).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(closes.size()), 8, RoundingMode.HALF_UP);
        BigDecimal std = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        BigDecimal upper = middle.add(std.multiply(BigDecimal.valueOf(2)));
        BigDecimal lower = middle.subtract(std.multiply(BigDecimal.valueOf(2)));
        BigDecimal bandwidth = positive(middle) ? upper.subtract(lower).multiply(HUNDRED).divide(middle, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new BollingerSnapshot(middle, upper, lower, bandwidth);
    }

    private static BigDecimal volumeAverage(List<ContractCandle> candles, int period, boolean excludeLast) {
        if (candles.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int to = excludeLast && candles.size() > 1 ? candles.size() - 1 : candles.size();
        int from = Math.max(0, to - period);
        if (from >= to) {
            return candles.get(candles.size() - 1).volume();
        }
        return average(candles.subList(from, to).stream().map(ContractCandle::volume).toList());
    }

    private static int consecutiveWicks(List<ContractCandle> candles, boolean upper) {
        int count = 0;
        for (int i = candles.size() - 1; i >= 0 && i >= candles.size() - 5; i--) {
            ContractCandle candle = candles.get(i);
            BigDecimal wick = upper
                    ? candle.high().subtract(candle.open().max(candle.close())).max(BigDecimal.ZERO)
                    : candle.open().min(candle.close()).subtract(candle.low()).max(BigDecimal.ZERO);
            BigDecimal range = candle.high().subtract(candle.low());
            if (positive(range) && wick.divide(range, 4, RoundingMode.HALF_UP).compareTo(new BigDecimal("0.60")) >= 0) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private static BigDecimal changeOverBars(List<ContractCandle> candles, int bars) {
        if (candles.size() <= bars) {
            return BigDecimal.ZERO;
        }
        BigDecimal now = candles.get(candles.size() - 1).close();
        BigDecimal before = candles.get(candles.size() - 1 - bars).close();
        return percent(now.subtract(before), before);
    }

    private static BigDecimal percent(BigDecimal delta, BigDecimal base) {
        if (!positive(base)) {
            return BigDecimal.ZERO;
        }
        return delta.multiply(HUNDRED).divide(base, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal safeRiskReward(DirectionBias direction, BigDecimal entry, BigDecimal stopLoss,
                                             BigDecimal takeProfit) {
        BigDecimal risk = direction == DirectionBias.BEARISH ? stopLoss.subtract(entry) : entry.subtract(stopLoss);
        BigDecimal reward = direction == DirectionBias.BEARISH ? entry.subtract(takeProfit) : takeProfit.subtract(entry);
        if (risk.signum() <= 0 || reward.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return reward.divide(risk, 2, RoundingMode.HALF_UP);
    }

    private static List<ContractCandle> clean(List<ContractCandle> candles) {
        if (candles == null) {
            return List.of();
        }
        return candles.stream()
                .filter(candle -> candle != null
                        && positive(candle.close())
                        && positive(candle.high())
                        && positive(candle.low())
                        && positive(candle.open()))
                .toList();
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal lastClose(List<ContractCandle> candles) {
        return candles.isEmpty() ? BigDecimal.ZERO : candles.get(candles.size() - 1).close();
    }

    private static BigDecimal max(List<BigDecimal> values) {
        return values.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
    }

    private static BigDecimal min(List<BigDecimal> values) {
        return values.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
    }

    private static BigDecimal defaultPositive(BigDecimal value, BigDecimal fallback) {
        return positive(value) ? value : fallback;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean isTradableSignal(ContractSignalType signalType) {
        return signalType == ContractSignalType.STRONG_LONG
                || signalType == ContractSignalType.PULLBACK_LONG
                || signalType == ContractSignalType.TREND_SHORT
                || signalType == ContractSignalType.REVERSAL_SHORT;
    }

    private static BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private record IndicatorSnapshot(
            BigDecimal close,
            BigDecimal ema20,
            BigDecimal ema60,
            BigDecimal macdHistogram,
            BigDecimal previousMacdHistogram,
            BigDecimal rsi,
            BigDecimal atr,
            BigDecimal atrPct,
            BigDecimal adx,
            BigDecimal bollingerBandwidth,
            BigDecimal volumeMa20,
            BigDecimal volumeRatio,
            BigDecimal bodyPct,
            BigDecimal upperWickPct,
            BigDecimal lowerWickPct,
            BigDecimal closePosition,
            int consecutiveUpperWicks,
            int consecutiveLowerWicks
    ) {
        static IndicatorSnapshot empty() {
            return new IndicatorSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.valueOf(50), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }
    }

    private record StructureSnapshot(
            String name,
            BigDecimal recentSwingHigh,
            BigDecimal recentSwingLow,
            boolean higherHigh,
            boolean higherLow,
            boolean lowerHigh,
            boolean lowerLow
    ) {
        static StructureSnapshot range(BigDecimal high, BigDecimal low) {
            return new StructureSnapshot("RANGE", high, low, false, false, false, false);
        }

        boolean higherStructure() {
            return higherHigh && higherLow;
        }

        boolean lowerStructure() {
            return lowerHigh && lowerLow;
        }

        boolean clearFor(DirectionBias direction) {
            if (direction == DirectionBias.BULLISH) {
                return higherStructure();
            }
            if (direction == DirectionBias.BEARISH) {
                return lowerStructure();
            }
            return false;
        }
    }

    private record MacdSnapshot(BigDecimal histogram, BigDecimal previousHistogram) {
    }

    private record BollingerSnapshot(BigDecimal middle, BigDecimal upper, BigDecimal lower, BigDecimal bandwidth) {
    }

    private record PlanPrices(BigDecimal entry, BigDecimal stopLoss, BigDecimal takeProfit) {
    }
}

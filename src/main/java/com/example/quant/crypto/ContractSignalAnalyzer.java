package com.example.quant.crypto;

import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.dto.ContractSignal;
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
    private static final BigDecimal VOLATILITY_HIGH = new BigDecimal("0.045");
    private static final BigDecimal VOLATILITY_TRADEABLE = new BigDecimal("0.008");
    private static final BigDecimal MIN_STOP_DISTANCE_RATE = new BigDecimal("0.006");

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
        List<ContractCandle> clean = candles.stream()
                .filter(candle -> positive(candle.close()) && positive(candle.high()) && positive(candle.low()))
                .toList();
        BigDecimal entry = positive(lastPrice) ? lastPrice : lastClose(clean);
        BigDecimal change24h = percent(entry.subtract(defaultPositive(open24h, entry)), defaultPositive(open24h, entry));
        BigDecimal change5m = changeOverBars(clean, 5);
        BigDecimal emaFast = ema(clean, 12);
        BigDecimal emaSlow = ema(clean, 26);
        BigDecimal rsi = rsi(clean, 14);
        BigDecimal atr = atr(clean, 14);
        BigDecimal volatility = positive(entry) ? atr.divide(entry, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal volumeSpike = volumeSpike(clean);
        BigDecimal oiChange = percent(openInterest.subtract(previousOpenInterest), previousOpenInterest);
        DirectionBias direction = emaFast.compareTo(emaSlow) >= 0 ? DirectionBias.BULLISH : DirectionBias.BEARISH;

        int score = 35;
        List<String> reasons = new ArrayList<>();
        List<String> risks = new ArrayList<>();

        if (emaFast.compareTo(emaSlow) > 0 && change5m.signum() > 0) {
            score += 22;
            reasons.add("EMA快线强于慢线且短周期动量为正");
        } else if (emaFast.compareTo(emaSlow) < 0 && change5m.signum() < 0) {
            score += 20;
            reasons.add("EMA快线弱于慢线且短周期动量为负");
        } else {
            score -= 8;
            risks.add("趋势与短周期动量不一致");
        }

        if (change24h.abs().compareTo(new BigDecimal("0.50")) < 0) {
            score -= 8;
            risks.add("24h方向不明确");
        } else if ((direction == DirectionBias.BULLISH && change24h.signum() > 0)
                || (direction == DirectionBias.BEARISH && change24h.signum() < 0)) {
            score += 10;
            reasons.add("24h方向与技术趋势一致");
        } else if (change24h.signum() != 0) {
            score -= 12;
            risks.add("24h方向与技术趋势冲突");
        }

        if (rsi.compareTo(BigDecimal.valueOf(45)) >= 0 && rsi.compareTo(BigDecimal.valueOf(68)) <= 0) {
            score += 14;
            reasons.add("RSI处于趋势延续区间");
        } else if (rsi.compareTo(BigDecimal.valueOf(72)) > 0 || rsi.compareTo(BigDecimal.valueOf(28)) < 0) {
            score -= 10;
            risks.add("RSI进入极端区间");
            reasons.add("RSI已纳入动量过滤");
        }

        if (volume24h.compareTo(BigDecimal.valueOf(1_500_000_000L)) >= 0) {
            score += 6;
            reasons.add("24h成交额满足主流流动性要求");
        }
        if (volumeSpike.compareTo(BigDecimal.valueOf(1.25)) >= 0) {
            score += 12;
            reasons.add("成交量相对均量放大");
        }
        if (openInterest.signum() > 0 && oiChange.compareTo(BigDecimal.valueOf(3)) >= 0) {
            score += 8;
            reasons.add("持仓量同步增加");
        }
        if (fundingRate.abs().compareTo(FUNDING_CROWDED) > 0) {
            score -= 18;
            risks.add("资金费率拥挤");
        } else {
            score += 8;
            reasons.add("资金费率未明显拥挤");
        }
        if (volatility.compareTo(VOLATILITY_HIGH) > 0) {
            score -= 18;
            risks.add("波动率过高");
        } else if (volatility.compareTo(VOLATILITY_TRADEABLE) >= 0) {
            score += 8;
            reasons.add("ATR波动处于可交易区间");
        }
        if (change24h.abs().compareTo(BigDecimal.valueOf(18)) > 0) {
            score -= 8;
            risks.add("24h涨跌幅过大");
        }

        score = Math.max(0, Math.min(100, score));
        int leverage = leverage(score, volatility, fundingRate);
        BigDecimal stopDistance = atr.multiply(BigDecimal.valueOf(direction == DirectionBias.BULLISH ? 1.35 : 1.45))
                .max(entry.multiply(MIN_STOP_DISTANCE_RATE));
        BigDecimal rewardDistance = stopDistance.multiply(score >= 78 ? new BigDecimal("2.35") : new BigDecimal("1.90"));
        BigDecimal stopLoss = direction == DirectionBias.BULLISH ? entry.subtract(stopDistance) : entry.add(stopDistance);
        BigDecimal takeProfit = direction == DirectionBias.BULLISH ? entry.add(rewardDistance) : entry.subtract(rewardDistance);
        BigDecimal riskReward = rewardDistance.divide(stopDistance, 2, RoundingMode.HALF_UP);

        if (reasons.isEmpty()) {
            reasons.add("综合指标未形成明显优势，仅保留观察");
        }
        return new ContractSignal(
                score,
                direction,
                scale(entry),
                scale(stopLoss.max(BigDecimal.ZERO)),
                scale(takeProfit.max(BigDecimal.ZERO)),
                leverage,
                riskReward,
                change5m,
                volumeSpike,
                fundingRate,
                openInterest,
                oiChange,
                volatility,
                List.copyOf(reasons),
                List.copyOf(risks)
        );
    }

    private static int leverage(int score, BigDecimal volatility, BigDecimal fundingRate) {
        if (score < 62 || volatility.compareTo(VOLATILITY_HIGH) > 0
                || fundingRate.abs().compareTo(FUNDING_CROWDED) > 0) {
            return 1;
        }
        if (score >= 84 && volatility.compareTo(new BigDecimal("0.018")) <= 0) {
            return 5;
        }
        if (score >= 76 && volatility.compareTo(new BigDecimal("0.028")) <= 0) {
            return 4;
        }
        if (score >= 68 && volatility.compareTo(new BigDecimal("0.036")) <= 0) {
            return 3;
        }
        return 2;
    }

    private static BigDecimal ema(List<ContractCandle> candles, int period) {
        if (candles.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal multiplier = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1L), 10, RoundingMode.HALF_UP);
        BigDecimal ema = candles.get(0).close();
        for (int i = 1; i < candles.size(); i++) {
            ema = candles.get(i).close().subtract(ema).multiply(multiplier).add(ema);
        }
        return ema;
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

    private static BigDecimal volumeSpike(List<ContractCandle> candles) {
        if (candles.size() < 20) {
            return BigDecimal.ONE;
        }
        BigDecimal recent = average(candles.subList(Math.max(0, candles.size() - 5), candles.size()).stream()
                .map(ContractCandle::volume)
                .toList());
        BigDecimal baseline = average(candles.subList(Math.max(0, candles.size() - 30), Math.max(0, candles.size() - 5)).stream()
                .map(ContractCandle::volume)
                .toList());
        return baseline.signum() > 0 ? recent.divide(baseline, 4, RoundingMode.HALF_UP) : BigDecimal.ONE;
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

    private static BigDecimal defaultPositive(BigDecimal value, BigDecimal fallback) {
        return positive(value) ? value : fallback;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}

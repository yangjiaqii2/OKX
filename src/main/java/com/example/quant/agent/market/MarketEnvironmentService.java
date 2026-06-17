package com.example.quant.agent.market;

import com.example.quant.crypto.OkxMarketService;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.market.DirectionBias;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MarketEnvironmentService {
    private static final BigDecimal TREND_THRESHOLD = new BigDecimal("0.25");
    private static final BigDecimal HIGH_RISK_MOVE_20M = new BigDecimal("2.50");
    private static final BigDecimal MEDIUM_RISK_MOVE_20M = new BigDecimal("1.50");

    private final OkxMarketService okxMarketService;

    public MarketEnvironmentService(OkxMarketService okxMarketService) {
        this.okxMarketService = okxMarketService;
    }

    public MarketEnvironment current() {
        try {
            MarketTrend btc = trend("BTC-USDT-SWAP");
            MarketTrend eth = trend("ETH-USDT-SWAP");
            List<String> reasons = new ArrayList<>();
            reasons.add("BTC 20m=" + btc.return20m() + "%, 1h=" + btc.return1h() + "%, trend=" + btc.direction());
            reasons.add("ETH 20m=" + eth.return20m() + "%, 1h=" + eth.return1h() + "%, trend=" + eth.direction());

            RiskLevel riskLevel = riskLevel(btc, eth);
            if (btc.direction() != DirectionBias.NEUTRAL
                    && eth.direction() != DirectionBias.NEUTRAL
                    && btc.direction() != eth.direction()) {
                riskLevel = max(riskLevel, RiskLevel.MEDIUM);
                reasons.add("BTC与ETH方向分歧，降低大盘环境评分");
            }
            if (riskLevel == RiskLevel.HIGH) {
                reasons.add("BTC/ETH短周期波动过大，禁止激进计划");
            }
            return new MarketEnvironment(
                    btc.direction(),
                    eth.direction(),
                    riskLevel,
                    btc.return20m(),
                    btc.return1h(),
                    eth.return20m(),
                    eth.return1h(),
                    List.copyOf(reasons),
                    Instant.now()
            );
        } catch (RuntimeException ex) {
            return MarketEnvironment.unavailable("BTC/ETH市场环境获取失败：" + ex.getMessage());
        }
    }

    private MarketTrend trend(String instId) {
        List<ContractCandle> fiveMinute = okxMarketService.candles(instId, "5m");
        List<ContractCandle> oneHour = okxMarketService.candles(instId, "1H");
        BigDecimal return20m = returnOverBars(fiveMinute, 4);
        BigDecimal return1h = returnOverBars(oneHour, 1);
        DirectionBias direction = DirectionBias.NEUTRAL;
        if (return20m.compareTo(TREND_THRESHOLD) >= 0 && return1h.signum() >= 0) {
            direction = DirectionBias.BULLISH;
        } else if (return20m.compareTo(TREND_THRESHOLD.negate()) <= 0 && return1h.signum() <= 0) {
            direction = DirectionBias.BEARISH;
        }
        return new MarketTrend(direction, return20m, return1h);
    }

    private static RiskLevel riskLevel(MarketTrend btc, MarketTrend eth) {
        BigDecimal maxAbs20m = btc.return20m().abs().max(eth.return20m().abs());
        if (maxAbs20m.compareTo(HIGH_RISK_MOVE_20M) >= 0) {
            return RiskLevel.HIGH;
        }
        if (maxAbs20m.compareTo(MEDIUM_RISK_MOVE_20M) >= 0) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private static BigDecimal returnOverBars(List<ContractCandle> candles, int bars) {
        if (candles == null || candles.size() <= bars) {
            return BigDecimal.ZERO;
        }
        BigDecimal now = candles.get(candles.size() - 1).close();
        BigDecimal before = candles.get(candles.size() - 1 - bars).close();
        if (before.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return now.subtract(before)
                .multiply(BigDecimal.valueOf(100))
                .divide(before, 4, RoundingMode.HALF_UP);
    }

    private static RiskLevel max(RiskLevel left, RiskLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private record MarketTrend(DirectionBias direction, BigDecimal return20m, BigDecimal return1h) {
    }
}

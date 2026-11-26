package com.example.quant.agent.selection;

import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.market.DirectionBias;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

final class AiEntryCounterfactual {
    private AiEntryCounterfactual() {}

    record Outcome(Instant priceAt, BigDecimal price, BigDecimal returnPct, BigDecimal mfePct, BigDecimal maePct) {}

    static Optional<Outcome> measure(Instant skippedAt, int minutes, BigDecimal entry, DirectionBias direction,
                                     List<ContractCandle> candles) {
        if (entry == null || entry.signum() <= 0
                || (direction != DirectionBias.BULLISH && direction != DirectionBias.BEARISH)) {
            return Optional.empty();
        }
        Instant start = skippedAt.truncatedTo(ChronoUnit.MINUTES);
        if (start.isBefore(skippedAt)) start = start.plusSeconds(60);
        Instant end = skippedAt.plusSeconds(minutes * 60L).truncatedTo(ChronoUnit.MINUTES);
        if (!start.isBefore(end)) return Optional.empty();

        var byTime = new HashMap<Instant, ContractCandle>();
        for (ContractCandle candle : candles) {
            if (candle.timestamp() != null && !candle.timestamp().isBefore(start) && candle.timestamp().isBefore(end)) {
                byTime.putIfAbsent(candle.timestamp(), candle);
            }
        }
        BigDecimal high = entry;
        BigDecimal low = entry;
        BigDecimal close = entry;
        for (Instant at = start; at.isBefore(end); at = at.plusSeconds(60)) {
            ContractCandle bar = byTime.get(at);
            if (bar == null || bar.low() == null || bar.low().signum() <= 0 || bar.high() == null
                    || bar.close() == null || bar.high().compareTo(bar.close()) < 0
                    || bar.close().compareTo(bar.low()) < 0) return Optional.empty();
            high = high.max(bar.high());
            low = low.min(bar.low());
            close = bar.close();
        }
        return Optional.of(new Outcome(end, close, pnl(entry, close, direction),
                pnl(entry, direction == DirectionBias.BULLISH ? high : low, direction),
                pnl(entry, direction == DirectionBias.BULLISH ? low : high, direction)));
    }

    private static BigDecimal pnl(BigDecimal entry, BigDecimal price, DirectionBias direction) {
        BigDecimal move = direction == DirectionBias.BULLISH ? price.subtract(entry) : entry.subtract(price);
        return move.movePointRight(2).divide(entry, 8, RoundingMode.HALF_UP);
    }
}

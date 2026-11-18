package com.example.quant.crypto.provider;

import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.provider.dto.AthBreakoutSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

public final class AthBreakoutProvider {
    private static final int SCALE = 6;

    private AthBreakoutProvider() {
    }

    public static AthBreakoutSnapshot calculate(String instId, BigDecimal currentPrice,
                                                List<ContractCandle> candles, DataQuality quality,
                                                int pagesFetched, boolean lookbackTruncated,
                                                BigDecimal atAthPct, BigDecimal nearAthPct,
                                                BigDecimal midRangePct) {
        BigDecimal ath = candles == null ? null : candles.stream()
                .filter(c -> c != null && c.high() != null && c.high().signum() > 0)
                .map(ContractCandle::high)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (ath == null || currentPrice == null || currentPrice.signum() <= 0) {
            return new AthBreakoutSnapshot(instId, ath, currentPrice, null, null,
                    DataQuality.UNAVAILABLE, pagesFetched, lookbackTruncated);
        }
        BigDecimal distance = currentPrice.subtract(ath)
                .divide(ath, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        String level = distance.compareTo(atAthPct) >= 0 ? "AT_ATH"
                : distance.compareTo(nearAthPct) >= 0 ? "NEAR_ATH"
                : distance.compareTo(midRangePct) >= 0 ? "MID_RANGE" : "DEEP_BELOW";
        return new AthBreakoutSnapshot(instId, ath, currentPrice, distance, level,
                quality, pagesFetched, lookbackTruncated);
    }
}

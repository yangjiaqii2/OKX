package com.example.quant.crypto.provider.dto;

import com.example.quant.crypto.provider.DataQuality;
import java.math.BigDecimal;

public record AthBreakoutSnapshot(
        String instId,
        BigDecimal athPrice,
        BigDecimal currentPrice,
        BigDecimal distanceToAthPct,
        String breakoutLevel,
        DataQuality dataQuality,
        int pagesFetched,
        boolean lookbackTruncated
) {
}

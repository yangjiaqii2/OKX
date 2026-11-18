package com.example.quant.crypto.selection;

import java.math.BigDecimal;

public enum V2ScoreDimension {
    CAPITAL_FLOW(BigDecimal.valueOf(25)),
    DERIVATIVES(BigDecimal.valueOf(20)),
    RELATIVE_STRENGTH(BigDecimal.valueOf(15)),
    MARKET_STRUCTURE(BigDecimal.valueOf(15)),
    VOLUME_QUALITY(BigDecimal.valueOf(10)),
    BREAKOUT(BigDecimal.valueOf(10)),
    NEWS(BigDecimal.valueOf(5));

    private final BigDecimal weight;

    V2ScoreDimension(BigDecimal weight) { this.weight = weight; }
    public BigDecimal weight() { return weight; }
}

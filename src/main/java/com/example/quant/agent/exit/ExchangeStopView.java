package com.example.quant.agent.exit;

import java.math.BigDecimal;

/**
 * The protective stop as it currently exists on the exchange.
 *
 * <p>The exchange is the source of truth for "where the stop is now". Local
 * state only records intent and observation, so every round starts by folding
 * this view back in.
 */
public record ExchangeStopView(
        String clientOrderId,
        String algoId,
        BigDecimal triggerPrice,
        BigDecimal size
) {
    public boolean usable() {
        return clientOrderId != null && !clientOrderId.isBlank()
                && triggerPrice != null && triggerPrice.signum() > 0;
    }
}

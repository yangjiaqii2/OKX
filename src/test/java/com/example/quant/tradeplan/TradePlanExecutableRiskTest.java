package com.example.quant.tradeplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.market.DirectionBias;
import com.example.quant.okxtrade.OkxInstrumentRules;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradePlanExecutableRiskTest {
    @Test
    void xauAndBtcUseNormalizedExecutableBaseQuantity() {
        TradePlan xau = plan("XAU-USDT-SWAP", new BigDecimal("0.71687429"), new BigDecimal("4415"), new BigDecimal("4384"));
        TradePlan btc = plan("BTC-USDT-SWAP", new BigDecimal("0.04987394"), new BigDecimal("63460"), new BigDecimal("64401"));
        assertThat(xau.estimatedStopLossAmount(new OkxInstrumentRules("XAU-USDT-SWAP", BigDecimal.ONE, "XAU", new BigDecimal("0.001"), new BigDecimal("0.001"), new BigDecimal("0.1"))))
                .isBetween(new BigDecimal("22"), new BigDecimal("23"));
        assertThat(btc.estimatedStopLossAmount(new OkxInstrumentRules("BTC-USDT-SWAP", BigDecimal.ONE, "BTC", new BigDecimal("0.001"), new BigDecimal("0.001"), new BigDecimal("0.1"))))
                .isBetween(new BigDecimal("46"), new BigDecimal("47"));
    }

    @Test
    void roundingRiskUsesNormalizedQuantityNotRawQuantity() {
        TradePlan plan = plan("BTC-USDT-SWAP", new BigDecimal("0.04987394"), new BigDecimal("63460"), new BigDecimal("64401"));
        OkxInstrumentRules rules = new OkxInstrumentRules("BTC-USDT-SWAP", new BigDecimal("100"), "BTC", new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.1"));
        BigDecimal rawContracts = rules.contractSizeFromBaseQuantity(plan.suggestedSize(), plan.entryPrice());
        BigDecimal normalizedContracts = rules.normalizeEntrySize(plan.suggestedSize(), plan.entryPrice());
        assertThat(rawContracts).isNotEqualByComparingTo(normalizedContracts);
        BigDecimal normalizedRisk = plan.estimatedStopLossAmount(rules);
        BigDecimal rawRisk = plan.entryPrice().subtract(plan.stopLossPrice()).abs()
                .multiply(rawContracts.multiply(rules.contractValue()).divide(plan.entryPrice(), 16, java.math.RoundingMode.DOWN));
        assertThat(normalizedRisk).isNotEqualByComparingTo(rawRisk.setScale(8, java.math.RoundingMode.HALF_UP));
    }

    private static TradePlan plan(String instId, BigDecimal size, BigDecimal entry, BigDecimal stop) {
        return new TradePlan(UUID.randomUUID(), instId, TradePlanType.OPEN_SHORT, "MARKET", DirectionBias.BEARISH,
                new BigDecimal("0.82"), entry, stop, new BigDecimal("60000"), 1, size,
                new BigDecimal("3165"), new BigDecimal("633"), new BigDecimal("2"), 90,
                BigDecimal.ZERO, new BigDecimal("0.02"), new BigDecimal("1000000"), "cross",
                List.of(), List.of(), "", false, Instant.now());
    }
}

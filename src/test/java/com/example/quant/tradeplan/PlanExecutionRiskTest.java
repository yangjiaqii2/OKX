package com.example.quant.tradeplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.exit.NetProfitExitPolicy.FeeRates;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxInstrumentRules;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlanExecutionRiskTest {
    private static final String INSTRUMENT = "BTC-USDT-SWAP";
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final BigDecimal ENTRY = new BigDecimal("100");
    private static final FeeRates FEES = new FeeRates(new BigDecimal("0.0002"), new BigDecimal("0.0005"));
    private final OkxInstrumentRules rules = new OkxInstrumentRules(INSTRUMENT, BigDecimal.ONE, "BTC",
            BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.01"));
    private final PlanExecutionRisk service = new PlanExecutionRisk(new CanonicalPlanCompiler(),
            null, null, null, new AgentProperties(), null);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void adverseSideQuoteAndCostsReduceLotsAndPreserveProtectionPrices(boolean shortSide) {
        TradePlanType action = shortSide ? TradePlanType.OPEN_SHORT : TradePlanType.OPEN_LONG;
        String stop = shortSide ? "103" : "97";
        String target = shortSide ? "90" : "110";
        TradeIntent intent = intent(action, stop, target);
        var quote = shortSide ? book("99.80", "99.82", NOW) : book("100.18", "100.20", NOW);
        var limits = new PlanExecutionRisk.Limits(new BigDecimal("100"), new BigDecimal("10000"),
                new BigDecimal("10000"), 2, new BigDecimal("1.5"), null);

        var result = service.evaluate(intent, rules, FEES, quote, limits, false, NOW);

        assertThat(result.accepted()).as(result.denial()).isTrue();
        assertThat(result.executable().risk().estimatedEntryPrice()).isEqualByComparingTo(shortSide ? "99.80" : "100.20");
        assertThat(result.executable().risk().estimatedNetStopLossAmount()).isLessThanOrEqualTo(limits.riskBudget());
        assertThat(result.executable().entry().executableContractQuantity()).isLessThan(new BigDecimal("33"));
        assertThat(result.executable().stopLoss().normalizedPrice()).isEqualByComparingTo(stop);
        assertThat(result.executable().takeProfitLegs().get(1).executableTargetPrice()).isEqualByComparingTo(target);
    }

    @Test
    void highFinalTargetDoesNotPassWhenWeightedNetRatioIsTooLow() {
        var limits = new PlanExecutionRisk.Limits(new BigDecimal("100"), new BigDecimal("10000"),
                new BigDecimal("10000"), 2, new BigDecimal("1.5"), null);
        var result = service.evaluate(intent(TradePlanType.OPEN_LONG, "99.4", "101.08"), rules, FEES,
                book("99.99", "100", NOW), limits, false, NOW);

        assertThat(result.denial()).startsWith("NET_RISK_REWARD_AFTER_COSTS_TOO_LOW:").contains(" < 1.5");
    }

    @Test
    void rejectsStaleQuoteEvenWhenSpreadAndPriceAreWithinLimits() {
        var limits = new PlanExecutionRisk.Limits(new BigDecimal("100"), new BigDecimal("10000"),
                new BigDecimal("10000"), 2, new BigDecimal("1.5"), null);
        var result = service.evaluate(intent(TradePlanType.OPEN_LONG, "97", "110"), rules, FEES,
                book("99.99", "100", NOW.minusSeconds(6)), limits, true, NOW);

        assertThat(result.denial()).isEqualTo("MARKET_EXECUTION_BOOK_STALE");
    }

    private static TradeIntent intent(TradePlanType action, String stop, String target) {
        BigDecimal stopPrice = new BigDecimal(stop);
        BigDecimal targetPrice = new BigDecimal(target);
        BigDecimal oneR = ENTRY.add(ENTRY.subtract(stopPrice));
        return new TradeIntent(UUID.randomUUID(), INSTRUMENT, action, "MARKET",
                ENTRY, stopPrice, targetPrice, new BigDecimal("100"), new BigDecimal("100"), "test",
                List.of(new TradeIntent.ExitLeg("TP1", BigDecimal.ONE, oneR, new BigDecimal("0.30"), ""),
                        new TradeIntent.ExitLeg("TP_FINAL", new BigDecimal("2"), targetPrice, new BigDecimal("0.70"), "")));
    }

    private static OrderBookLiquiditySnapshot book(String bid, String ask, Instant when) {
        return new OrderBookLiquiditySnapshot(INSTRUMENT, new BigDecimal(bid), new BigDecimal(ask), ENTRY,
                BigDecimal.ONE, new BigDecimal("1000000"), new BigDecimal("1000000"), BigDecimal.ONE, true, "", when);
    }
}

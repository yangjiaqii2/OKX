package com.example.quant.tradeplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.market.MarketType;
import com.example.quant.order.PendingOrder;
import com.example.quant.okxtrade.OkxInstrumentRules;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalPlanShadowTest {
    @Test
    void shadowComparisonIsReadOnlyAndReportsNoDifferenceForMatchingFacts() {
        UUID id = UUID.randomUUID();
        PendingOrder order = new PendingOrder(id, MarketType.OKX_SWAP, "XAU-USDT-SWAP",
                TradePlanType.OPEN_LONG, "buy", "long", "LIMIT",
                new BigDecimal("100"), new BigDecimal("10"), 1, "cross",
                new BigDecimal("90"), new BigDecimal("118"), new BigDecimal("100"),
                BigDecimal.ONE, 90, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                id, "token", Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
        OkxInstrumentRules rules = new OkxInstrumentRules("XAU-USDT-SWAP", BigDecimal.ONE, "XAU",
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.1"));
        TradeIntent intent = TradeIntent.fromPendingOrder(order, new BigDecimal("0.3"),
                new BigDecimal("0.4"), new BigDecimal("0.3"), "strategy-v1");
        CanonicalExecutablePlan plan = new CanonicalPlanCompiler().compile(intent, rules);
        order.setExecutableContractQuantity(plan.entry().executableContractQuantity());

        assertThat(CanonicalPlanShadow.divergences(order, plan)).isEmpty();
        assertThat(order.size()).isEqualByComparingTo("10");
    }

    @Test
    void rejectedCompilationResultIsPersistableForAuditButNotExecutable() {
        PendingOrder order = new PendingOrder(UUID.randomUUID(), MarketType.OKX_SWAP, "XAU-USDT-SWAP",
                TradePlanType.OPEN_LONG, "buy", "long", "LIMIT", new BigDecimal("100"), BigDecimal.ONE,
                1, "cross", new BigDecimal("90"), new BigDecimal("118"), BigDecimal.ONE,
                BigDecimal.ONE, 90, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                UUID.randomUUID(), "token", Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
        OkxInstrumentRules rules = new OkxInstrumentRules("XAU-USDT-SWAP", BigDecimal.ONE, "XAU",
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.1"));
        CanonicalExecutablePlan rejected = new CanonicalPlanCompiler().compile(
                TradeIntent.fromPendingOrder(order, new BigDecimal("0.3"), new BigDecimal("0.4"),
                        new BigDecimal("0.3"), "strategy-v1"), rules);

        assertThat(rejected.accepted()).isFalse();
        order.setCanonicalPlanSnapshot(rejected);
        assertThat(order.canonicalExecutablePlan().status())
                .isEqualTo(CanonicalExecutablePlan.CompilationStatus.REJECTED);
    }
}

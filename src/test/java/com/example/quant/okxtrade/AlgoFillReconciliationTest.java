package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlgoFillReconciliationTest {

    @Test
    void xauStopLossFixtureIsOrderIndependentAndCompleteOnlyAtAll717Contracts() {
        List<OkxCurrentOrderService.Fill> fills = xauFills();

        AlgoFillReconciliation partial = AlgoFillReconciliation.summarize(
                new BigDecimal("717"), fills.subList(0, 13));
        assertThat(partial.completeness()).isEqualTo(LedgerCompleteness.PARTIAL);

        AlgoFillReconciliation complete = AlgoFillReconciliation.summarize(new BigDecimal("717"), fills);
        assertThat(complete.completeness()).isEqualTo(LedgerCompleteness.COMPLETE);
        assertThat(complete.totalSize()).isEqualByComparingTo("717");
        assertThat(complete.grossPnl()).isEqualByComparingTo("-23.2472");
        assertThat(complete.fee()).isEqualByComparingTo("-1.571664");

        List<OkxCurrentOrderService.Fill> reversed = new ArrayList<>(fills);
        Collections.reverse(reversed);
        AlgoFillReconciliation reordered = AlgoFillReconciliation.summarize(new BigDecimal("717"), reversed);
        assertThat(reordered).isEqualTo(complete);
    }

    @Test
    void fullQuantityWithMissingPnlOrFeeRemainsPartial() {
        OkxCurrentOrderService.Fill missingPnl = new OkxCurrentOrderService.Fill(
                "fill-missing-pnl", "sell", "long", new BigDecimal("100"), BigDecimal.ONE,
                new BigDecimal("-0.01"), "USDT", null, Instant.parse("2026-09-07T00:00:00Z"));
        OkxCurrentOrderService.Fill missingFee = new OkxCurrentOrderService.Fill(
                "fill-missing-fee", "sell", "long", new BigDecimal("100"), BigDecimal.ONE,
                null, "USDT", BigDecimal.ONE, Instant.parse("2026-09-07T00:00:01Z"));

        assertThat(AlgoFillReconciliation.summarize(BigDecimal.ONE, List.of(missingPnl)).completeness())
                .isEqualTo(LedgerCompleteness.PARTIAL);
        assertThat(AlgoFillReconciliation.summarize(BigDecimal.ONE, List.of(missingFee)).completeness())
                .isEqualTo(LedgerCompleteness.PARTIAL);
    }

    private static List<OkxCurrentOrderService.Fill> xauFills() {
        List<OkxCurrentOrderService.Fill> fills = new ArrayList<>();
        BigDecimal remainingSize = new BigDecimal("717");
        BigDecimal remainingPnl = new BigDecimal("-23.2472");
        BigDecimal remainingFee = new BigDecimal("-1.571664");
        for (int index = 1; index <= 14; index++) {
            BigDecimal size = index == 14 ? remainingSize : new BigDecimal("50");
            BigDecimal pnl = index == 14 ? remainingPnl : new BigDecimal("-1.6");
            BigDecimal fee = index == 14 ? remainingFee : new BigDecimal("-0.11");
            fills.add(new OkxCurrentOrderService.Fill(
                    "xau-fill-" + index, "sell", "long", new BigDecimal("4416.4"), size,
                    fee, "USDT", pnl, Instant.parse("2026-09-06T01:00:00Z").plusSeconds(index)));
            remainingSize = remainingSize.subtract(size);
            remainingPnl = remainingPnl.subtract(pnl);
            remainingFee = remainingFee.subtract(fee);
        }
        return fills;
    }
}

package com.example.quant.agent.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.filter.FilterContext;
import com.example.quant.crypto.filter.FilterFunnel;
import com.example.quant.crypto.filter.FilterOutcome;
import com.example.quant.crypto.filter.FilterResult;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScanDiagnosticsV2Test {

    private static FilterContext fakeContext(String instId) {
        ProviderResult<MarketCapSnapshot> mcap = ProviderResult.fresh(
                new MarketCapSnapshot("BTC", BigDecimal.valueOf(1_000_000_000L),
                        null, null, null, null, java.time.Instant.now(), "test"),
                "test");
        return new FilterContext(instId, "BTC",
                BigDecimal.ONE, BigDecimal.valueOf(100_000L), null, null, null, null, mcap);
    }

    private static FilterOutcome outcomeWithFunnel(int universe, int... stages) {
        FilterFunnel funnel = new FilterFunnel();
        funnel.recordStage("universe_inputs", universe);
        funnel.recordStage("after_market_cap", universe);
        if (stages.length > 0) funnel.recordStage("after_circulation", stages[0]);
        if (stages.length > 1) funnel.recordStage("after_volume_mcap", stages[1]);
        if (stages.length > 2) funnel.recordStage("after_price_range", stages[2]);
        if (stages.length > 3) funnel.recordStage("after_liquidity", stages[3]);
        return new FilterOutcome(List.of(), List.of(), funnel);
    }

    @Test
    void fromV2EmitsAllTenFunnelStages() {
        FilterOutcome outcome = outcomeWithFunnel(3, 2, 1, 1, 1);

        ScanDiagnostics diag = ScanDiagnostics.fromV2(
                outcome, 3, Map.of("market_cap", DataQuality.FRESH),
                1, 1, 30, 1, 1, 1);

        Map<String, Integer> stages = diag.funnel().stages();
        assertThat(stages).containsKeys(
                "universe_inputs",
                "universe_tickers",
                "after_market_cap",
                "after_circulation",
                "after_volume_mcap",
                "after_price_range",
                "after_liquidity",
                "after_deep_analysis",
                "after_signal",
                "after_trade_gate",
                "after_final_rank"
        );
        assertThat(stages.get("universe_tickers")).isEqualTo(3);
        assertThat(stages.get("after_deep_analysis")).isEqualTo(1);
        assertThat(stages.get("after_signal")).isEqualTo(1);
        assertThat(stages.get("after_trade_gate")).isEqualTo(1);
        assertThat(stages.get("after_final_rank")).isEqualTo(1);
    }

    @Test
    void fromV1DoesNotEmitV2Stages() {
        FilterOutcome outcome = outcomeWithFunnel(5);

        ScanDiagnostics diag = ScanDiagnostics.fromV1(outcome, 5, Map.of(), 0);

        assertThat(diag.signalPassedCount()).isZero();
        assertThat(diag.tradeGatePassedCount()).isZero();
        assertThat(diag.finalRankPassedCount()).isZero();
        assertThat(diag.funnel().stages()).doesNotContainKey("after_deep_analysis");
        assertThat(diag.funnel().stages()).doesNotContainKey("after_final_rank");
        assertThat(diag.funnel().stages()).doesNotContainKey("universe_tickers");
    }

    @Test
    void fromLegacyFromMethodStaysBackwardCompatible() {
        FilterOutcome outcome = outcomeWithFunnel(10);

        ScanDiagnostics diag = ScanDiagnostics.from(
                outcome, 10,
                Map.of("market_cap", DataQuality.STALE),
                2);

        assertThat(diag.universeTickers()).isEqualTo(10);
        assertThat(diag.deepAnalysisTargetCount()).isEqualTo(2);
        assertThat(diag.providerStatus()).containsEntry("market_cap", DataQuality.STALE);
        assertThat(diag.signalPassedCount()).isZero();
    }

    @Test
    void asMapExposesAllV2Fields() {
        FilterOutcome outcome = outcomeWithFunnel(0);
        ScanDiagnostics diag = ScanDiagnostics.fromV2(
                outcome, 0, Map.of(),
                0, 0, 30, 0, 0, 0);

        Map<String, Object> map = diag.asMap();
        assertThat(map).containsKeys(
                "universeTickers", "passedCount", "rejectedCount",
                "deepAnalysisTargetCount", "deepAnalysisCompletedCount", "deepTargetLimit",
                "signalPassedCount",
                "tradeGatePassedCount", "finalRankPassedCount",
                "providerStatus", "funnel");
    }

    @Test
    void fromV2WithNullOutcomeIsStillValid() {
        ScanDiagnostics diag = ScanDiagnostics.fromV2(null, 0, null, 0, 0, 30, 0, 0, 0);
        assertThat(diag.universeTickers()).isZero();
        assertThat(diag.passedCount()).isZero();
        assertThat(diag.rejectedCount()).isZero();
        assertThat(diag.deepAnalysisTargetCount()).isZero();
        assertThat(diag.funnel().stages()).isEmpty();
    }

    @Test
    void fromV2WithMultiplePassedRecordsAllCounters() {
        // 验证 passed 列表 → passedCount；funnel 阶段 → 全部可读
        FilterFunnel funnel = new FilterFunnel();
        FilterContext ctx1 = fakeContext("BTC-USDT-SWAP");
        FilterContext ctx2 = fakeContext("ETH-USDT-SWAP");
        FilterOutcome outcome = new FilterOutcome(List.of(ctx1, ctx2), List.of(), funnel);

        ScanDiagnostics diag = ScanDiagnostics.fromV2(
                outcome, 100, Map.of("market_cap", DataQuality.FRESH),
                2, 2, 30, 1, 1, 1);

        assertThat(diag.passedCount()).isEqualTo(2);
        assertThat(diag.universeTickers()).isEqualTo(100);
        assertThat(diag.deepAnalysisTargetCount()).isEqualTo(2);
        assertThat(diag.signalPassedCount()).isEqualTo(1);
        assertThat(diag.tradeGatePassedCount()).isEqualTo(1);
        assertThat(diag.finalRankPassedCount()).isEqualTo(1);
    }
}

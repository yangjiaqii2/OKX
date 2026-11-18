package com.example.quant.agent.scan;

import com.example.quant.crypto.filter.FilterFunnel;
import com.example.quant.crypto.filter.FilterOutcome;
import com.example.quant.crypto.provider.DataQuality;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 扫描诊断信息。每轮 Universe Hard Filter 执行后由 {@code OkxContractScanner} 写入。
 *
 * <p>字段：<ul>
 *   <li>{@code universeTickers}: 拉到的全部 USDT-SWAP ticker 数量</li>
 *   <li>{@code providerStatus}: 各 Provider 的 DataQuality 状态（UNAVAILABLE / FRESH / STALE）</li>
 *   <li>{@code funnel}: Filter 漏斗（按 filter 名称 → 剩余数量）</li>
 *   <li>{@code passedCount}: 通过所有 Filter 的数量</li>
 *   <li>{@code rejectedCount}: 拒绝数量</li>
 *   <li>{@code deepAnalysisTargetCount}: 按限额选入 deep analysis 的目标数量</li>
 *   <li>{@code deepAnalysisCompletedCount}: 实际完成 deep analysis 的数量</li>
 *   <li>{@code deepTargetLimit}: 配置的 deep analysis 上限</li>
 *   <li>{@code signalPassedCount}: 产生有效 V1 signal 的数量（V2 path）</li>
 *   <li>{@code tradeGatePassedCount}: action != NO_TRADE 的数量（V2 path；V1 path 固定等于 passedCount）</li>
 *   <li>{@code finalRankPassedCount}: 排序后取 top N 的最终数量（V2 path）</li>
 * </ul>
 *
 * <p>向后兼容：<ul>
 *   <li>V1 路径调用 {@link #fromV1(FilterOutcome, int, Map, int)}，3 个 V2 字段固定为 0</li>
 *   <li>V2 路径调用 {@link #fromV2(FilterOutcome, int, Map, int, int, int, int)}，全部字段填充</li>
 *   <li>funnel 阶段名：V2 path 额外写入 {@code universe_tickers} + 4 个 V2 stage</li>
 * </ul>
 */
public record ScanDiagnostics(
        int universeTickers,
        Map<String, DataQuality> providerStatus,
        FilterFunnel funnel,
        int passedCount,
        int rejectedCount,
        int deepAnalysisTargetCount,
        int deepAnalysisCompletedCount,
        int deepTargetLimit,
        int signalPassedCount,
        int tradeGatePassedCount,
        int finalRankPassedCount
) {
    /** V1 path：3 个 V2 字段固定为 0。 */
    public static ScanDiagnostics fromV1(FilterOutcome outcome, int universeTickers,
                                          Map<String, DataQuality> providerStatus,
                                          int deepAnalysisTargetCount) {
        return new ScanDiagnostics(
                universeTickers,
                providerStatus == null ? Map.of() : Map.copyOf(providerStatus),
                outcome == null ? new FilterFunnel() : outcome.funnel(),
                outcome == null ? 0 : outcome.passed().size(),
                outcome == null ? 0 : outcome.rejected().size(),
                deepAnalysisTargetCount,
                deepAnalysisTargetCount,
                deepAnalysisTargetCount,
                0, 0, 0
        );
    }

    /**
     * V1 入口（向后兼容）。等价 {@code fromV1}，4 参；V2 字段为 0。
     * 既有 OkxContractScanner.scanV1 / 既有单测都走这里。
     */
    public static ScanDiagnostics from(FilterOutcome outcome, int universeTickers,
                                       Map<String, DataQuality> providerStatus,
                                       int deepAnalysisTargetCount) {
        return fromV1(outcome, universeTickers, providerStatus, deepAnalysisTargetCount);
    }

    /**
     * V2 path：填充全部字段，并把 V2 漏斗 stage 写入同一 {@link FilterFunnel}。
     *
     * <p>{@code outcome.funnel()} 必须已经包含 V1 6 个 stage（{@code universe_inputs} /
     * {@code after_market_cap} / {@code after_circulation} / {@code after_volume_mcap} /
     * {@code after_price_range} / {@code after_liquidity}）。
     */
    public static ScanDiagnostics fromV2(FilterOutcome outcome, int universeTickers,
                                          Map<String, DataQuality> providerStatus,
                                          int deepAnalysisTargetCount,
                                          int deepAnalysisCompletedCount,
                                          int deepTargetLimit,
                                          int signalPassedCount,
                                          int tradeGatePassedCount,
                                          int finalRankPassedCount) {
        if (outcome != null) {
            FilterFunnel funnel = outcome.funnel();
            funnel.recordStage("universe_tickers", universeTickers);
            funnel.recordStage("after_deep_analysis", deepAnalysisCompletedCount);
            funnel.recordStage("after_signal", signalPassedCount);
            funnel.recordStage("after_trade_gate", tradeGatePassedCount);
            funnel.recordStage("after_final_rank", finalRankPassedCount);
        }
        return new ScanDiagnostics(
                universeTickers,
                providerStatus == null ? Map.of() : Map.copyOf(providerStatus),
                outcome == null ? new FilterFunnel() : outcome.funnel(),
                outcome == null ? 0 : outcome.passed().size(),
                outcome == null ? 0 : outcome.rejected().size(),
                deepAnalysisTargetCount,
                deepAnalysisCompletedCount,
                deepTargetLimit,
                signalPassedCount,
                tradeGatePassedCount,
                finalRankPassedCount
        );
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("universeTickers", universeTickers);
        map.put("passedCount", passedCount);
        map.put("rejectedCount", rejectedCount);
        map.put("deepAnalysisTargetCount", deepAnalysisTargetCount);
        map.put("deepAnalysisCompletedCount", deepAnalysisCompletedCount);
        map.put("deepTargetLimit", deepTargetLimit);
        map.put("signalPassedCount", signalPassedCount);
        map.put("tradeGatePassedCount", tradeGatePassedCount);
        map.put("finalRankPassedCount", finalRankPassedCount);
        map.put("providerStatus", providerStatus);
        map.put("funnel", funnel.stages());
        return map;
    }
}

package com.example.quant.crypto.filter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Universe Filter 漏斗统计。
 *
 * <p>记录每经过一个 Filter 后剩余候选数量。
 * 例如：<pre>
 *   universe_tickers: 287
 *   after_market_cap: 87
 *   after_circulation: 65
 *   after_volume_mcap: 42
 *   after_price_range: 40
 *   after_liquidity: 38
 * </pre>
 *
 * <p>新 Filter 加入时只需调用 {@link #recordStage(String, int)}，不需要修改数据结构。
 */
public class FilterFunnel {
    private final Map<String, Integer> stages = new LinkedHashMap<>();
    private int accepted = 0;
    private int rejected = 0;

    public void recordStage(String filterName, int remaining) {
        stages.put(filterName, remaining);
    }

    public void recordAccepted() {
        accepted++;
    }

    public void recordRejected() {
        rejected++;
    }

    public Map<String, Integer> stages() {
        return Map.copyOf(stages);
    }

    public int accepted() {
        return accepted;
    }

    public int rejected() {
        return rejected;
    }
}

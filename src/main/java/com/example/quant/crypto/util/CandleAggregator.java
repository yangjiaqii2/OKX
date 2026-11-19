package com.example.quant.crypto.util;

import com.example.quant.crypto.dto.ContractCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯函数 K 线聚合工具。
 *
 * <p>职责：<ul>
 *   <li>{@link #aggregate20m(List)}：把 5m K 线按自然 UTC 20m 边界（00/20/40）聚合为 20m K 线</li>
 * </ul>
 *
 * <p>设计约束（Phase B）：<ul>
 *   <li><b>纯函数</b>：无状态、无副作用、不持有任何上下文；可安全并发调用</li>
 *   <li><b>不可变输入</b>：不修改入参 {@code fiveMinute}（用 sorted() 复制后处理）</li>
 *   <li><b>V1 兼容</b>：保持 V1 的 "只输出完整 4 根 5m K 线组成的桶" + "尾部 60 根截断" 行为</li>
 * </ul>
 *
 * <p>抽出理由：{@link com.example.quant.crypto.OkxContractScanner#safeKlines(String)} 与
 * 单元测试都需要复用此聚合逻辑，且本身不依赖任何 Scanner 状态。Scanner 自身不再暴露
 * {@code aggregate20m} 公共方法以保持内聚性。
 */
public final class CandleAggregator {
    /** 输出尾部最大保留根数（与 V1 一致）。 */
    public static final int MAX_AGGREGATED_BARS = 60;
    /** 20m 桶宽度（秒）。 */
    public static final long TWENTY_MINUTE_BUCKET_SECONDS = 1200L;
    /** 完整桶需要的最少 5m K 线数。 */
    public static final int MIN_CANDLES_PER_BUCKET = 4;

    private CandleAggregator() {
    }

    /**
     * 把 5m K 线按自然 UTC 20m 边界聚合为 20m K 线。
     *
     * <p>聚合规则：<ol>
     *   <li>输入先按 timestamp 升序排序（容忍乱序）</li>
     *   <li>按 {@code floor(timestamp mod 1200)} 对齐到 20m 桶起点</li>
     *   <li>桶内 5m K 线数 < 4 → 整桶丢弃（V1 行为，避免尾桶不完整）</li>
     *   <li>完全重复的行去重；时间点缺失、错位或冲突的桶丢弃</li>
     *   <li>输出 20m K 线：open = 首根 5m open；close = 第 4 根 5m close；high/low/volume = 桶内 4 根的 max/min/sum</li>
     *   <li>尾部最多保留 {@link #MAX_AGGREGATED_BARS} 根（V1 行为）</li>
     * </ol>
     *
     * @param fiveMinute 5m K 线列表（可为 null 或空，返回空列表）
     * @return 聚合后的 20m K 线列表（不可变）
     */
    public static List<ContractCandle> aggregate20m(List<ContractCandle> fiveMinute) {
        if (fiveMinute == null || fiveMinute.isEmpty()) {
            return List.of();
        }
        // 1. 按 timestamp 排序（输入可能乱序，例如 Okx 返回倒序或被打散）。
        List<ContractCandle> sorted = fiveMinute.stream()
                .filter(c -> c != null && c.timestamp() != null)
                .distinct()
                .sorted(Comparator.comparing(ContractCandle::timestamp))
                .toList();
        // 2. 按自然 UTC 20m 边界分桶（00/20/40），不完整 bucket 直接丢弃。
        //    这是 V1 旧行为：只有刚好 4 根 5m K 线组成完整 20m 窗口时才输出聚合 K 线。
        Map<Instant, List<ContractCandle>> byBucket = new LinkedHashMap<>();
        for (ContractCandle c : sorted) {
            long sec = c.timestamp().getEpochSecond();
            Instant bucketStart = Instant.ofEpochSecond(sec - Math.floorMod(sec, TWENTY_MINUTE_BUCKET_SECONDS));
            byBucket.computeIfAbsent(bucketStart, k -> new ArrayList<>()).add(c);
        }
        List<ContractCandle> result = new ArrayList<>();
        for (Map.Entry<Instant, List<ContractCandle>> e : byBucket.entrySet()) {
            List<ContractCandle> window = e.getValue();
            if (window.size() != MIN_CANDLES_PER_BUCKET) {
                continue;
            }
            boolean aligned = true;
            for (int i = 0; i < window.size(); i++) {
                if (!e.getKey().plusSeconds(i * 300L).equals(window.get(i).timestamp())) {
                    aligned = false;
                    break;
                }
            }
            if (!aligned) {
                continue;
            }
            BigDecimal high = window.stream().map(ContractCandle::high)
                    .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            BigDecimal low = window.stream().map(ContractCandle::low)
                    .min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            BigDecimal volume = window.stream().map(ContractCandle::volume)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(new ContractCandle(
                    e.getKey(),
                    window.get(0).open(),
                    high,
                    low,
                    window.get(3).close(),
                    volume
            ));
        }
        // 3. 限制最多返回 MAX_AGGREGATED_BARS 个聚合 K 线（保留 V1 旧尾部截断）。
        int from = Math.max(0, result.size() - MAX_AGGREGATED_BARS);
        return List.copyOf(result.subList(from, result.size()));
    }
}

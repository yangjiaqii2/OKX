package com.example.quant.crypto.scan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 深度分析名额分配器。
 *
 * <p>硬过滤（市值 / 流通比例 / 成交额市值比 / 价格区间 / 流动性）已经完成资格判定，
 * 进入本层的都是"已经合格"的候选。本层只回答一个问题：
 * <b>有限的 deepAnalysisLimit 个深度分析名额分配给谁</b>。
 *
 * <p>原行为是纯成交额降序截断，后果是成交额排名在 limit 之外的合格候选
 * <b>持续排在 limit 之外期间拿不到任何分析机会</b>。本分配器把名额拆成三段：
 * <ul>
 *   <li><b>固定名额</b>（{@code pinnedInstIds}）：已经存在活跃等待入场意图的合约。
 *       它们必须每轮拿到新数据，否则触发判断和失效判断都无从谈起；</li>
 *   <li><b>成交额头部</b>：保持原行为不变；</li>
 *   <li><b>轮换名额</b>（{@code rotationQuota} 个）：在剩余合格候选上按确定性游标轮转。</li>
 * </ul>
 *
 * <p>名额总量恒等于 {@code deepAnalysisLimit} → 分析目标数量不变；
 * 不引入任何行情判断 → 不会用"涨得最多"替代"成交额最大"。
 *
 * <p><b>覆盖保证的适用范围（重要）</b>：游标是<b>尾部列表的下标</b>，不是每个合约的最后分析时间。
 * 因此 {@code ceil(tailSize / rotationQuota)} 轮的覆盖上界只在
 * <b>尾部名单与其排序在这些轮次中保持稳定、扫描持续完成并 commit、进程未重启</b> 时成立。
 * 真实市场里成交额排序和入围名单都会变，游标也会随重启归零，
 * 所以本类保证的是<b>静态名单上的确定性轮换</b>，不是"任意合约在 N 轮内必被覆盖"的动态保证。
 * 需要后者就得改为按 instId 记录最后分析时间并持久化，本轮未做。
 *
 * <p>游标只在 {@link #commit(Allocation)} 时推进。扫描因 deadline 中断而未完成时不要 commit，
 * 否则本轮没真正分析到的候选会被当成"已覆盖"而跳过。
 *
 * <p>非线程安全：由单次扫描串行调用（{@code OkxContractScanner.scanV2}）。
 */
public final class DeepAnalysisTargetAllocator {

    private int cursor;

    /**
     * @param rankedByTurnoverDesc 硬过滤通过的候选，按 24h 成交额降序
     * @param deepAnalysisLimit    本轮深度分析名额总量
     * @param rotationQuota        其中预留给轮换覆盖的名额
     */
    public Allocation allocate(List<String> rankedByTurnoverDesc, int deepAnalysisLimit, int rotationQuota) {
        return allocate(rankedByTurnoverDesc, deepAnalysisLimit, rotationQuota, Set.of());
    }

    /**
     * @param rankedByTurnoverDesc 硬过滤通过的候选，按 24h 成交额降序
     * @param deepAnalysisLimit    本轮深度分析名额总量（固定名额也从这里面扣）
     * @param rotationQuota        其中预留给轮换覆盖的名额
     * @param pinnedInstIds        必须每轮拿到数据刷新的合约（活跃等待入场意图）。
     *                             只有同时出现在 {@code rankedByTurnoverDesc} 里的才会被选中——
     *                             本层不绕过硬过滤资格判定。
     */
    public Allocation allocate(List<String> rankedByTurnoverDesc, int deepAnalysisLimit, int rotationQuota,
                               Set<String> pinnedInstIds) {
        List<String> passed = rankedByTurnoverDesc == null ? List.of() : rankedByTurnoverDesc;
        int limit = Math.max(1, deepAnalysisLimit);
        if (passed.size() <= limit) {
            // 名额够用，没有任何候选被排除，pin 与轮换都无意义。
            return new Allocation(List.copyOf(passed), 0, passed.size(), 0, 0);
        }
        // 固定名额优先，但同样受总量截断：等待意图不得突破请求预算。
        List<String> pinned = new ArrayList<>();
        if (pinnedInstIds != null && !pinnedInstIds.isEmpty()) {
            for (String instId : passed) {
                if (pinned.size() >= limit) {
                    break;
                }
                if (pinnedInstIds.contains(instId)) {
                    pinned.add(instId);
                }
            }
        }
        Set<String> pinnedSelected = new LinkedHashSet<>(pinned);
        List<String> remaining = passed.stream().filter(instId -> !pinnedSelected.contains(instId)).toList();
        int budget = limit - pinned.size();
        if (budget <= 0 || remaining.isEmpty()) {
            return new Allocation(List.copyOf(pinned), pinned.size(), 0, 0, cursor);
        }
        if (remaining.size() <= budget) {
            List<String> selected = new ArrayList<>(pinned);
            selected.addAll(remaining);
            return new Allocation(List.copyOf(selected), pinned.size(), remaining.size(), 0, 0);
        }
        // 头部至少保留 1 个名额：轮换永远不能吃光成交额头部。
        int quota = Math.max(0, Math.min(rotationQuota, budget - 1));
        int headSize = budget - quota;
        List<String> head = remaining.subList(0, headSize);
        List<String> tail = remaining.subList(headSize, remaining.size());
        if (quota == 0 || tail.isEmpty()) {
            List<String> selected = new ArrayList<>(pinned);
            selected.addAll(head);
            return new Allocation(List.copyOf(selected), pinned.size(), head.size(), 0, 0);
        }
        int start = Math.floorMod(cursor, tail.size());
        int rotationSize = Math.min(quota, tail.size());
        List<String> selected = new ArrayList<>(pinned.size() + headSize + rotationSize);
        selected.addAll(pinned);
        selected.addAll(head);
        for (int offset = 0; offset < rotationSize; offset++) {
            selected.add(tail.get((start + offset) % tail.size()));
        }
        return new Allocation(List.copyOf(selected), pinned.size(), headSize, rotationSize,
                (start + rotationSize) % tail.size());
    }

    /** 提交本轮分配，推进轮换游标。扫描未完成时不要调用。 */
    public void commit(Allocation allocation) {
        if (allocation != null) {
            cursor = allocation.nextCursor();
        }
    }

    /**
     * @param instIds       本轮深度分析目标，按 [固定名额..., 成交额头部..., 轮换名额...] 排列
     * @param pinnedCount   其中来自固定名额（活跃等待意图）的数量
     * @param headCount     其中来自成交额头部的数量
     * @param rotationCount 其中来自轮换覆盖的数量
     * @param nextCursor    commit 后的新游标
     */
    public record Allocation(List<String> instIds, int pinnedCount, int headCount, int rotationCount,
                             int nextCursor) {
    }
}

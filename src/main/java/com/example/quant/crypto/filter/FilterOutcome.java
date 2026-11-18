package com.example.quant.crypto.filter;

import java.util.List;

/**
 * UniverseHardFilter 批量执行结果。
 *
 * @param passed   通过所有 Filter 的输入
 * @param rejected 被某 Filter 拒绝的输入及拒绝原因
 * @param funnel   漏斗统计
 */
public record FilterOutcome(
        List<FilterContext> passed,
        List<RejectedCandidate> rejected,
        FilterFunnel funnel
) {
    public record RejectedCandidate(FilterContext context, FilterResult rejection) {
    }
}

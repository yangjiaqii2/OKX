package com.example.quant.crypto.scan;

import java.util.Set;

/**
 * 当前存在活跃等待入场意图的合约。
 *
 * <p>{@code WaitingEntryIntentService.evaluate()} 只对"出现在交付列表里的合约"判断触发与失效，
 * 找不到候选就跳过。所以等待意图能否被正常评估，取决于扫描链路知不知道它的存在：
 * 记录没被删除 ≠ 它还在被持续评估。
 *
 * <p>扫描器用它来固定深度分析名额和交付名额，两者都从既有上限内部扣除，不扩大请求预算。
 * 这里<b>只提供"谁需要新数据"，不提供任何放行权限</b>：所有门控仍在下游原位生效。
 */
@FunctionalInterface
public interface ActiveEntryIntentInstIds {

    /** 未终结的等待意图对应的 instId；实现方不可抛异常，无数据时返回空集合。 */
    Set<String> currentInstIds();
}

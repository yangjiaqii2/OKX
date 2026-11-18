package com.example.quant.crypto.filter;

/**
 * 单个 Universe 硬资格 Filter。
 *
 * <p>实现必须：<ul>
 *   <li>纯函数：不修改入参</li>
 *   <li>不抛异常：缺失数据视为 REJECT（reason 标明 UNAVAILABLE）</li>
 *   <li>确定性：相同输入必须产生相同结果</li>
 * </ul>
 */
public interface UniverseFilter {
    String name();

    FilterResult apply(FilterContext context);
}

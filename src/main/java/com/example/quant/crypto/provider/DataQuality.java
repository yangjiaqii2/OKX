package com.example.quant.crypto.provider;

/**
 * 第三方数据质量标记。
 *
 * <p>所有 Provider 实现必须返回带 {@link DataQuality} 标记的结果。Universe Filter 和 Selection Score
 * 根据这个标记决定如何处理数据：{@link #AVAILABLE} / {@link #FRESH} 视为可参与硬过滤；
 * {@link #STALE} 仅作为参考但不允许通过硬资格；{@link #UNAVAILABLE} / {@link #TIMEOUT} 视为
 * 数据缺失，对应硬资格直接拒绝。
 */
public enum DataQuality {
    /** 数据当前可用、刚刚获取，未应用 TTL。 */
    AVAILABLE,
    /** 数据在 TTL 之内，可参与硬过滤与评分。 */
    FRESH,
    /** 仅遍历到 Provider 配置安全上限，结果不代表完整历史。 */
    PARTIAL,
    /** 数据存在但已超过 TTL 仍可返回历史值。仅供观察，Universe 硬资格视为缺失。 */
    STALE,
    /** Provider 没有数据返回（未配置 / 上游无此 symbol）。 */
    UNAVAILABLE,
    /** Provider 调用超时。 */
    TIMEOUT
}

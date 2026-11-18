package com.example.quant.crypto.filter;

import com.example.quant.crypto.provider.DataQuality;

/**
 * 单个 Universe Filter 的判定结果。
 *
 * <p>每个 REJECT 必须附带 {@code reason}，使得后续诊断可以解释：
 * 例如 {@code MARKET_CAP_TOO_LOW} / {@code CIRCULATION_UNAVAILABLE}。
 *
 * @param filterName  Filter 名称（与 UniverseFilter#name 一致）
 * @param passed      是否通过
 * @param reason      拒绝原因，通过时为 null
 * @param actualValue 实际值（用于诊断），可空
 * @param threshold   阈值（用于诊断），可空
 * @param dataQuality 数据质量（用于诊断），可空
 */
public record FilterResult(
        String filterName,
        boolean passed,
        String reason,
        Object actualValue,
        Object threshold,
        DataQuality dataQuality
) {
    public static FilterResult pass(String filterName) {
        return new FilterResult(filterName, true, null, null, null, null);
    }

    public static FilterResult reject(String filterName, String reason) {
        return new FilterResult(filterName, false, reason, null, null, null);
    }

    public static FilterResult reject(String filterName, String reason,
                                      Object actualValue, Object threshold, DataQuality dataQuality) {
        return new FilterResult(filterName, false, reason, actualValue, threshold, dataQuality);
    }
}

package com.example.quant.crypto.provider.dto;

/**
 * Funding 趋势分类（基于最近 3 个 funding events 线性回归斜率）。
 *
 * <p>公式：{@code x = [1, 2, 3]}，{@code y = fundingRate[i]}，{@code slope = Σ(xi-x̄)(yi-ȳ) / Σ(xi-x̄)²}。
 *
 * <p>分类阈值：<ul>
 *   <li>{@code slope > 0.001} → {@link #HEATING}（多空资金费率同步上行，多头付息）</li>
 *   <li>{@code slope < -0.001} → {@link #COOLING}（多空资金费率同步下行，空头付息）</li>
 *   <li>其他 → {@link #STABLE}（震荡）</li>
 * </ul>
 *
 * <p>阈值 {@code 0.001} 在
 * {@code com.example.quant.crypto.provider.okx.OkxFundingHistoryProvider} 中硬编码，
 * 不通过 {@code AgentProperties} 配置。Funding rate 量级 ≈ 0.0001 ~ 0.01 per 8h，
 * 0.001 阈值足以区分"趋势性"与"噪声"。
 *
 * <p>趋势与 {@link FundingHistorySnapshot#extreme()} 正交：HEATING 资金费率可能
 * 同时极端（多头极度贪婪），STABLE 也可能极端（双边同时极端少，但理论上可能）。
 */
public enum FundingTrend {
    /** slope > 0.001：资金费率上行趋势。 */
    HEATING,
    /** slope < -0.001：资金费率下行趋势。 */
    COOLING,
    /** |slope| <= 0.001：震荡。 */
    STABLE;

    /**
     * 趋势分类。
     *
     * @param slope 最近 3 个 funding events 的最小二乘斜率（可为 NaN → STABLE）
     * @return FundingTrend 枚举值
     */
    public static FundingTrend classify(double slope) {
        if (Double.isNaN(slope) || Double.isInfinite(slope)) {
            return STABLE;
        }
        if (slope > 0.001) {
            return HEATING;
        }
        if (slope < -0.001) {
            return COOLING;
        }
        return STABLE;
    }
}

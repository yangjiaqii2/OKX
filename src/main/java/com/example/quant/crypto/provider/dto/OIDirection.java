package com.example.quant.crypto.provider.dto;

import java.math.BigDecimal;

/**
 * OI Direction 四象限分类。
 *
 * <p>由价格变化方向 × OI 变化方向构成 2x2 矩阵：
 * <pre>
 *           oi > 0           oi <= 0
 * price > 0  LEADING         FOLLOWING_WEAK
 * price < 0  CAPITULATION    WEAK_DOWN
 * </pre>
 *
 * <p>边界规则（与方向判定强耦合，必须与 Provider / Aggregator 同步）：<ul>
 *   <li>{@code price == 0}（持平）→ 视为 {@code price >= 0}（正向）</li>
 *   <li>{@code oi == 0}（无变化）→ 视为 {@code oi <= 0}（负向）</li>
 * </ul>
 *
 * <p><b>强度信息不由 Direction 承载</b>：{@code oiChange1h / 4h / 24h} 数值字段在
 * {@link OpenInterestHistorySnapshot} 中以原始值提供，强度差异由调用方自行处理。
 *
 * <p>Provider 不计算 Direction（避免耦合价格）。调用方
 * （{@code OkxContractScanner.scanV2} 聚合层）持有 {@code candidate.changePercent24h}
 * + {@code snapshot.oiChange24h()}，调用 {@link #classify(BigDecimal, BigDecimal)}。
 */
public enum OIDirection {
    /** 价格 ↑ + OI ↑：趋势 + 加仓，强势跟随。 */
    LEADING,
    /** 价格 ↑ + OI ↓/0：价格上行但资金离场，动能衰减。 */
    FOLLOWING_WEAK,
    /** 价格 ↓ + OI ↑：价格下跌但仍在加仓，恐慌接盘 / 逼空。 */
    CAPITULATION,
    /** 价格 ↓ + OI ↓/0：价格下跌且资金离场，弱势下行。 */
    WEAK_DOWN;

    /**
     * 方向矩阵分类。
     *
     * @param priceChange24h 候选 24h 价格变化百分比（可为 null → 视为 0）
     * @param oiChange24h     OI 24h 变化百分比（可为 null → 视为 0）
     * @return OIDirection 枚举值
     */
    public static OIDirection classify(BigDecimal priceChange24h, BigDecimal oiChange24h) {
        // 边界：null 视为 0；price == 0 视为正；oi == 0 视为负
        boolean priceUp = priceChange24h == null || priceChange24h.signum() >= 0;
        boolean oiUp = oiChange24h != null && oiChange24h.signum() > 0;
        if (priceUp && oiUp) {
            return LEADING;
        }
        if (priceUp) {
            return FOLLOWING_WEAK;
        }
        if (oiUp) {
            return CAPITULATION;
        }
        return WEAK_DOWN;
    }
}

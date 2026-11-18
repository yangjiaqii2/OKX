package com.example.quant.crypto.provider.dto;

import com.example.quant.crypto.provider.DataQuality;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 相对强弱原始值。{@code windowEnd} 为实际用于 1h/4h/24h 计算的共同收盘终点，便于排查过旧窗口。
 */
public record RelativeStrengthSnapshot(
        String instId,
        BigDecimal coin1h,
        BigDecimal coin4h,
        BigDecimal coin24h,
        BigDecimal btc1h,
        BigDecimal btc4h,
        BigDecimal btc24h,
        BigDecimal eth1h,
        BigDecimal eth4h,
        BigDecimal eth24h,
        BigDecimal rs1h,
        BigDecimal rs4h,
        BigDecimal rs24h,
        DataQuality dataQuality,
        Instant windowEnd
) {
    /** 兼容旧构造：不记录评分终点。 */
    public RelativeStrengthSnapshot(
            String instId,
            BigDecimal coin1h,
            BigDecimal coin4h,
            BigDecimal coin24h,
            BigDecimal btc1h,
            BigDecimal btc4h,
            BigDecimal btc24h,
            BigDecimal eth1h,
            BigDecimal eth4h,
            BigDecimal eth24h,
            BigDecimal rs1h,
            BigDecimal rs4h,
            BigDecimal rs24h,
            DataQuality dataQuality
    ) {
        this(instId, coin1h, coin4h, coin24h, btc1h, btc4h, btc24h, eth1h, eth4h, eth24h,
                rs1h, rs4h, rs24h, dataQuality, null);
    }
}

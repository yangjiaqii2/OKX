package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 成交额单位：OKX SWAP 的 volCcy24h 是基础币数量，不是美元；vol24h 是合约张数。
 */
class QuoteTurnoverUsdtTest {

    @Test
    @DisplayName("volCcy24h 为基础币数量：应按 last 换算名义成交额")
    void volCcy24hIsBaseCurrencyNotUsd() {
        // 隔离复现：币价 100000、成交量 1000 BTC → 名义约 1 亿，不能得到 1000。
        BigDecimal turnover = OkxContractScanner.quoteTurnoverUsdt(
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                new BigDecimal("100000"),
                null);

        assertThat(turnover).isEqualByComparingTo("100000000");
    }

    @Test
    @DisplayName("仅有 vol24h（张数）时必须乘以 ctVal 与 last，不能只用张数×价格")
    void vol24hFallbackRequiresContractValue() {
        BigDecimal withoutCtVal = OkxContractScanner.quoteTurnoverUsdt(
                BigDecimal.ZERO,
                new BigDecimal("1000"),
                new BigDecimal("100"),
                null);
        BigDecimal withCtVal = OkxContractScanner.quoteTurnoverUsdt(
                BigDecimal.ZERO,
                new BigDecimal("1000"),
                new BigDecimal("100"),
                new BigDecimal("0.01"));

        assertThat(withoutCtVal).isNull();
        assertThat(withCtVal).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("成交额不可计算时返回 null，不得伪装成真实零成交额")
    void uncalculableTurnoverIsUnavailableNotZero() {
        assertThat(OkxContractScanner.quoteTurnoverUsdt(null, null, new BigDecimal("100"), null)).isNull();
        assertThat(OkxContractScanner.quoteTurnoverUsdt(BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100"), null)).isNull();
        assertThat(OkxContractScanner.quoteTurnoverUsdt(new BigDecimal("10"), null,
                BigDecimal.ZERO, null)).isNull();
    }
}

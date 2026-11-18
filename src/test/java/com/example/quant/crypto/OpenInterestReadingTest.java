package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenInterestReadingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("优先使用 oiUsd 名义价值，不把 oi 张数当成美元")
    void prefersOiUsdAndNeverTreatsContractCountAsUsd() {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("oi", "999999");
        row.put("oiCcy", "10");
        row.put("oiUsd", "50000");

        OkxContractScanner.OpenInterestReading reading =
                OkxContractScanner.OpenInterestReading.fromOkxRow("BTC-USDT-SWAP", row, new BigDecimal("100"));

        assertThat(reading.coinAmount()).isEqualByComparingTo("10");
        assertThat(reading.usdNotional()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("缺少 oiUsd 时，USDT-SWAP 用 oiCcy×lastPrice 得到名义价值")
    void convertsOiCcyTimesPriceForUsdtSwapWhenOiUsdMissing() {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("oi", "100");
        row.put("oiCcy", "200");

        OkxContractScanner.OpenInterestReading a =
                OkxContractScanner.OpenInterestReading.fromOkxRow("AAA-USDT-SWAP", row, new BigDecimal("50"));
        OkxContractScanner.OpenInterestReading b =
                OkxContractScanner.OpenInterestReading.fromOkxRow("BBB-USDT-SWAP", row, new BigDecimal("50"));

        assertThat(a.usdNotional()).isEqualByComparingTo("10000");
        assertThat(b.usdNotional()).isEqualByComparingTo(a.usdNotional());
        assertThat(a.coinAmount()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("不同币价×币数量但相同名义价值时，usdNotional 一致")
    void differentCoinAmountsSameNotional() {
        ObjectNode low = MAPPER.createObjectNode();
        low.put("oiCcy", "1000");
        ObjectNode high = MAPPER.createObjectNode();
        high.put("oiCcy", "200");

        OkxContractScanner.OpenInterestReading left =
                OkxContractScanner.OpenInterestReading.fromOkxRow("X-USDT-SWAP", low, new BigDecimal("50"));
        OkxContractScanner.OpenInterestReading right =
                OkxContractScanner.OpenInterestReading.fromOkxRow("Y-USDT-SWAP", high, new BigDecimal("250"));

        assertThat(left.usdNotional()).isEqualByComparingTo("50000");
        assertThat(right.usdNotional()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("请求失败语义：空字段与真实零值可区分")
    void blankFieldsAreUnavailableWhileZeroIsRealZero() {
        ObjectNode blank = MAPPER.createObjectNode();
        blank.put("oiCcy", "");
        blank.put("oiUsd", "");
        OkxContractScanner.OpenInterestReading missing =
                OkxContractScanner.OpenInterestReading.fromOkxRow("X-USDT-SWAP", blank, BigDecimal.TEN);
        assertThat(missing.coinAmount()).isNull();
        assertThat(missing.usdNotional()).isNull();

        ObjectNode zero = MAPPER.createObjectNode();
        zero.put("oiCcy", "0");
        zero.put("oiUsd", "0");
        OkxContractScanner.OpenInterestReading realZero =
                OkxContractScanner.OpenInterestReading.fromOkxRow("X-USDT-SWAP", zero, BigDecimal.TEN);
        assertThat(realZero.coinAmount()).isEqualByComparingTo("0");
        assertThat(realZero.usdNotional()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("无法可靠转换时（无价格 / 非 USDT-SWAP）名义价值为 null")
    void unavailableWhenConversionIsNotReliable() {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("oiCcy", "10");
        row.put("oi", "100");

        assertThat(OkxContractScanner.OpenInterestReading
                .fromOkxRow("X-USDT-SWAP", row, null).usdNotional()).isNull();
        assertThat(OkxContractScanner.OpenInterestReading
                .fromOkxRow("X-USD-SWAP", row, new BigDecimal("100")).usdNotional()).isNull();
        assertThat(OkxContractScanner.OpenInterestReading.unavailable().coinAmount()).isNull();
        assertThat(OkxContractScanner.OpenInterestReading.unavailable().usdNotional()).isNull();
    }
}

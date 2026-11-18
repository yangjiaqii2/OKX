package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PositionSideMatcherTest {
    @ParameterizedTest
    @CsvSource({"long,buy,net,5,true", "short,sell,net,-5,true", "long,buy,net,-5,false",
            "short,sell,net,5,false", "long,buy,long,5,true", "short,sell,short,5,true",
            "long,buy,short,5,false", "short,sell,long,5,false", "net,buy,net,5,true",
            "net,sell,net,-5,true", "net,buy,net,-5,false", "net,sell,net,5,false",
            "long,buy,net,0,false", "short,sell,short,0,false"})
    void matchesSignedNetAndHedgePositions(String expected, String side, String actual, String size, boolean matched) {
        PositionSummary position = new PositionSummary("BTC-USDT-SWAP", actual, size, BigDecimal.TEN, BigDecimal.ZERO, "LIVE");
        assertThat(PositionSideMatcher.matches("BTC-USDT-SWAP", expected, side, position)).isEqualTo(matched);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid"})
    void invalidQuantityCannotMeanPositionGone(String size) {
        PositionSummary position = new PositionSummary("BTC-USDT-SWAP", "net", size, BigDecimal.TEN, BigDecimal.ZERO, "LIVE");
        assertThatThrownBy(() -> PositionSideMatcher.matches("BTC-USDT-SWAP", "long", "buy", position))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("POSITION_SIZE_UNAVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown"})
    void invalidDirectionCannotMeanPositionGone(String direction) {
        PositionSummary position = new PositionSummary("BTC-USDT-SWAP", direction, "5", BigDecimal.TEN, BigDecimal.ZERO, "LIVE");
        assertThatThrownBy(() -> PositionSideMatcher.matches("BTC-USDT-SWAP", "long", "buy", position))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("POSITION_DIRECTION_UNAVAILABLE");
    }
}

package com.example.quant.crypto.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TokenSymbolMapperTest {

    @Test
    void mapsStandardSwap() {
        assertThat(TokenSymbolMapper.mapSwapToBase("BTC-USDT-SWAP")).isEqualTo("BTC");
        assertThat(TokenSymbolMapper.mapSwapToBase("ETH-USDT-SWAP")).isEqualTo("ETH");
    }

    @Test
    void mapsMemeMultiplierSwap() {
        // OKX 对 meme 标的常采用 1000x 包装：1000PEPE / 1000BONK / 1000FLOKI
        assertThat(TokenSymbolMapper.mapSwapToBase("1000PEPE-USDT-SWAP")).isEqualTo("1000PEPE");
        assertThat(TokenSymbolMapper.mapSwapToBase("1000BONK-USDT-SWAP")).isEqualTo("1000BONK");
        assertThat(TokenSymbolMapper.mapSwapToBase("1000FLOKI-USDT-SWAP")).isEqualTo("1000FLOKI");
    }

    @Test
    void mapsToCoinGeckoSymbol() {
        assertThat(TokenSymbolMapper.mapToCoinGeckoSymbol("BTC")).isEqualTo("BTC");
        assertThat(TokenSymbolMapper.mapToCoinGeckoSymbol("1000PEPE")).isEqualTo("PEPE");
        assertThat(TokenSymbolMapper.mapToCoinGeckoSymbol("1000BONK")).isEqualTo("BONK");
    }

    @Test
    void mapsSpotAlso() {
        assertThat(TokenSymbolMapper.mapSwapToBase("BTC-USDT")).isEqualTo("BTC");
    }

    @Test
    void invalidFormatThrows() {
        assertThatThrownBy(() -> TokenSymbolMapper.mapSwapToBase("BTC-USD-SWAP-260328"))
                .isInstanceOf(TokenSymbolMapper.SymbolMappingException.class);
        assertThatThrownBy(() -> TokenSymbolMapper.mapSwapToBase(""))
                .isInstanceOf(TokenSymbolMapper.SymbolMappingException.class);
        assertThatThrownBy(() -> TokenSymbolMapper.mapSwapToBase(null))
                .isInstanceOf(TokenSymbolMapper.SymbolMappingException.class);
    }

    @Test
    void isValidMatchesRegex() {
        assertThat(TokenSymbolMapper.isValid("BTC-USDT-SWAP")).isTrue();
        assertThat(TokenSymbolMapper.isValid("1000PEPE-USDT-SWAP")).isTrue();
        assertThat(TokenSymbolMapper.isValid("BTC-USDT")).isTrue();
        assertThat(TokenSymbolMapper.isValid("BTC-USD-SWAP")).isFalse();
        assertThat(TokenSymbolMapper.isValid("")).isFalse();
        assertThat(TokenSymbolMapper.isValid(null)).isFalse();
    }
}

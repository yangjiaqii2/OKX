package com.example.quant.crypto.provider;

import java.util.regex.Pattern;

/**
 * OKX instId → 外部 MarketCap Provider 用的 base symbol 标准化。
 *
 * <p>支持：<ul>
 *   <li>{@code BTC-USDT-SWAP} → {@code BTC}</li>
 *   <li>{@code ETH-USDT-SWAP} → {@code ETH}</li>
 *   <li>{@code 1000PEPE-USDT-SWAP} → {@code 1000PEPE}</li>
 *   <li>{@code 1000BONK-USDT-SWAP} → {@code 1000BONK}</li>
 * </ul>
 *
 * <p>无法可靠映射时抛 {@link SymbolMappingException}。调用方应当捕获并把对应
 * baseSymbol 视为 UNAVAILABLE，不要猜。
 */
public final class TokenSymbolMapper {
    private static final Pattern SWAP = Pattern.compile("^([0-9A-Z]+)-USDT-SWAP$");
    private static final Pattern SPOT = Pattern.compile("^([0-9A-Z]+)-USDT$");

    private TokenSymbolMapper() {
    }

    /**
     * OKX base → CoinGecko {@code symbols=} 查询参数。
     * OKX meme 合约常见 {@code 1000PEPE} 包装，CoinGecko 用 {@code PEPE}。
     */
    public static String mapToCoinGeckoSymbol(String baseSymbol) {
        if (baseSymbol == null || baseSymbol.isBlank()) {
            throw new SymbolMappingException("baseSymbol is null/blank");
        }
        String upper = baseSymbol.toUpperCase();
        if (upper.startsWith("1000") && upper.length() > 4) {
            return upper.substring(4);
        }
        return upper;
    }

    /**
     * 把 instId 转换为 base symbol。失败抛异常，不返回 null。
     */
    public static String mapSwapToBase(String instId) {
        if (instId == null || instId.isBlank()) {
            throw new SymbolMappingException("instId is null/blank");
        }
        var matcher = SWAP.matcher(instId);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        matcher = SPOT.matcher(instId);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        throw new SymbolMappingException("Cannot map instId: " + instId);
    }

    /** 是否符合 SWAP/SPOT 标准格式。 */
    public static boolean isValid(String instId) {
        if (instId == null || instId.isBlank()) {
            return false;
        }
        return SWAP.matcher(instId).matches() || SPOT.matcher(instId).matches();
    }

    public static class SymbolMappingException extends RuntimeException {
        public SymbolMappingException(String message) {
            super(message);
        }
    }
}

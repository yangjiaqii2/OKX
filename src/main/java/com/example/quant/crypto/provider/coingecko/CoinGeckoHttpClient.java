package com.example.quant.crypto.provider.coingecko;

import java.util.List;
import java.util.Optional;

/**
 * CoinGecko HTTP 客户端抽象。
 *
 * <p>设计目的：把"网络是否可达 / 鉴权 / 限流"和"如何解析 / 映射"分离。
 * 生产实现 {@link CoinGeckoClientImpl} 走真实网络；测试 fake 实现返回预录的真实响应快照。
 *
 * <p>普通上游错误返回 {@link Optional#empty()}；本地请求中断或超时可抛出
 * {@link CoinGeckoRequestException}，由 Provider 转换为 TIMEOUT，避免把扫描取消误判成上游限流。
 */
public interface CoinGeckoHttpClient {
    /**
     * 用 {@code /coins/markets?vs_currency=usd&symbols={base}} 拉一条 base 的最新数据。
     * 命中多条时取首条（CoinGecko 同一 symbol 通常只返回一条）。
     * 命中零条 / 异常 / 4xx / 5xx / 超时 → 返回 {@link Optional#empty()}。
     */
    Optional<CoinGeckoMarketData> fetchBySymbol(String baseSymbol);

    /**
     * 批量查询 {@code symbols=a,b,c}。实现可合并为一次 HTTP；默认逐条 {@link #fetchBySymbol}。
     */
    default List<CoinGeckoMarketData> fetchBySymbols(List<String> baseSymbols) {
        if (baseSymbols == null || baseSymbols.isEmpty()) {
            return List.of();
        }
        return baseSymbols.stream()
                .map(this::fetchBySymbol)
                .flatMap(Optional::stream)
                .toList();
    }
}

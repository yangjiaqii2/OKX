package com.example.quant.crypto.provider.coingecko;

import java.util.List;
import java.util.Optional;

/**
 * CoinGecko HTTP 客户端抽象。
 *
 * <p>设计目的：把"网络是否可达 / 鉴权 / 限流"和"如何解析 / 映射"分离。
 * 生产实现 {@link CoinGeckoClientImpl} 走真实网络；测试 fake 实现返回预录的真实响应快照。
 *
 * <p>上游错误由 {@link CoinGeckoRequestException} 区分失败类型；空结果表示无可用的资产匹配。
 */
public interface CoinGeckoHttpClient {
    /**
     * 按 base 解析 CoinGecko ID 后获取市场数据；无法确认匹配时返回空。
     */
    Optional<CoinGeckoMarketData> fetchBySymbol(String baseSymbol);

    /**
     * 批量查多个 base；默认逐条调用 {@link #fetchBySymbol}。
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

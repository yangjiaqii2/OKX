package com.example.quant.crypto.provider;

import com.example.quant.crypto.provider.dto.OpenInterestHistorySnapshot;
import java.time.Duration;

/**
 * Open Interest 历史 Provider 抽象。
 *
 * <p>实现必须捕获所有异常并返回 {@link DataQuality#UNAVAILABLE} /
 * {@link DataQuality#TIMEOUT} 的 {@link ProviderResult}，禁止抛出。
 *
 * <p>调用方应通过 {@link ProviderCache} 控制 TTL，避免每轮扫描都打上游。
 * 默认实现：{@code com.example.quant.crypto.provider.okx.OkxOpenInterestHistoryProvider}。
 *
 * <p>输入参数：{@code instId}（{@code BTC-USDT-SWAP}），与 OKX 原生 API 一致。
 * 输出参数：见 {@link OpenInterestHistorySnapshot}，含 {@code currentOi / oiChange1h /
 * oiChange4h / oiChange24h}，<b>不含 Direction</b>（方向由聚合层用
 * {@code OIDirection.classify(priceChange24h, oiChange24h)} 计算）。
 */
public interface OpenInterestHistoryProvider {
    /**
     * 拉取指定 instId 的 OI 历史快照（24h 窗口，内部切窗为 1h/4h/24h）。
     *
     * @param instId OKX 合约 ID，例如 {@code BTC-USDT-SWAP}
     * @return ProviderResult；data 为 OI 历史快照；失败时 data=null
     */
    ProviderResult<OpenInterestHistorySnapshot> fetch(String instId);

    default ProviderResult<OpenInterestHistorySnapshot> fetch(String instId, Duration timeout) {
        if (Thread.currentThread().isInterrupted()) {
            return ProviderResult.timeout("OI_TIMEOUT: interrupted");
        }
        if (timeout != null && timeout.isZero()) {
            return ProviderResult.timeout("OI_TIMEOUT: deadline exceeded");
        }
        return fetch(instId);
    }
}

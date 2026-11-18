package com.example.quant.crypto.provider;

import com.example.quant.crypto.provider.dto.FundingHistorySnapshot;
import java.time.Duration;

/**
 * Funding Rate 历史 Provider 抽象。
 *
 * <p>实现必须捕获所有异常并返回 {@link DataQuality#UNAVAILABLE} /
 * {@link DataQuality#TIMEOUT} 的 {@link ProviderResult}，禁止抛出。
 *
 * <p>调用方应通过 {@link ProviderCache} 控制 TTL，避免每轮扫描都打上游。
 * 默认实现：{@code com.example.quant.crypto.provider.okx.OkxFundingHistoryProvider}。
 *
 * <p>实现内部完成：<ol>
 *   <li>拉取最近 3 个 funding events（≈ 24h）</li>
 *   <li>计算线性回归斜率（{@code x=[1,2,3]} vs {@code y=fundingRate[i]}）</li>
 *   <li>分类为 {@code HEATING/COOLING/STABLE}（斜率阈值 ±0.001 硬编码）</li>
 *   <li>打 {@code extreme} 标签（{@code |rate24h| >= extremeFundingRate}，
 *       通过构造器注入阈值，默认 0.0030）</li>
 * </ol>
 */
public interface FundingHistoryProvider {
    /**
     * 拉取指定 instId 的 Funding 历史快照。
     *
     * @param instId OKX 合约 ID，例如 {@code BTC-USDT-SWAP}
     * @return ProviderResult；data 为 Funding 历史快照；失败时 data=null
     */
    ProviderResult<FundingHistorySnapshot> fetch(String instId);

    default ProviderResult<FundingHistorySnapshot> fetch(String instId, Duration timeout) {
        if (Thread.currentThread().isInterrupted()) {
            return ProviderResult.timeout("FUNDING_TIMEOUT: interrupted");
        }
        if (timeout != null && timeout.isZero()) {
            return ProviderResult.timeout("FUNDING_TIMEOUT: deadline exceeded");
        }
        return fetch(instId);
    }
}

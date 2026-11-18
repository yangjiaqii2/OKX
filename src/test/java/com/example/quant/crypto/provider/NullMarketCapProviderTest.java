package com.example.quant.crypto.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import org.junit.jupiter.api.Test;

/**
 * 验证 Phase B 关键约束：没有真实 Provider 时必须返回 UNAVAILABLE，让 Universe 硬资格拒绝。
 * <p>禁止：随机市值 / 固定测试市值进入生产 / 根据价格估算市值 / UNKNOWN 当 PASS。
 */
class NullMarketCapProviderTest {

    @Test
    void returnsUnavailabkeForAnySymbol() {
        NullMarketCapProvider provider = new NullMarketCapProvider();
        ProviderResult<MarketCapSnapshot> r1 = provider.fetch("BTC");
        ProviderResult<MarketCapSnapshot> r2 = provider.fetch("1000PEPE");
        assertThat(r1.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(r2.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(r1.data()).isNull();
        assertThat(r1.errorMessage()).contains("configured");
    }

    @Test
    void isNotUsable() {
        NullMarketCapProvider provider = new NullMarketCapProvider();
        assertThat(provider.fetch("ETH").isUsable()).isFalse();
    }

    @Test
    void reasonContainsDiagnosticHint() {
        // 关键诊断价值：reason 必须包含 "Not configured"，运维能直接看到为何被卡
        NullMarketCapProvider provider = new NullMarketCapProvider();
        var r = provider.fetch("DOGE");
        assertThat(r.errorMessage()).contains("configured");
        assertThat(r.errorCode()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void repeatedCallsAreIdempotent() {
        // 没有真实 Provider 时，不应有"首次返回某值、再次返回不同值"的副作用
        NullMarketCapProvider provider = new NullMarketCapProvider();
        var r1 = provider.fetch("SOL");
        var r2 = provider.fetch("SOL");
        assertThat(r1.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(r2.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(r1.data()).isNull();
        assertThat(r2.data()).isNull();
    }
}

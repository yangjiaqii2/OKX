package com.example.quant.crypto.provider.okx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.quant.crypto.FakeOkxRestClient;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.FundingHistorySnapshot;
import com.example.quant.crypto.provider.dto.FundingTrend;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OkxFundingHistoryProvider 单元测试。
 *
 * <p>测试覆盖：<ol>
 *   <li>HEATING 趋势（slope > 0.001）</li>
 *   <li>COOLING 趋势（slope < -0.001）</li>
 *   <li>STABLE 趋势（|slope| <= 0.001）</li>
 *   <li>STABLE 边界：slope = 0.001 恰好</li>
 *   <li>HEATING 临界：slope = 0.0011</li>
 *   <li>EXTREME with HEATING（rate24h 负向极端）</li>
 *   <li>EXTREME with COOLING（rate24h 正向极端）</li>
 *   <li>STABLE + EXTREME（rate24h 极端但 slope = 0）</li>
 *   <li>EXTREME 边界：|rate24h| = extremeFundingRate 恰好</li>
 *   <li>EXTREME 边界下：|rate24h| < extremeFundingRate → extreme = false</li>
 *   <li>单事件：dataQuality=STALE，slope=0</li>
 *   <li>双事件：dataQuality=STALE，slope 仍可计算</li>
 *   <li>空 data 数组 → UNAVAILABLE</li>
 *   <li>publicGet 抛错 → UNAVAILABLE</li>
 *   <li>OKX API code 非 0 → UNAVAILABLE</li>
 *   <li>ProviderResult 字段正确</li>
 * </ol>
 */
class OkxFundingHistoryProviderTest {

    private static final String PATH_FUNDING = "/api/v5/public/funding-rate-history?instId=BTC-USDT-SWAP&limit=3";
    private static final String INST_ID = "BTC-USDT-SWAP";

    /**
     * 生成 3 个 funding events 的 JSON。{@code rates} 长度 = 3，{@code timestampsMillis} 长度 = 3。
     * 顺序：时间正序（24h 前 → 最新）。
     */
    private static String fundingJson(List<BigDecimal> rates, long baseTimeMillis) {
        StringBuilder sb = new StringBuilder("{\"code\":\"0\",\"data\":[");
        for (int i = 0; i < rates.size(); i++) {
            long ts = baseTimeMillis - (rates.size() - 1 - i) * 8 * 3_600_000L;
            if (i > 0) sb.append(",");
            sb.append("{\"instId\":\"").append(INST_ID).append("\",")
                    .append("\"fundingRate\":\"").append(rates.get(i).toPlainString()).append("\",")
                    .append("\"fundingTime\":\"").append(ts).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    @DisplayName("HEATING: rates=[-0.002, 0.000, 0.002] → slope=0.002 → HEATING")
    void heating() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("-0.002"), new BigDecimal("0.000"), new BigDecimal("0.002")),
                now));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertEquals(DataQuality.FRESH, result.dataQuality());
        assertEquals(FundingTrend.HEATING, snap.trend());
        assertEquals(0.002, snap.slope(), 0.0001);
        assertFalse(snap.extreme(), "rate24h=-0.002 abs=0.002 < 0.0030 不极端");
    }

    @Test
    @DisplayName("COOLING: rates=[0.002, 0.000, -0.002] → slope=-0.002 → COOLING")
    void cooling() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("0.002"), new BigDecimal("0.000"), new BigDecimal("-0.002")),
                now));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertEquals(FundingTrend.COOLING, snap.trend());
        assertEquals(-0.002, snap.slope(), 0.0001);
    }

    @Test
    @DisplayName("STABLE: rates=[0.0001, 0.0001, 0.0001] → slope=0 → STABLE")
    void stable() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("0.0001"), new BigDecimal("0.0001"), new BigDecimal("0.0001")),
                now));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertEquals(FundingTrend.STABLE, snap.trend());
        assertEquals(0.0, snap.slope(), 1e-9);
    }

    @Test
    @DisplayName("STABLE 边界: slope=0.001 恰好 → STABLE（> 严格）")
    void stableBoundary() {
        long now = System.currentTimeMillis();
        // rates=[-0.001, 0.000, 0.001] → slope = 0.001 → STABLE
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("-0.001"), new BigDecimal("0.000"), new BigDecimal("0.001")),
                now));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertEquals(0.001, snap.slope(), 0.0001);
        assertEquals(FundingTrend.STABLE, snap.trend(), "slope=0.001 应为 STABLE（严格 >）");
    }

    @Test
    @DisplayName("HEATING 临界: rates=[-0.0011, 0, 0.0011] → slope=0.0011 → HEATING")
    void heatingJustOver() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("-0.0011"), new BigDecimal("0.000"), new BigDecimal("0.0011")),
                now));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertEquals(0.0011, snap.slope(), 0.0001);
        assertEquals(FundingTrend.HEATING, snap.trend());
    }

    @Test
    @DisplayName("EXTREME with HEATING: rate24h=-0.005 (abs >= 0.0030) + HEATING slope")
    void extremeHeating() {
        long now = System.currentTimeMillis();
        // rates=[-0.005, 0, 0.005] → slope=0.005 → HEATING, rate24h=-0.005 abs=0.005 >= 0.0030 → extreme
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("-0.005"), new BigDecimal("0.000"), new BigDecimal("0.005")),
                now));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertEquals(FundingTrend.HEATING, snap.trend());
        assertTrue(snap.extreme(), "rate24h abs >= 0.0030 应 extreme=true");
    }

    @Test
    @DisplayName("EXTREME with COOLING: rate24h=0.005 + COOLING slope")
    void extremeCooling() {
        long now = System.currentTimeMillis();
        // rates=[0.005, 0, -0.005] → slope=-0.005 → COOLING, rate24h=0.005 extreme
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("0.005"), new BigDecimal("0.000"), new BigDecimal("-0.005")),
                now));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertEquals(FundingTrend.COOLING, snap.trend());
        assertTrue(snap.extreme());
    }

    @Test
    @DisplayName("STABLE + EXTREME: rate24h=0.005 但 slope=0 → STABLE 但 extreme=true")
    void stableButExtreme() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("0.005"), new BigDecimal("0.005"), new BigDecimal("0.005")),
                now));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertEquals(FundingTrend.STABLE, snap.trend());
        assertTrue(snap.extreme(), "rate24h=0.005 >= 0.0030 应 extreme=true");
    }

    @Test
    @DisplayName("EXTREME 边界: |rate24h|=0.0030 恰好 → extreme=true（>= 包含）")
    void extremeThresholdExactly() {
        long now = System.currentTimeMillis();
        // rates=[0.0030, 0.0030, 0.0030] → slope=0 STABLE, rate24h=0.0030 abs=0.0030 恰好
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("0.0030"), new BigDecimal("0.0030"), new BigDecimal("0.0030")),
                now));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertTrue(snap.extreme(), "rate24h abs=0.0030 恰好等于阈值应 extreme=true");
    }

    @Test
    @DisplayName("EXTREME 边界下: |rate24h|=0.0029 → extreme=false")
    void belowExtremeThreshold() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("0.0029"), new BigDecimal("0.0029"), new BigDecimal("0.0029")),
                now));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertFalse(snap.extreme(), "rate24h abs=0.0029 < 0.0030 应 extreme=false");
    }

    @Test
    @DisplayName("单事件: dataQuality=STALE, slope=0")
    void singleEvent() {
        long now = System.currentTimeMillis();
        String json = "{\"code\":\"0\",\"data\":[{\"instId\":\"" + INST_ID
                + "\",\"fundingRate\":\"0.0001\",\"fundingTime\":\"" + now + "\"}]}";
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, json);
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertEquals(DataQuality.FRESH, result.dataQuality(), "OKX 返回则 ProviderResult=FRESH");
        assertEquals(FundingHistorySnapshot.DataQuality.STALE, snap.dataQuality(),
                "snapshot 内部 dataQuality=STALE（不足 3 events）");
        assertEquals(0.0, snap.slope(), 1e-9, "单事件 slope=0");
        assertNull(snap.rate24h(), "单事件 rate24h 应为 null");
    }

    @Test
    @DisplayName("双事件: dataQuality=STALE, slope 从 2 点计算")
    void twoEvents() {
        long now = System.currentTimeMillis();
        long t1 = now - 8 * 3_600_000L;
        long t2 = now;
        String json = "{\"code\":\"0\",\"data\":["
                + "{\"instId\":\"" + INST_ID + "\",\"fundingRate\":\"0.0001\",\"fundingTime\":\"" + t1 + "\"},"
                + "{\"instId\":\"" + INST_ID + "\",\"fundingRate\":\"0.0003\",\"fundingTime\":\"" + t2 + "\"}"
                + "]}";
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, json);
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertEquals(FundingHistorySnapshot.DataQuality.STALE, snap.dataQuality());
        // 2 points: x=[1,2], y=[0.0001, 0.0003], slope = 0.0002
        assertEquals(0.0002, snap.slope(), 1e-6);
        assertNull(snap.rate24h(), "双事件 rate24h 应为 null（24h 前无数据）");
    }

    @Test
    @DisplayName("空 data 数组 → UNAVAILABLE")
    void emptyData() {
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, "{\"code\":\"0\",\"data\":[]}");
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        assertEquals(DataQuality.UNAVAILABLE, result.dataQuality());
        assertNull(result.data());
    }

    @Test
    @DisplayName("publicGet 抛错 → UNAVAILABLE")
    void exceptionInPublicGet() {
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.throwOnPublicGet(new RuntimeException("connection reset"));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        assertEquals(DataQuality.UNAVAILABLE, result.dataQuality());
        assertTrue(result.errorMessage().contains("connection reset"));
    }

    @Test
    @DisplayName("OKX API code 非 0 → UNAVAILABLE")
    void nonZeroApiCode() {
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, "{\"code\":\"50011\",\"msg\":\"Too Many Requests\",\"data\":[]}");
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        assertEquals(DataQuality.UNAVAILABLE, result.dataQuality());
        assertTrue(result.errorMessage().contains("Too Many Requests"));
    }

    @Test
    @DisplayName("ProviderResult 字段: source / fetchedAt / FRESH 时 errorCode null")
    void providerResultFields() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("0.0001"), new BigDecimal("0.0001"), new BigDecimal("0.0001")),
                now));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        assertEquals("okx", result.source());
        assertNotNull(result.fetchedAt());
        assertNull(result.errorCode());
        assertNull(result.errorMessage());
        assertTrue(result.isUsable());
    }

    @Test
    @DisplayName("自定义 extremeFundingRate: 0.0010 → 0.0015 已极端")
    void customExtremeRate() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_FUNDING, fundingJson(
                List.of(new BigDecimal("0.0015"), new BigDecimal("0.0015"), new BigDecimal("0.0015")),
                now));
        // 阈值降到 0.0010：0.0015 >= 0.0010 → extreme
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake, new BigDecimal("0.0010"));

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID);
        FundingHistorySnapshot snap = result.data();
        assertTrue(snap.extreme(), "rate24h=0.0015 >= 0.0010(自定义阈值) 应 extreme=true");
    }

    @Test
    @DisplayName("request timeout → FUNDING_TIMEOUT")
    void requestTimeout() {
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.throwOnPublicGet(new RuntimeException("request timed out"));
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch(INST_ID, java.time.Duration.ofMillis(100));

        assertEquals(DataQuality.TIMEOUT, result.dataQuality());
        assertTrue(result.errorMessage().contains("FUNDING_TIMEOUT"));
    }

    @Test
    @DisplayName("blank instId → UNAVAILABLE")
    void blankInstId() {
        FakeOkxRestClient fake = new FakeOkxRestClient();
        OkxFundingHistoryProvider provider = new OkxFundingHistoryProvider("okx", fake);

        ProviderResult<FundingHistorySnapshot> result = provider.fetch("");
        assertEquals(DataQuality.UNAVAILABLE, result.dataQuality());
        assertTrue(result.errorMessage().contains("blank"));
    }
}

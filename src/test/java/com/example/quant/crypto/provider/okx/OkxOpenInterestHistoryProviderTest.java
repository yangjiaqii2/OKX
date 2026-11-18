package com.example.quant.crypto.provider.okx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.quant.crypto.FakeOkxRestClient;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.OpenInterestHistorySnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OkxOpenInterestHistoryProvider 单元测试。
 *
 * <p>测试覆盖：<ol>
 *   <li>happy path 上升趋势（LEADING 数据准备）</li>
 *   <li>happy path 下降趋势（FOLLOWING_WEAK 数据准备）</li>
 *   <li>flat series（无变化）</li>
 *   <li>单数据点（变化率全 null）</li>
 *   <li>少量数据点（变化率部分 null）</li>
 *   <li>空 data 数组 → UNAVAILABLE</li>
 *   <li>异常（publicGet 抛错）→ UNAVAILABLE</li>
 *   <li>OKX API code 非 0 → UNAVAILABLE</li>
 *   <li>24h 单次拉取：1 次 publicGet 调用，不是 3 次</li>
 *   <li>ProviderResult 字段正确（source / errorCode / errorMessage）</li>
 *   <li>desc 数据顺序：OKX 返回 desc，Provider 内部 sort</li>
 *   <li>边界 0% 变化（最新 == 1h 前）</li>
 *   <li>baseline 0（除零保护）</li>
 *   <li>JSON 部分字段缺失：跳过坏行，整体仍可用</li>
 * </ol>
 */
class OkxOpenInterestHistoryProviderTest {

    private static final String PATH_OI = "/api/v5/rubik/stat/contracts/open-interest-history?instId=BTC-USDT-SWAP&period=1H&limit=25";
    private static final String INST_ID = "BTC-USDT-SWAP";

    /**
     * 生成 25 个 1h 采样点（覆盖完整 24h 窗口）。{@code oiStart} = 24h 前值，{@code oiEnd} = 最新值，
     * 中间按线性插值。返回 OKX 格式的 JSON。
     */
    private static String oiSeriesJson(BigDecimal oiStart, BigDecimal oiEnd, long nowMillis) {
        StringBuilder sb = new StringBuilder("{\"code\":\"0\",\"data\":[");
        int n = 25;
        for (int i = 0; i < n; i++) {
            long ts = nowMillis - (n - 1 - i) * 3_600_000L;
            BigDecimal oi = oiStart.add(oiEnd.subtract(oiStart)
                    .multiply(BigDecimal.valueOf(i))
                    .divide(BigDecimal.valueOf(n - 1), 6, RoundingMode.HALF_UP));
            if (i > 0) sb.append(",");
            sb.append("{\"ts\":\"").append(ts).append("\",\"oi\":\"").append(oi.toPlainString()).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 生成 desc 顺序（OKX 原生返回顺序）。 */
    private static String oiSeriesDescJson(BigDecimal oiStart, BigDecimal oiEnd, long nowMillis) {
        StringBuilder sb = new StringBuilder("{\"code\":\"0\",\"data\":[");
        int n = 25;
        for (int i = 0; i < n; i++) {
            long ts = nowMillis - i * 3_600_000L;
            BigDecimal oi = oiStart.add(oiEnd.subtract(oiStart)
                    .multiply(BigDecimal.valueOf(n - 1 - i))
                    .divide(BigDecimal.valueOf(n - 1), 6, RoundingMode.HALF_UP));
            if (i > 0) sb.append(",");
            sb.append("{\"ts\":\"").append(ts).append("\",\"oi\":\"").append(oi.toPlainString()).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 生成 OKX 官方数组格式：[ts, oi(张), oiCcy(币), oiUsd]。 */
    private static String officialArraySeriesJson(BigDecimal oiCcyStart, BigDecimal oiCcyEnd,
                                                  long nowMillis) {
        StringBuilder sb = new StringBuilder("{\"code\":\"0\",\"data\":[");
        int n = 25;
        for (int i = 0; i < n; i++) {
            long ts = nowMillis - (n - 1L - i) * 3_600_000L;
            BigDecimal oiCcy = oiCcyStart.add(oiCcyEnd.subtract(oiCcyStart)
                    .multiply(BigDecimal.valueOf(i))
                    .divide(BigDecimal.valueOf(n - 1), 6, RoundingMode.HALF_UP));
            BigDecimal contracts = oiCcy.multiply(BigDecimal.TEN);
            BigDecimal oiUsd = oiCcy.multiply(new BigDecimal("50000"));
            if (i > 0) sb.append(",");
            sb.append("[\"").append(ts).append("\",\"")
                    .append(contracts.toPlainString()).append("\",\"")
                    .append(oiCcy.toPlainString()).append("\",\"")
                    .append(oiUsd.toPlainString()).append("\"]");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    @DisplayName("OKX 官方数组格式：使用 oiCcy 计算 1h/4h/24h OI 变化")
    void officialArrayRowsUseOiCcyAndProduceChanges() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI,
                officialArraySeriesJson(new BigDecimal("1000"), new BigDecimal("1500"), now));
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);

        assertEquals(DataQuality.FRESH, result.dataQuality());
        assertNotNull(result.data());
        assertEquals(0, result.data().currentOi().compareTo(new BigDecimal("1500.000000")));
        assertEquals(0, result.data().oiChange24h().compareTo(new BigDecimal("0.500000")));
        assertNotNull(result.data().oiChange1h());
        assertNotNull(result.data().oiChange4h());
    }

    @Test
    @DisplayName("happy path: 24h 上升 → currentOi 正确，oiChange24h > 0")
    void happyPathRising() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, oiSeriesJson(new BigDecimal("1000"), new BigDecimal("1500"), now));
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);

        assertEquals(DataQuality.FRESH, result.dataQuality());
        assertEquals("okx", result.source());
        OpenInterestHistorySnapshot snap = result.data();
        assertNotNull(snap);
        assertEquals(INST_ID, snap.instId());
        // currentOi 应是最新值 = 1500
        assertEquals(0, snap.currentOi().compareTo(new BigDecimal("1500")),
                "currentOi 应为 1500, got=" + snap.currentOi());
        // oiChange24h 应为 (1500-1000)/1000 = 0.5
        assertEquals(0, snap.oiChange24h().compareTo(new BigDecimal("0.500000")),
                "oiChange24h 应为 0.5, got=" + snap.oiChange24h());
        // oiChange1h 和 oiChange4h 应为正（最近一直在涨）
        assertTrue(snap.oiChange1h().signum() > 0, "oiChange1h 应为正, got=" + snap.oiChange1h());
        assertTrue(snap.oiChange4h().signum() > 0, "oiChange4h 应为正, got=" + snap.oiChange4h());
    }

    @Test
    @DisplayName("happy path: 24h 下降 → oiChange24h < 0（FOLLOWING_WEAK 数据准备）")
    void happyPathFalling() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, oiSeriesJson(new BigDecimal("1500"), new BigDecimal("1000"), now));
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        OpenInterestHistorySnapshot snap = result.data();
        assertTrue(snap.oiChange24h().signum() < 0, "oiChange24h 应为负, got=" + snap.oiChange24h());
        // (1000 - 1500) / 1500 = -0.333333
        assertEquals(0, snap.oiChange24h().compareTo(new BigDecimal("-0.333333")),
                "oiChange24h 应为 -0.333333, got=" + snap.oiChange24h());
    }

    @Test
    @DisplayName("flat series: 24h 全部相同 → 变化率全为 0")
    void flatSeries() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, oiSeriesJson(new BigDecimal("1000"), new BigDecimal("1000"), now));
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        OpenInterestHistorySnapshot snap = result.data();
        assertEquals(0, snap.oiChange1h().signum(), "oiChange1h 应为 0");
        assertEquals(0, snap.oiChange4h().signum(), "oiChange4h 应为 0");
        assertEquals(0, snap.oiChange24h().signum(), "oiChange24h 应为 0");
    }

    @Test
    @DisplayName("单数据点：所有变化率 null，但 currentOi 仍正确")
    void singleDataPoint() {
        long now = System.currentTimeMillis();
        String json = "{\"code\":\"0\",\"data\":[{\"ts\":\"" + now + "\",\"oi\":\"5000\"}]}";
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, json);
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        OpenInterestHistorySnapshot snap = result.data();
        assertNotNull(snap);
        assertEquals(0, snap.currentOi().compareTo(new BigDecimal("5000")));
        assertNull(snap.oiChange1h(), "单点 oiChange1h 应为 null");
        assertNull(snap.oiChange4h(), "单点 oiChange4h 应为 null");
        assertNull(snap.oiChange24h(), "单点 oiChange24h 应为 null");
    }

    @Test
    @DisplayName("3 数据点：oiChange1h 正常，oiChange4h/24h 为 null（数据不足）")
    void threeDataPoints() {
        long now = System.currentTimeMillis();
        // 3 个点：now-2h, now-1h, now
        String json = "{\"code\":\"0\",\"data\":["
                + "{\"ts\":\"" + (now - 2 * 3_600_000L) + "\",\"oi\":\"1000\"},"
                + "{\"ts\":\"" + (now - 1 * 3_600_000L) + "\",\"oi\":\"1100\"},"
                + "{\"ts\":\"" + now + "\",\"oi\":\"1200\"}"
                + "]}";
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, json);
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        OpenInterestHistorySnapshot snap = result.data();
        assertEquals(0, snap.currentOi().compareTo(new BigDecimal("1200")));
        // oiChange1h: (1200-1100)/1100 = 0.090909
        assertEquals(0, snap.oiChange1h().compareTo(new BigDecimal("0.090909")),
                "oiChange1h 应为 0.090909, got=" + snap.oiChange1h());
        // 数据只覆盖 2h，oiChange4h/24h 缺失
        assertNull(snap.oiChange4h(), "数据不足 4h 应为 null");
        assertNull(snap.oiChange24h(), "数据不足 24h 应为 null");
    }

    @Test
    @DisplayName("空 data 数组 → UNAVAILABLE")
    void emptyDataArray() {
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, "{\"code\":\"0\",\"data\":[]}");
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        assertEquals(DataQuality.UNAVAILABLE, result.dataQuality());
        assertNull(result.data());
        assertNotNull(result.errorMessage());
    }

    @Test
    @DisplayName("publicGet 抛错 → UNAVAILABLE（不传播异常）")
    void exceptionInPublicGet() {
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.throwOnPublicGet(new RuntimeException("network down"));
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        assertEquals(DataQuality.UNAVAILABLE, result.dataQuality());
        assertNull(result.data());
        assertTrue(result.errorMessage().contains("network down"),
                "errorMessage 应包含原因, got=" + result.errorMessage());
    }

    @Test
    @DisplayName("OKX API code 非 0 → UNAVAILABLE")
    void nonZeroApiCode() {
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, "{\"code\":\"50011\",\"msg\":\"Too Many Requests\",\"data\":[]}");
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        assertEquals(DataQuality.UNAVAILABLE, result.dataQuality());
        assertTrue(result.errorMessage().contains("Too Many Requests"));
    }

    @Test
    @DisplayName("24h 单次拉取：1 次 publicGet，3 个变化率都从同一响应算")
    void singleHttpCallForAllChanges() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, oiSeriesJson(new BigDecimal("1000"), new BigDecimal("1200"), now));
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        provider.fetch(INST_ID);
        List<String> calls = fake.getCalledPaths();
        // 必须恰好 1 次 HTTP 调用，不是 3 次（1h + 4h + 24h 各自拉一次）
        assertEquals(1, calls.size(), "应只调用 publicGet 1 次, got=" + calls.size());
    }

    @Test
    @DisplayName("ProviderResult 字段：source / fetchedAt / errorCode / errorMessage")
    void providerResultFields() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, oiSeriesJson(new BigDecimal("1000"), new BigDecimal("1500"), now));
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        assertEquals("okx", result.source());
        assertNotNull(result.fetchedAt());
        assertNull(result.errorCode(), "FRESH 时 errorCode 应为 null");
        assertNull(result.errorMessage(), "FRESH 时 errorMessage 应为 null");
        assertTrue(result.isUsable(), "FRESH 应 isUsable=true");
    }

    @Test
    @DisplayName("desc 数据顺序：OKX 返回 desc，Provider 内部 sort 后正确")
    void descDataOrder() {
        long now = System.currentTimeMillis();
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, oiSeriesDescJson(new BigDecimal("1000"), new BigDecimal("1500"), now));
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        OpenInterestHistorySnapshot snap = result.data();
        // 即使输入是 desc，latest = 1500，24h 前 = 1000，change = 0.5
        assertEquals(0, snap.currentOi().compareTo(new BigDecimal("1500")));
        assertEquals(0, snap.oiChange24h().compareTo(new BigDecimal("0.500000")));
    }

    @Test
    @DisplayName("边界 0% 变化：1h 前 == latest，oiChange1h = 0")
    void zeroChangeRate() {
        long now = System.currentTimeMillis();
        // 构造 2 个点：now-1h = 1000, now = 1000
        String json = "{\"code\":\"0\",\"data\":["
                + "{\"ts\":\"" + (now - 3_600_000L) + "\",\"oi\":\"1000\"},"
                + "{\"ts\":\"" + now + "\",\"oi\":\"1000\"}"
                + "]}";
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, json);
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        OpenInterestHistorySnapshot snap = result.data();
        assertEquals(0, snap.oiChange1h().signum(), "1h 无变化应为 0");
    }

    @Test
    @DisplayName("baseline 0（除零保护）：1h 前 oi=0 → oiChange1h=null，不报异常")
    void baselineZeroProtection() {
        long now = System.currentTimeMillis();
        String json = "{\"code\":\"0\",\"data\":["
                + "{\"ts\":\"" + (now - 3_600_000L) + "\",\"oi\":\"0\"},"
                + "{\"ts\":\"" + now + "\",\"oi\":\"500\"}"
                + "]}";
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, json);
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        OpenInterestHistorySnapshot snap = result.data();
        assertEquals(0, snap.currentOi().compareTo(new BigDecimal("500")));
        assertNull(snap.oiChange1h(), "baseline=0 时 oiChange1h 应为 null（除零保护）");
    }

    @Test
    @DisplayName("JSON 部分字段缺失：跳过坏行，正常行仍可解析")
    void malformedEntriesAreSkipped() {
        long now = System.currentTimeMillis();
        // 混合：好行 + 缺失 ts + 缺失 oi + 非法数字
        String json = "{\"code\":\"0\",\"data\":["
                + "{\"ts\":\"" + (now - 3_600_000L) + "\",\"oi\":\"1000\"},"
                + "{\"oi\":\"1100\"},"  // 缺 ts
                + "{\"ts\":\"" + (now - 2 * 3_600_000L) + "\"},"  // 缺 oi
                + "{\"ts\":\"not-a-number\",\"oi\":\"1200\"},"  // 非法 ts
                + "{\"ts\":\"" + now + "\",\"oi\":\"1300\"}"
                + "]}";
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.respondToPath(PATH_OI, json);
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID);
        // 2 个好行（1000 和 1300），currentOi=1300, oiChange1h=(1300-1000)/1000=0.3
        OpenInterestHistorySnapshot snap = result.data();
        assertNotNull(snap);
        assertEquals(0, snap.currentOi().compareTo(new BigDecimal("1300")));
        assertEquals(0, snap.oiChange1h().compareTo(new BigDecimal("0.300000")));
    }

    @Test
    @DisplayName("request timeout → OI_TIMEOUT")
    void requestTimeout() {
        FakeOkxRestClient fake = new FakeOkxRestClient();
        fake.throwOnPublicGet(new RuntimeException("request timed out"));
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch(INST_ID, java.time.Duration.ofMillis(100));

        assertEquals(DataQuality.TIMEOUT, result.dataQuality());
        assertTrue(result.errorMessage().contains("OI_TIMEOUT"));
    }

    @Test
    @DisplayName("blank instId → UNAVAILABLE")
    void blankInstId() {
        FakeOkxRestClient fake = new FakeOkxRestClient();
        OkxOpenInterestHistoryProvider provider = new OkxOpenInterestHistoryProvider("okx", fake);

        ProviderResult<OpenInterestHistorySnapshot> result = provider.fetch("");
        assertEquals(DataQuality.UNAVAILABLE, result.dataQuality());
        assertTrue(result.errorMessage().contains("blank"));
    }
}

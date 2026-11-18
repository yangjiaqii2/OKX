package com.example.quant.crypto.provider.okx;

import com.example.quant.crypto.OkxRestClient;
import com.example.quant.crypto.provider.OpenInterestHistoryProvider;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.OpenInterestHistorySnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OKX Open Interest History Provider —— 真实实现。
 *
 * <p>调用 {@code GET /api/v5/rubik/stat/contracts/open-interest-history?instId=...&period=1H&limit=24}，
 * 一次性拉取最近 24 个 1h OI 采样点（共 24h 窗口），内部切窗为 1h/4h/24h 三个变化率。
 *
 * <p><b>不做</b>多次 HTTP 调用。Provider 内部仅做：<ol>
 *   <li>单次 HTTP GET</li>
 *   <li>解析 OKX 官方数组 {@code [ts, oi, oiCcy, oiUsd]}（按 ts 升序），使用 {@code oiCcy}</li>
 *   <li>计算 latest vs t-1h / t-4h / t-24h 的变化率</li>
 *   <li>封装为 {@link OpenInterestHistorySnapshot}</li>
 * </ol>
 *
 * <p>本类不持 cache：缓存、TTL、DataQuality 由外层
 * {@link com.example.quant.crypto.provider.ProviderCache} 负责。
 *
 * <p>Provider <b>不计算 OIDirection</b>，避免耦合价格。Direction 由聚合层
 * （{@code OkxContractScanner.scanV2}）用 {@code OIDirection.classify(priceChange24h, oiChange24h)} 计算。
 */
public final class OkxOpenInterestHistoryProvider implements OpenInterestHistoryProvider {

    private static final Logger log = LoggerFactory.getLogger(OkxOpenInterestHistoryProvider.class);

    /** OKX Rubik API 要求 native period（{@code 1H}），小写 {@code 1h} 会返回 51000。 */
    private static final String OKX_PERIOD = "1H";
    /** 拉取 25 个 1h 采样点（latest + 24h ago）。 */
    private static final int OKX_LIMIT = 25;
    /** 变化率小数位（百分比，0.0001 = 0.01%）。 */
    private static final int SCALE = 6;

    private final String providerName;
    private final OkxRestClient restClient;

    public OkxOpenInterestHistoryProvider(String providerName, OkxRestClient restClient) {
        this.providerName = providerName == null || providerName.isBlank() ? "okx" : providerName;
        this.restClient = restClient;
    }

    @Override
    public ProviderResult<OpenInterestHistorySnapshot> fetch(String instId) {
        return fetch(instId, null);
    }

    @Override
    public ProviderResult<OpenInterestHistorySnapshot> fetch(String instId, Duration timeout) {
        if (instId == null || instId.isBlank()) {
            return ProviderResult.unavailable("OI_PROVIDER_UNAVAILABLE: blank instId");
        }
        if (restClient == null) {
            return ProviderResult.unavailable("OI_PROVIDER_UNAVAILABLE: no rest client");
        }
        if (timeout != null && timeout.isZero()) {
            return ProviderResult.timeout("OI_TIMEOUT: deadline exceeded");
        }
        try {
            String path = "/api/v5/rubik/stat/contracts/open-interest-history"
                    + "?instId=" + instId
                    + "&period=" + OKX_PERIOD
                    + "&limit=" + OKX_LIMIT;
            JsonNode root = timeout == null
                    ? restClient.publicGet(path)
                    : restClient.publicGet(path, timeout);
            if (root == null) {
                return ProviderResult.unavailable("OI_EMPTY_RESPONSE: empty response");
            }
            String code = root.path("code").asText("1");
            if (!"0".equals(code)) {
                String msg = root.path("msg").asText("unknown");
                return ProviderResult.unavailable("OI_PROVIDER_UNAVAILABLE: api code=" + code + " msg=" + msg);
            }
            JsonNode data = root.path("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                return ProviderResult.unavailable("OI_EMPTY_RESPONSE: empty data array");
            }
            List<OiPoint> points = parsePoints(data);
            if (points.isEmpty()) {
                return ProviderResult.unavailable("OI_EMPTY_RESPONSE: no parseable points");
            }
            points.sort((a, b) -> Long.compare(a.tsMillis, b.tsMillis));
            OiPoint latest = points.get(points.size() - 1);
            BigDecimal currentOi = latest.oi;
            BigDecimal change1h = computeChange(points, latest, 1);
            BigDecimal change4h = computeChange(points, latest, 4);
            BigDecimal change24h = computeChange(points, latest, 24);
            if (points.size() == 1) {
                log.debug("OI_FIRST_OBSERVATION instId={} currentOi={}", instId, currentOi);
            } else if (change4h == null || change24h == null) {
                log.debug("OI_INSUFFICIENT_HISTORY instId={} points={}", instId, points.size());
            }
            Instant snapshotAt = Instant.ofEpochMilli(latest.tsMillis);
            OpenInterestHistorySnapshot snap = new OpenInterestHistorySnapshot(
                    instId,
                    currentOi,
                    change1h,
                    change4h,
                    change24h,
                    snapshotAt,
                    Instant.now(),
                    providerName
            );
            return ProviderResult.fresh(snap, providerName);
        } catch (Exception ex) {
            if (isTimeout(ex)) {
                log.warn("OI_TIMEOUT instId={} message={}", instId, ex.toString());
                return ProviderResult.timeout("OI_TIMEOUT: request timed out");
            }
            log.warn("OI_PROVIDER_UNAVAILABLE instId={} message={}", instId, ex.toString());
            String msg = ex.getMessage();
            return ProviderResult.unavailable("OI_PROVIDER_UNAVAILABLE: " + ex.getClass().getSimpleName()
                    + (msg == null || msg.isBlank() ? "" : " (" + msg + ")"));
        }
    }

    /** 计算 latest vs N 小时前的变化率。N 小时前的点不存在 → null。 */
    private static BigDecimal computeChange(List<OiPoint> points, OiPoint latest, int hoursBack) {
        if (points.size() < 2) {
            return null;
        }
        long targetTs = latest.tsMillis - hoursBack * 3_600_000L;
        OiPoint baseline = null;
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).tsMillis <= targetTs) {
                baseline = points.get(i);
                break;
            }
        }
        if (baseline == null) {
            return null;
        }
        if (baseline.oi.signum() == 0) {
            return null;
        }
        return latest.oi.subtract(baseline.oi)
                .divide(baseline.oi, SCALE, RoundingMode.HALF_UP);
    }

    private static boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static List<OiPoint> parsePoints(JsonNode data) {
        List<OiPoint> out = new ArrayList<>();
        for (JsonNode entry : data) {
            String tsStr;
            String oiStr;
            if (entry.isArray()) {
                tsStr = entry.size() > 0 ? textOrNull(entry.get(0)) : null;
                oiStr = entry.size() > 2 ? textOrNull(entry.get(2)) : null;
            } else {
                tsStr = entry.path("ts").asText(null);
                oiStr = entry.path("oiCcy").asText(null);
                if (oiStr == null) {
                    oiStr = entry.path("oi").asText(null);
                }
            }
            if (tsStr == null || oiStr == null) {
                continue;
            }
            try {
                long ts = Long.parseLong(tsStr);
                BigDecimal oi = new BigDecimal(oiStr);
                out.add(new OiPoint(ts, oi));
            } catch (NumberFormatException ex) {
                // 单点解析失败：跳过，不影响整体
            }
        }
        return out;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static final class OiPoint {
        final long tsMillis;
        final BigDecimal oi;
        OiPoint(long tsMillis, BigDecimal oi) {
            this.tsMillis = tsMillis;
            this.oi = oi;
        }
    }
}

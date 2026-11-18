package com.example.quant.crypto.provider.okx;

import com.example.quant.crypto.OkxRestClient;
import com.example.quant.crypto.provider.FundingHistoryProvider;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.FundingHistorySnapshot;
import com.example.quant.crypto.provider.dto.FundingTrend;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OKX Funding Rate History Provider —— 真实实现。
 *
 * <p>调用 {@code GET /api/v5/public/funding-rate-history?instId=...&limit=3}，
 * 一次性拉取最近 3 个 funding events（≈ 24h，OKX SWAP funding 间隔 8h）。
 */
public final class OkxFundingHistoryProvider implements FundingHistoryProvider {

    private static final Logger log = LoggerFactory.getLogger(OkxFundingHistoryProvider.class);

    private static final int TARGET_EVENTS = 3;
    private static final double SLOPE_THRESHOLD = 0.001;
    private static final String DEFAULT_EXTREME_FUNDING_RATE = "0.0030";

    private final String providerName;
    private final OkxRestClient restClient;
    private final BigDecimal extremeFundingRate;

    public OkxFundingHistoryProvider(String providerName, OkxRestClient restClient) {
        this(providerName, restClient, new BigDecimal(DEFAULT_EXTREME_FUNDING_RATE));
    }

    public OkxFundingHistoryProvider(String providerName, OkxRestClient restClient, BigDecimal extremeFundingRate) {
        this.providerName = providerName == null || providerName.isBlank() ? "okx" : providerName;
        this.restClient = restClient;
        this.extremeFundingRate = extremeFundingRate == null
                ? new BigDecimal(DEFAULT_EXTREME_FUNDING_RATE)
                : extremeFundingRate.abs();
    }

    @Override
    public ProviderResult<FundingHistorySnapshot> fetch(String instId) {
        return fetch(instId, null);
    }

    @Override
    public ProviderResult<FundingHistorySnapshot> fetch(String instId, Duration timeout) {
        if (instId == null || instId.isBlank()) {
            return ProviderResult.unavailable("FUNDING_PROVIDER_UNAVAILABLE: blank instId");
        }
        if (restClient == null) {
            return ProviderResult.unavailable("FUNDING_PROVIDER_UNAVAILABLE: no rest client");
        }
        if (timeout != null && timeout.isZero()) {
            return ProviderResult.timeout("FUNDING_TIMEOUT: deadline exceeded");
        }
        if (Thread.currentThread().isInterrupted()) {
            return ProviderResult.timeout("FUNDING_TIMEOUT: interrupted");
        }
        try {
            String path = "/api/v5/public/funding-rate-history?instId=" + instId + "&limit=" + TARGET_EVENTS;
            JsonNode root = timeout == null
                    ? restClient.publicGet(path)
                    : restClient.publicGet(path, timeout);
            if (root == null) {
                return ProviderResult.unavailable("FUNDING_EMPTY_RESPONSE: empty response");
            }
            String code = root.path("code").asText("1");
            if (!"0".equals(code)) {
                String msg = root.path("msg").asText("unknown");
                return ProviderResult.unavailable("FUNDING_PROVIDER_UNAVAILABLE: api code=" + code + " msg=" + msg);
            }
            JsonNode data = root.path("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                return ProviderResult.unavailable("FUNDING_EMPTY_RESPONSE: empty data array");
            }
            List<FundingPoint> points = parsePoints(data);
            if (points.isEmpty()) {
                return ProviderResult.unavailable("FUNDING_EMPTY_RESPONSE: no parseable points");
            }
            points.sort((a, b) -> Long.compare(a.tsMillis, b.tsMillis));
            FundingPoint latest = points.get(points.size() - 1);
            BigDecimal currentRate = latest.rate;
            BigDecimal rate24h = points.size() >= 3 ? points.get(0).rate : null;
            double slope = computeSlope(points);
            FundingTrend trend = FundingTrend.classify(slope);
            boolean extreme = rate24h != null && rate24h.abs().compareTo(extremeFundingRate) >= 0;
            FundingHistorySnapshot.DataQuality quality = points.size() >= TARGET_EVENTS
                    ? FundingHistorySnapshot.DataQuality.FRESH
                    : FundingHistorySnapshot.DataQuality.STALE;
            List<BigDecimal> rawRates = new ArrayList<>(points.size());
            for (FundingPoint p : points) {
                rawRates.add(p.rate);
            }
            FundingHistorySnapshot snap = new FundingHistorySnapshot(
                    instId,
                    currentRate,
                    rate24h,
                    slope,
                    trend,
                    extreme,
                    rawRates,
                    quality,
                    Instant.ofEpochMilli(latest.tsMillis),
                    Instant.now(),
                    providerName
            );
            return ProviderResult.fresh(snap, providerName);
        } catch (Exception ex) {
            if (isTimeout(ex)) {
                log.warn("FUNDING_TIMEOUT instId={} message={}", instId, ex.toString());
                return ProviderResult.timeout("FUNDING_TIMEOUT: request timed out");
            }
            log.warn("FUNDING_PROVIDER_UNAVAILABLE instId={} message={}", instId, ex.toString());
            String msg = ex.getMessage();
            return ProviderResult.unavailable("FUNDING_PROVIDER_UNAVAILABLE: " + ex.getClass().getSimpleName()
                    + (msg == null || msg.isBlank() ? "" : " (" + msg + ")"));
        }
    }

    private static double computeSlope(List<FundingPoint> points) {
        int n = points.size();
        if (n < 2) {
            return 0.0;
        }
        double xMean = (n + 1) / 2.0;
        double yMean = 0.0;
        for (FundingPoint p : points) {
            yMean += p.rate.doubleValue();
        }
        yMean /= n;
        double numerator = 0.0;
        double denominator = 0.0;
        for (int i = 0; i < n; i++) {
            double xi = i + 1;
            double yi = points.get(i).rate.doubleValue();
            double dx = xi - xMean;
            double dy = yi - yMean;
            numerator += dx * dy;
            denominator += dx * dx;
        }
        if (denominator == 0.0) {
            return 0.0;
        }
        double slope = numerator / denominator;
        if (Double.isNaN(slope) || Double.isInfinite(slope)) {
            return 0.0;
        }
        return slope;
    }

    private static List<FundingPoint> parsePoints(JsonNode data) {
        List<FundingPoint> out = new ArrayList<>();
        for (JsonNode entry : data) {
            String tsStr = entry.path("fundingTime").asText(null);
            String rateStr = entry.path("fundingRate").asText(null);
            if (tsStr == null || rateStr == null) {
                continue;
            }
            try {
                long ts = Long.parseLong(tsStr);
                BigDecimal rate = new BigDecimal(rateStr);
                out.add(new FundingPoint(ts, rate));
            } catch (NumberFormatException ex) {
                // skip invalid point
            }
        }
        return out;
    }

    private static boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (current instanceof java.util.concurrent.TimeoutException
                    || (message != null && message.toLowerCase().contains("timed out"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class FundingPoint {
        final long tsMillis;
        final BigDecimal rate;

        FundingPoint(long tsMillis, BigDecimal rate) {
            this.tsMillis = tsMillis;
            this.rate = rate;
        }
    }
}

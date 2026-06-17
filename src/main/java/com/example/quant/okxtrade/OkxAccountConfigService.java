package com.example.quant.okxtrade;

import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OkxAccountConfigService implements OkxPositionModeProvider {
    private static final Logger log = LoggerFactory.getLogger(OkxAccountConfigService.class);
    private static final long CACHE_MILLIS = Duration.ofMinutes(5).toMillis();

    private final OkxRestClient okxRestClient;
    private volatile OkxPositionMode cachedMode;
    private volatile long expiresAtMillis;

    public OkxAccountConfigService(OkxRestClient okxRestClient) {
        this.okxRestClient = okxRestClient;
    }

    @Override
    public OkxPositionMode positionMode() {
        long now = System.currentTimeMillis();
        OkxPositionMode current = cachedMode;
        if (current != null && now < expiresAtMillis) {
            return current;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (cachedMode != null && now < expiresAtMillis) {
                return cachedMode;
            }
            cachedMode = fetchPositionMode();
            expiresAtMillis = now + CACHE_MILLIS;
            return cachedMode;
        }
    }

    private OkxPositionMode fetchPositionMode() {
        try {
            JsonNode data = okxRestClient.privateGet("/api/v5/account/config").path("data");
            if (data.isArray() && !data.isEmpty()) {
                String posMode = data.get(0).path("posMode").asText("");
                OkxPositionMode mode = "long_short_mode".equalsIgnoreCase(posMode)
                        ? OkxPositionMode.LONG_SHORT
                        : OkxPositionMode.NET;
                log.info("Loaded OKX account position mode posMode={} mapped={}", posMode, mode);
                return mode;
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to load OKX account position mode, fallback to net mode: {}", ex.getMessage());
        }
        return OkxPositionMode.NET;
    }
}

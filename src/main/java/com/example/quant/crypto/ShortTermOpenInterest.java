package com.example.quant.crypto;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Select the snapshot closest to five minutes ago, allowing for scan cadence. */
final class ShortTermOpenInterest {
    private static final long MINUTE = 60_000L;
    private final Map<String, NavigableMap<Long, BigDecimal>> history = new HashMap<>();
    private long lastCleanup;

    synchronized BigDecimal previous(String instrument, long timestamp, BigDecimal amount, long now) {
        if (now - lastCleanup >= MINUTE) {
            history.values().removeIf(samples -> samples.lastKey() < now - 8 * MINUTE);
            lastCleanup = now;
        }
        if (amount == null || amount.signum() <= 0 || timestamp <= 0
                || timestamp > now || timestamp < now - MINUTE) {
            return null;
        }
        var samples = history.computeIfAbsent(instrument, ignored -> new TreeMap<>());
        if (!samples.isEmpty() && timestamp < samples.lastKey()) {
            return null;
        }
        samples.headMap(timestamp - 7 * MINUTE, false).clear();
        samples.putIfAbsent(timestamp, amount);
        long target = timestamp - 5 * MINUTE;
        var earlier = samples.floorEntry(target);
        var later = samples.ceilingEntry(target);
        var closest = earlier != null && (later == null || target - earlier.getKey() <= later.getKey() - target)
                ? earlier : later;
        return closest != null && Math.abs(closest.getKey() - target) <= 2 * MINUTE
                ? closest.getValue() : null;
    }
}

package com.example.quant.crypto.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DeepAnalysisTargetAllocator {
    public record Visit(long attemptedAt, long analyzedAt) {
        private static final Visit UNSEEN = new Visit(0, 0);

        boolean coolingDown(long now) {
            return attemptedAt > analyzedAt && now - attemptedAt < 300_000L;
        }
    }

    public record Allocation(List<String> instIds, int pinnedCount, int headCount, int rotationCount) {}

    public Allocation allocate(List<String> ranked, int limit, int rotationQuota, Set<String> pinned,
                               Map<String, Visit> history, long now) {
        var selected = new LinkedHashSet<String>();
        limit = Math.max(1, limit);
        ranked.stream().filter(pinned::contains).limit(limit).forEach(selected::add);
        int pinnedCount = selected.size();
        int remaining = limit - pinnedCount;
        int reserved = ranked.size() <= limit ? 0 : Math.max(0, Math.min(rotationQuota, remaining - 1));
        ranked.stream().filter(id -> !selected.contains(id)).limit(remaining - reserved).forEach(selected::add);
        int headCount = selected.size() - pinnedCount;
        // Stable sort preserves turnover order for instruments with identical visit history.
        List<String> tail = new ArrayList<>(ranked.stream().filter(id -> !selected.contains(id)).toList());
        Comparator<Visit> priority = Comparator.comparing((Visit v) -> v.coolingDown(now))
                .thenComparingLong(Visit::analyzedAt).thenComparingLong(Visit::attemptedAt);
        tail.sort(Comparator.comparing(id -> history.getOrDefault(id, Visit.UNSEEN), priority));
        tail.stream().limit(reserved).forEach(selected::add);
        return new Allocation(List.copyOf(selected), pinnedCount, headCount, selected.size() - headCount - pinnedCount);
    }
}

package com.example.quant.crypto.scan;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ScanCoverageStore {
    private final JdbcTemplate jdbc;

    public ScanCoverageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, DeepAnalysisTargetAllocator.Visit> load() {
        Map<String, DeepAnalysisTargetAllocator.Visit> visits = new HashMap<>();
        jdbc.query("SELECT inst_id, attempted_at_ms, analyzed_at_ms FROM scan_instrument_coverage", rs -> {
            visits.put(rs.getString(1), new DeepAnalysisTargetAllocator.Visit(rs.getLong(2), rs.getLong(3)));
        });
        return visits;
    }

    @Transactional
    public void save(Map<String, DeepAnalysisTargetAllocator.Visit> visits) {
        jdbc.batchUpdate("""
                INSERT INTO scan_instrument_coverage(inst_id, attempted_at_ms, analyzed_at_ms) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE attempted_at_ms=GREATEST(attempted_at_ms, VALUES(attempted_at_ms)),
                                        analyzed_at_ms=GREATEST(analyzed_at_ms, VALUES(analyzed_at_ms))
                """, visits.entrySet(), 100, (ps, entry) -> {
                    ps.setString(1, entry.getKey());
                    ps.setLong(2, entry.getValue().attemptedAt());
                    ps.setLong(3, entry.getValue().analyzedAt());
                });
    }
}

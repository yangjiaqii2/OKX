package com.example.quant.crypto.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ScanCoverageStoreTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void restartAndLateWritesPreserveLatestAttemptAndLatestSuccessIndependently() {
        var store = new ScanCoverageStore(jdbc);
        store.save(Map.of("A", new DeepAnalysisTargetAllocator.Visit(1000, 1000)));
        store.save(Map.of("A", new DeepAnalysisTargetAllocator.Visit(3000, 1000)));
        store.save(Map.of("A", new DeepAnalysisTargetAllocator.Visit(2000, 2000)));
        assertThat(new ScanCoverageStore(jdbc).load().get("A"))
                .isEqualTo(new DeepAnalysisTargetAllocator.Visit(3000, 2000));
    }
}

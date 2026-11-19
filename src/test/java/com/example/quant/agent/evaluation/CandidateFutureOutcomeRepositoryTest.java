package com.example.quant.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.scan.AgentScanRunEntity;
import com.example.quant.agent.scan.AgentScanRunRepository;
import com.example.quant.agent.score.AgentContractCandidateScoreEntity;
import com.example.quant.agent.score.AgentContractCandidateScoreRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:outcome_repository_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CandidateFutureOutcomeRepositoryTest {
    @Autowired private CandidateFutureOutcomeRepository outcomes;
    @Autowired private AgentContractCandidateScoreRepository snapshots;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AgentScanRunRepository runs;

    @Test
    void cohortQueryChecksVersionStatusAndPersistedCount() {
        AgentScanRunEntity run = new AgentScanRunEntity();
        run.setStatus("SUCCESS");
        run.setCandidateCount(1);
        run.setStartedAt(Instant.EPOCH);
        run.setCreatedAt(Instant.EPOCH);
        runs.saveAndFlush(run);
        AgentContractCandidateScoreEntity row = new AgentContractCandidateScoreEntity();
        row.setScanRunId(run.getId());
        row.setInstId("AKE-USDT-SWAP");
        row.setCreatedAt(Instant.EPOCH);
        row.setScoringVersion("TEST_VERSION");
        snapshots.saveAndFlush(row);
        assertThat(runs.findCompleteForVersion("TEST_VERSION")).containsExactly(run);
        assertThat(runs.findCompleteForVersion("OTHER_VERSION")).isEmpty();
        run.setCandidateCount(2);
        runs.saveAndFlush(run);
        assertThat(runs.findCompleteForVersion("TEST_VERSION")).isEmpty();
        run.setCandidateCount(1);
        run.setStatus("FAILED");
        runs.saveAndFlush(run);
        assertThat(runs.findCompleteForVersion("TEST_VERSION")).isEmpty();
    }

    @BeforeAll
    static void modelMySqlTimestampInstant(@Autowired JdbcTemplate jdbc) {
        // MySQL TIMESTAMP stores an instant; the same Flyway spelling in H2 is a wall-clock value.
        jdbc.execute("ALTER TABLE agent_contract_candidate_score ALTER COLUMN created_at TIMESTAMP WITH TIME ZONE");
    }

    @Test
    void utcMidnightStrataAreIndependentOfSessionTimeZone() {
        Long before = snapshot("NEUTRAL", "2026-01-20T23:55:00Z");
        Long after = snapshot("NEUTRAL", "2026-01-21T00:05:00Z");
        outcome(before, 240, "2026-01-21T03:55:00Z");
        outcome(after, 240, "2026-01-21T04:05:00Z");
        try {
            for (String offset : List.of("+08:00", "-05:00")) {
                jdbc.execute("SET TIME ZONE '" + offset + "'");
                assertThat(outcomes.findHistoricalControlDue(List.of("PENDING"),
                        Instant.parse("2026-01-22T00:00:00Z"), Instant.parse("2026-01-23T00:00:00Z"),
                        PageRequest.of(0, 10))).extracting(CandidateFutureOutcomeEntity::getSnapshotId)
                        .containsExactly(before, after);
            }
        } finally {
            jdbc.execute("SET TIME ZONE 'UTC'");
        }
    }

    @Test
    void historicalFocusSelectsFourHourOutcomeBeforeOlderOneHourOutcome() {
        Long snapshotId = snapshot("TREND_SHORT", "2026-01-20T00:00:00Z");
        outcome(snapshotId, 60, "2026-01-20T01:00:00Z");
        outcome(snapshotId, 240, "2026-01-20T04:00:00Z");
        assertThat(outcomes.findHistoricalDue(List.of("PENDING"),
                Instant.parse("2026-01-22T00:00:00Z"), Instant.parse("2026-01-23T00:00:00Z"),
                PageRequest.of(0, 2))).extracting(CandidateFutureOutcomeEntity::getHorizonMinutes)
                .containsExactly(240, 60);
    }

    private Long snapshot(String signal, String createdAt) {
        AgentContractCandidateScoreEntity row = new AgentContractCandidateScoreEntity();
        row.setScanRunId(123L);
        row.setInstId("BTC-USDT-SWAP");
        row.setSignalType(signal);
        row.setCreatedAt(Instant.parse(createdAt));
        return snapshots.saveAndFlush(row).getId();
    }

    private void outcome(Long snapshot, int horizon, String maturity) {
        CandidateFutureOutcomeEntity row = new CandidateFutureOutcomeEntity();
        row.setSnapshotId(snapshot);
        row.setHorizonMinutes(horizon);
        row.setMaturityAt(Instant.parse(maturity));
        row.setStatus("PENDING");
        row.setUpdatedAt(row.getMaturityAt());
        outcomes.saveAndFlush(row);
    }
}

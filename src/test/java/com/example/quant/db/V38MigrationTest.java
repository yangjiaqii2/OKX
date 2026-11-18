package com.example.quant.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.QuantAssistantApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class V38MigrationTest {
    @Test
    void v38ResourceDeclaresExplicitSnapshotAndUniqueOutcomeSchema() throws Exception {
        Path sql = Path.of("src/main/resources/db/migration/V38__historical_candidate_outcomes.sql");
        assertThat(Files.exists(sql)).isTrue();
        String content = Files.readString(sql);
        assertThat(content).contains("scoring_version", "reference_price", "capital_flow_score",
                "candidate_future_outcome", "UNIQUE (snapshot_id, horizon_minutes)");
        assertThat(content.lines().filter(line -> line.trim().toUpperCase().startsWith("ALTER TABLE")).count())
                .isGreaterThan(10L);
    }

    @Test
    void flywayAppliesV38OutcomeTableAndSnapshotColumns() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(QuantAssistantApplication.class)
                .profiles("test")
                .properties(
                        "spring.main.web-application-type=none",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                        "spring.flyway.locations=classpath:db/migration"
                )
                .run()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            List<String> tables = jdbc.queryForList(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES", String.class);
            assertThat(tables).anyMatch(name -> name.equalsIgnoreCase("candidate_future_outcome"));
            List<String> columns = jdbc.queryForList(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE LOWER(TABLE_NAME) = LOWER(?)", String.class,
                    "agent_contract_candidate_score");
            assertThat(columns).contains("snapshot_rank", "reference_price", "scoring_version",
                    "v2_ranking_score", "v2_coverage_ratio", "capital_flow_score");
        }
    }
}

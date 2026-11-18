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

class V39MigrationTest {
    @Test
    void v39ResourceAddsRetryAndObservationMetadataWithoutEditingV38() throws Exception {
        Path v38 = Path.of("src/main/resources/db/migration/V38__historical_candidate_outcomes.sql");
        Path v39 = Path.of("src/main/resources/db/migration/V39__historical_candidate_outcome_integrity.sql");
        assertThat(Files.exists(v38)).isTrue();
        assertThat(Files.exists(v39)).isTrue();
        String content = Files.readString(v39);
        assertThat(content).contains("retry_count", "last_attempt_at", "next_retry_at",
                "observed_at", "window_start", "window_end", "idx_candidate_future_outcome_retry");
    }

    @Test
    void flywayAppliesV39OutcomeIntegrityColumns() {
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
            List<String> columns = jdbc.queryForList(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE LOWER(TABLE_NAME) = LOWER(?)", String.class,
                    "candidate_future_outcome");
            assertThat(columns).contains("retry_count", "last_attempt_at", "next_retry_at",
                    "observed_at", "window_start", "window_end");
        }
    }
}

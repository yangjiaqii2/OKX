package com.example.quant.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.QuantAssistantApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class V40MigrationTest {
    @Test
    void v40ResourceWidensOpenInterestSnapshotPrecision() throws Exception {
        Path sql = Path.of("src/main/resources/db/migration/V40__widen_open_interest_snapshot.sql");
        assertThat(Files.exists(sql)).isTrue();
        assertThat(Files.readString(sql))
                .contains("snapshot_open_interest DECIMAL(30, 12)");
    }

    @Test
    void flywayAppliesWiderOpenInterestSnapshotColumn() {
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
            Map<String, Object> metadata = jdbc.queryForMap(
                    "SELECT NUMERIC_PRECISION, NUMERIC_SCALE FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE LOWER(TABLE_NAME) = LOWER(?) AND LOWER(COLUMN_NAME) = LOWER(?)",
                    "agent_contract_candidate_score", "snapshot_open_interest");
            assertThat(((Number) metadata.get("NUMERIC_PRECISION")).intValue()).isEqualTo(30);
            assertThat(((Number) metadata.get("NUMERIC_SCALE")).intValue()).isEqualTo(12);
        }
    }
}

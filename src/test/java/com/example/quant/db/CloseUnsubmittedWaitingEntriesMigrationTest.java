package com.example.quant.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class CloseUnsubmittedWaitingEntriesMigrationTest {
    @Test
    void closesDeferredEntriesAndPreservesSubmittedOrUnknownOrders() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:close_wait_entries;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE waiting_entry_intent (
                    decision_client_order_id VARCHAR(32) PRIMARY KEY, status VARCHAR(32), active_key VARCHAR(32),
                    reason VARCHAR(240), updated_at TIMESTAMP, pending_order_id VARCHAR(36), okx_order_id VARCHAR(64)
                )
                """);
        jdbc.execute("CREATE TABLE pending_order_state (client_order_id VARCHAR(32) PRIMARY KEY)");
        jdbc.update("""
                INSERT INTO waiting_entry_intent (decision_client_order_id, status, active_key, okx_order_id) VALUES
                  ('plain-wait', 'WAITING', 'plain-wait', NULL),
                  ('spread-blocked', 'TRIGGERED', 'spread-blocked', NULL),
                  ('with-local-order', 'TRIGGERED', 'with-local-order', NULL),
                  ('with-exchange-order', 'VALIDATING', 'with-exchange-order', 'okx-123'),
                  ('unknown-submit', 'RECONCILING', 'unknown-submit', NULL)
                """);
        jdbc.update("INSERT INTO pending_order_state VALUES ('with-local-order')");

        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V52__close_unsubmitted_waiting_entries.sql")).execute(dataSource);

        Map<String, String> statuses = jdbc.query("SELECT decision_client_order_id, status FROM waiting_entry_intent",
                rs -> {
                    Map<String, String> result = new java.util.HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getString(2));
                    }
                    return result;
                });
        assertThat(statuses).containsExactlyInAnyOrderEntriesOf(Map.of(
                "plain-wait", "CANCELED", "spread-blocked", "CANCELED",
                "with-local-order", "TRIGGERED", "with-exchange-order", "VALIDATING",
                "unknown-submit", "RECONCILING"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM waiting_entry_intent
                WHERE status = 'CANCELED' AND active_key IS NULL AND reason = 'AI_WAIT_DISABLED'
                """, Integer.class)).isEqualTo(2);
    }
}

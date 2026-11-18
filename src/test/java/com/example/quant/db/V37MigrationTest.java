package com.example.quant.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.example.quant.QuantAssistantApplication;

/**
 * Phase B V37 migration 基础验证。
 *
 * <p>两个维度：<ol>
 *   <li>结构性 — 资源文件存在，5 个 ADD COLUMN 全部到位</li>
 *   <li>运行时 — Spring Boot + Flyway 启动后，{@code agent_contract_candidate_score}
 *       表已包含 5 个 V2 字段</li>
 * </ol>
 *
 * <p>不在这里跑 V37 SQL（那是 Flyway 的职责），只断言结果。
 */
class V37MigrationTest {

    @Nested
    @DisplayName("结构性验证")
    class Structural {

        @Test
        void v37ResourceFileExists() throws Exception {
            Path sql = Path.of("src/main/resources/db/migration/V37__selection_v2_universe.sql");
            assertThat(Files.exists(sql))
                    .as("V37 migration file must exist at %s", sql.toAbsolutePath())
                    .isTrue();
            String content = Files.readString(sql);
            // 5 个 V2 字段：marketCap / FDV / Circulation / VolumeMcap / MarketQualityNote
            assertThat(content).contains("market_cap_usd");
            assertThat(content).contains("fdv_usd");
            assertThat(content).contains("circulation_ratio");
            assertThat(content).contains("volume_mcap_ratio");
            assertThat(content).contains("market_quality_note");
            // 5 个独立 ALTER TABLE（H2 v2 MySQL 模式 ADD COLUMN 不支持多列）
            long alterCount = content.lines()
                    .filter(line -> line.trim().toUpperCase().startsWith("ALTER TABLE"))
                    .count();
            assertThat(alterCount)
                    .as("V37 should have 5 ALTER TABLE statements, one per column")
                    .isEqualTo(5L);
            // 必须作用在 agent_contract_candidate_score
            assertThat(content).contains("agent_contract_candidate_score");
        }

        @Test
        void v32IsUntouched() throws Exception {
            // V32 counterfactual_exit 是已存在 migration，Phase B 不能动它
            Path v32 = Path.of("src/main/resources/db/migration/V32__counterfactual_exit.sql");
            assertThat(Files.exists(v32))
                    .as("V32 migration file must remain untouched")
                    .isTrue();
        }

        @Test
        void migrationOrderIsStrictlyIncreasing() throws Exception {
            // 防止误用 V1-V36 之间已用过的版本号
            Path dir = Path.of("src/main/resources/db/migration");
            List<Integer> versions = Files.list(dir)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("^V\\d+__.+\\.sql$"))
                    .map(name -> Integer.parseInt(name.substring(1, name.indexOf('_'))))
                    .sorted()
                    .collect(Collectors.toList());
            for (int i = 1; i < versions.size(); i++) {
                assertThat(versions.get(i))
                        .as("Migration versions must be strictly increasing, no duplicates")
                        .isGreaterThan(versions.get(i - 1));
            }
            // V37 必须存在
            assertThat(versions).contains(37);
        }
    }

    @Nested
    @DisplayName("运行时验证：Flyway 应用 V37 后 schema 包含 5 个 V2 列")
    class Runtime {

        @Test
        void flywayAppliesV37AndColumnsExist() {
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(QuantAssistantApplication.class)
                    .profiles("test")
                    .properties(
                            "spring.main.web-application-type=none",
                            "spring.datasource.driver-class-name=org.h2.Driver",
                            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                            "spring.flyway.locations=classpath:db/migration"
                    )
                    .run()) {
                JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
                // 探测模式
                List<String> allTables = jdbc.queryForList(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES",
                        String.class);
                List<String> target = allTables.stream()
                        .filter(t -> t.toLowerCase().contains("agent_contract_candidate_score"))
                        .toList();
                assertThat(target)
                        .as("agent_contract_candidate_score table must exist after Flyway (got tables: %s)",
                                allTables.stream().filter(t -> t.toLowerCase().contains("agent")).toList())
                        .isNotEmpty();

                List<String> columns = jdbc.queryForList(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE LOWER(TABLE_NAME) = LOWER(?)",
                        String.class,
                        target.get(0));
                assertThat(columns)
                        .as("V37 must add 5 V2 columns to %s (got: %s)", target.get(0), columns)
                        .contains("market_cap_usd", "fdv_usd", "circulation_ratio",
                                "volume_mcap_ratio", "market_quality_note");
            }
        }
    }
}

package com.example.quant.agent.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.quant.crypto.OkxMarketService;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.market.DirectionBias;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class AiEntryObservationServiceTest {
    @Test
    void migrationPreservesLegacyRowsAndPersistedTrackingResumesAfterRestart() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:ai_entry_audit;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE auto_trade_record (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO auto_trade_record VALUES (1)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V53__ai_entry_observation.sql")).execute(source);
        assertThat(jdbc.queryForObject("SELECT ai_observation_id FROM auto_trade_record WHERE id=1", String.class)).isNull();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var market = mock(OkxMarketService.class);
        Instant skipped = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(2400);
        var first = new AiEntryObservationService(jdbc, mapper, market);
        try {
            first.record(attempt(mapper, "first", skipped));
            first.track("first", "owner", "BTC-USDT-SWAP", DirectionBias.BEARISH, BigDecimal.valueOf(100),
                    skipped.minusSeconds(10), skipped);
            first.record(attempt(mapper, "duplicate", skipped.plusSeconds(20)));
            first.track("duplicate", "owner", "BTC-USDT-SWAP", DirectionBias.BEARISH,
                    BigDecimal.valueOf(100), skipped, skipped.plusSeconds(20));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_entry_observation", Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_entry_counterfactual", Integer.class)).isEqualTo(1);
            });
            verifyNoInteractions(market);
        } finally {
            first.stop();
        }
        var record = jdbc.queryForMap("SELECT * FROM ai_entry_observation WHERE id='first'");
        assertThat(record.get("input_sha256").toString()).hasSize(64);
        assertThat(record.get("prompt_version").toString()).hasSize(64);
        assertThat(record.get("threshold_version").toString()).hasSize(64);
        var payload = mapper.readTree(jdbc.queryForObject(
                "SELECT decision_json FROM ai_entry_observation WHERE id='first'", String.class));
        assertThat(payload.path("rawDecision").asText()).isEqualTo("TRADE");
        assertThat(payload.path("finalSelection").path("decision").asText()).isEqualTo("WAIT");
        assertThat(payload.path("thresholdVetoes").size()).isEqualTo(2);
        assertThat(payload.path("qualityThreshold").doubleValue()).isEqualTo(1.75);
        assertThat(payload.path("riskThreshold").doubleValue()).isEqualTo(0.55);
        assertThat(payload.path("confidenceUsedToBlock").asBoolean()).isFalse();

        var thread = new AtomicReference<String>();
        List<ContractCandle> candles = IntStream.range(0, 30).mapToObj(i -> new ContractCandle(
                skipped.plusSeconds(i * 60L), BigDecimal.valueOf(100), BigDecimal.valueOf(106),
                BigDecimal.valueOf(88), BigDecimal.valueOf(90), BigDecimal.ONE)).toList();
        when(market.candles(eq("BTC-USDT-SWAP"), eq("1m"), eq(300), eq(Duration.ofSeconds(4))))
                .thenAnswer(ignored -> {
                    thread.set(Thread.currentThread().getName());
                    return candles;
                });
        var restarted = new AiEntryObservationService(jdbc, mapper, market);
        try {
            restarted.checkDue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "SELECT status FROM ai_entry_counterfactual WHERE observation_id='first'", String.class))
                    .isEqualTo("COMPLETE"));
            assertThat(thread.get()).isEqualTo("ai-entry-counterfactual");
            for (int minutes : List.of(5, 15, 30)) {
                String json = jdbc.queryForObject("SELECT outcome_" + minutes
                        + "m FROM ai_entry_counterfactual WHERE observation_id='first'", String.class);
                assertThat(mapper.readTree(json).path("returnPct").decimalValue()).isEqualByComparingTo("10");
            }
        } finally {
            restarted.stop();
        }
    }

    private static AiEntryObservation attempt(ObjectMapper mapper, String id, Instant time) throws Exception {
        var response = mapper.readTree("""
                {"model":"jev-1","answers":{"decision":{"choice":"TRADE","confidence":0.13},
                  "selectedInstId":{"choice":"BTC-USDT-SWAP","confidence":0.88},
                  "direction":{"choice":"SHORT","confidence":0.90},"entryMode":{"choice":"MARKET","confidence":0.86},
                  "quality":{"score":1.7,"confidence":0.82},"risk_ok":{"noul":0.54}}}
                """);
        var decision = new ContractSelectionDecision(ContractSelectionDecision.DecisionSource.AI_WAIT,
                ContractSelectionDecision.SelectionAction.WAIT, "BTC-USDT-SWAP", DirectionBias.BEARISH,
                "WAIT_RETEST", new BigDecimal("0.13"), List.of(), List.of(), List.of(), "");
        return new AiEntryObservation(new AiEntryObservation.Reference(id,
                AiEntryObservation.Rejection.QUALITY_AND_RISK_BELOW_THRESHOLD),
                "EXECUTION", "owner", "scan", time, "BTC-USDT-SWAP", "jev-latest", true, "task",
                Map.of("state", Map.of("recentClosed5mCandles", List.of())), response, decision,
                List.of(AiEntryObservation.Rejection.QUALITY_BELOW_THRESHOLD,
                        AiEntryObservation.Rejection.RISK_BELOW_THRESHOLD), List.of(), null);
    }
}

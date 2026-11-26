package com.example.quant.agent.selection;

import com.example.quant.crypto.OkxMarketService;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.market.DirectionBias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AiEntryObservationService {
    private static final Logger log = LoggerFactory.getLogger(AiEntryObservationService.class);
    private static final List<Integer> HORIZONS = List.of(5, 15, 30);
    private static final Duration TRACKING_TTL = Duration.ofHours(4);
    private static final int SYMBOLS_PER_TICK = 6;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OkxMarketService market;
    private final ThreadPoolExecutor writes = executor("ai-entry-observation", 256);
    private final ThreadPoolExecutor tracking = executor("ai-entry-counterfactual", 1);
    private Instant backoffUntil = Instant.EPOCH;
    private Instant nextTick = Instant.EPOCH;

    public AiEntryObservationService(JdbcTemplate jdbc, ObjectMapper mapper, OkxMarketService market) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.market = market;
    }

    void record(AiEntryObservation observation) {
        offer(writes, () -> save(observation), "audit", observation.reference().id());
    }

    public void track(String observationId, String owner, String instId, DirectionBias direction,
                      BigDecimal price, Instant referenceAt, Instant skippedAt) {
        if (observationId == null) return;
        if (instId == null || price == null || price.signum() <= 0
                || (direction != DirectionBias.BULLISH && direction != DirectionBias.BEARISH)) {
            log.warn("AI entry counterfactual missing reference id={}", observationId);
            return;
        }
        offer(writes, () -> {
            try {
                jdbc.update("""
                        INSERT INTO ai_entry_counterfactual
                        (observation_id,user_name,inst_id,direction,bucket_5m,reference_price,reference_at,
                         skipped_at,next_check_at,status)
                        VALUES (?,?,?,?,?,?,?,?,?,'PENDING')
                        """, observationId, owner, instId, direction.name(), Math.floorDiv(skippedAt.getEpochSecond(), 300),
                        price, referenceAt == null ? null : timestamp(referenceAt),
                        timestamp(skippedAt), timestamp(skippedAt.plusSeconds(300)));
            } catch (DuplicateKeyException duplicate) {
                log.debug("AI entry counterfactual already tracked id={}", observationId);
            }
        }, "tracking enqueue", observationId);
    }

    @Scheduled(fixedDelayString = "${quant.agent.evaluation.ai-entry-tracking-interval-ms:60000}",
            initialDelayString = "${quant.agent.evaluation.ai-entry-tracking-interval-ms:60000}")
    public void checkDue() {
        offer(tracking, this::reconcile, "tracking tick", null);
    }

    private void save(AiEntryObservation observation) throws Exception {
        String input = observation.input() == null ? null : mapper.writeValueAsString(observation.input());
        String version = observation.prompt() == null ? null : hash(observation.prompt());
        String thresholdVersion = observation.input() == null ? null : hash(observation.typeSafe()
                ? "quality=" + AiEntryObservation.QUALITY_MINIMUM + ";risk=" + AiEntryObservation.RISK_MINIMUM
                : "quality and risk_ok gates not used");
        JsonNode response = observation.response();
        Map<String, Object> decision = decisionFacts(observation);
        jdbc.update("""
                INSERT INTO ai_entry_observation
                (id,scope,user_name,scan_id,observed_at,inst_id,reason_code,model_requested,model_reported,
                 prompt_version,threshold_version,input_sha256,input_json,decision_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, observation.reference().id(), observation.scope(), observation.owner(), observation.scanId(),
                timestamp(observation.observedAt()), bounded(observation.instId() == null || observation.instId().isBlank()
                        ? (String) decision.get("modelSelectedInstId") : observation.instId(), 64),
                observation.reference().reasonCode(), observation.requestedModel(),
                response == null ? null : bounded(response.path("model").asText(null), 128), version,
                thresholdVersion, input == null ? null : hash(input), input, mapper.writeValueAsString(decision));
    }

    static Map<String, Object> decisionFacts(AiEntryObservation observation) {
        JsonNode response = observation.response();
        JsonNode decision = answer(response, "decision", "choices");
        JsonNode selected = answer(response, "selectedInstId", "choices");
        JsonNode direction = answer(response, "direction", "choices");
        JsonNode entryMode = answer(response, "entryMode", "choices");
        JsonNode quality = answer(response, "quality", "scores");
        JsonNode risk = answer(response, "risk_ok", "nouls");
        boolean typed = observation.typeSafe();
        String rawDecision = value(response, decision, "decision", typed);
        BigDecimal qualityScore = number(quality, "score");
        BigDecimal riskOk = number(risk, "noul");
        var confidences = new LinkedHashMap<String, BigDecimal>();
        if (typed) {
            confidences.put("decision", number(decision, "confidence"));
            confidences.put("selectedInstId", number(selected, "confidence"));
            confidences.put("direction", number(direction, "confidence"));
            confidences.put("entryMode", number(entryMode, "confidence"));
            confidences.put("quality", number(quality, "confidence"));
            confidences.put("risk_ok", number(risk, "confidence"));
        }
        var facts = new LinkedHashMap<String, Object>();
        facts.put("rawDecision", rawDecision);
        facts.put("modelSelectedInstId", value(response, selected, "selectedInstId", typed));
        facts.put("modelDirection", value(response, direction, "direction", typed));
        facts.put("modelEntryMode", value(response, entryMode, "entryMode", typed));
        facts.put("rawResponse", response);
        facts.put("finalSelection", observation.finalDecision());
        facts.put("quality", qualityScore);
        facts.put("qualityMax", typed ? 4 : null);
        facts.put("riskOk", riskOk);
        facts.put("qualityThreshold", typed ? AiEntryObservation.QUALITY_MINIMUM : null);
        facts.put("riskThreshold", typed ? AiEntryObservation.RISK_MINIMUM : null);
        facts.put("qualityBelowThreshold", typed && qualityScore != null
                && qualityScore.doubleValue() < AiEntryObservation.QUALITY_MINIMUM);
        facts.put("riskBelowThreshold", typed && riskOk != null && riskOk.doubleValue() < AiEntryObservation.RISK_MINIMUM);
        facts.put("thresholdVetoes", observation.thresholdVetoes());
        facts.put("confidenceByQuestion", confidences);
        var fiveConfidenceValues = confidences.entrySet().stream()
                .filter(entry -> !"risk_ok".equals(entry.getKey()))
                .map(Map.Entry::getValue).filter(java.util.Objects::nonNull).toList();
        facts.put("lowestRawConfidence", fiveConfidenceValues.size() == 5
                ? fiveConfidenceValues.stream().min(BigDecimal::compareTo).orElse(null) : null);
        boolean parsed = observation.finalDecision() != null
                && observation.finalDecision().source() != ContractSelectionDecision.DecisionSource.RULE_FALLBACK;
        facts.put("lowestEffectiveConfidence", typed && parsed ? observation.finalDecision().confidence() : null);
        facts.put("confidenceUsedToBlock", false);
        facts.put("hardRules", observation.hardRules());
        facts.put("detail", observation.detail());
        return facts;
    }

    private void reconcile() {
        Instant now = Instant.now();
        if (now.isBefore(nextTick)) return;
        nextTick = now.plusSeconds(60);
        jdbc.update("""
                UPDATE ai_entry_counterfactual SET status='EXPIRED'
                WHERE status='PENDING' AND skipped_at <= ?
                """, timestamp(now.minus(TRACKING_TTL)));
        if (now.isBefore(backoffUntil)) return;
        List<TrackingRow> due = jdbc.query("""
                SELECT observation_id, inst_id, direction, reference_price, skipped_at,
                       outcome_5m, outcome_15m, outcome_30m
                FROM ai_entry_counterfactual
                WHERE status='PENDING' AND next_check_at <= ?
                ORDER BY next_check_at, observation_id LIMIT 96
                """, (rs, index) -> new TrackingRow(rs.getString("observation_id"), rs.getString("inst_id"),
                DirectionBias.valueOf(rs.getString("direction")), rs.getBigDecimal("reference_price"),
                rs.getTimestamp("skipped_at").toInstant(),
                List.of(rs.getString("outcome_5m") != null, rs.getString("outcome_15m") != null,
                        rs.getString("outcome_30m") != null)), timestamp(now));
        var bySymbol = new LinkedHashMap<String, List<TrackingRow>>();
        for (TrackingRow row : due) {
            if (bySymbol.size() < SYMBOLS_PER_TICK || bySymbol.containsKey(row.instId())) {
                bySymbol.computeIfAbsent(row.instId(), ignored -> new java.util.ArrayList<>()).add(row);
            }
        }
        for (var group : bySymbol.entrySet()) {
            List<ContractCandle> candles;
            try {
                candles = market.candles(group.getKey(), "1m", 300, Duration.ofSeconds(4));
            } catch (RuntimeException ex) {
                log.warn("AI entry tracking candle request failed instId={} errorType={}", group.getKey(),
                        ex.getClass().getSimpleName());
                if (ex.getMessage() != null && (ex.getMessage().startsWith("OKX HTTP 429")
                        || ex.getMessage().contains("code=50011"))) {
                    backoffUntil = Instant.now().plusSeconds(300);
                    break;
                }
                candles = List.of();
            }
            for (TrackingRow row : group.getValue()) {
                try {
                    update(row, candles, now);
                } catch (Exception ex) {
                    log.warn("AI entry tracking update failed id={} errorType={}", row.id(), ex.getClass().getSimpleName());
                }
            }
        }
    }

    private void update(TrackingRow row, List<ContractCandle> candles, Instant now) throws Exception {
        String[] outcomes = new String[HORIZONS.size()];
        Instant next = null;
        for (int i = 0; i < HORIZONS.size(); i++) {
            if (row.measured().get(i)) continue;
            int minutes = HORIZONS.get(i);
            Instant due = row.skippedAt().plusSeconds(minutes * 60L);
            if (!due.isAfter(now)) {
                var result = AiEntryCounterfactual.measure(row.skippedAt(), minutes, row.price(), row.direction(), candles);
                if (result.isPresent()) {
                    outcomes[i] = mapper.writeValueAsString(result.get());
                    continue;
                }
                due = now.plusSeconds(60);
            }
            if (next == null || due.isBefore(next)) next = due;
        }
        jdbc.update("""
                UPDATE ai_entry_counterfactual SET outcome_5m=COALESCE(outcome_5m, ?),
                  outcome_15m=COALESCE(outcome_15m, ?), outcome_30m=COALESCE(outcome_30m, ?),
                  status=?, next_check_at=? WHERE observation_id=? AND status='PENDING'
                """, outcomes[0], outcomes[1], outcomes[2], next == null ? "COMPLETE" : "PENDING",
                timestamp(next == null ? now : next), row.id());
    }

    private record TrackingRow(String id, String instId, DirectionBias direction, BigDecimal price,
                               Instant skippedAt, List<Boolean> measured) {}

    private static JsonNode answer(JsonNode response, String question, String group) {
        if (response == null) return null;
        JsonNode node = response.path("answers").path(question);
        return node.isMissingNode() ? response.path(group).path(question) : node;
    }

    private static BigDecimal number(JsonNode node, String field) {
        if (node == null || !node.path(field).isNumber()) return null;
        return node.path(field).decimalValue();
    }

    private static String value(JsonNode response, JsonNode answer, String field, boolean typed) {
        if (response == null) return null;
        return typed ? answer.path("choice").asText(null) : response.path(field).asText(null);
    }

    private static String bounded(String text, int limit) {
        return text == null || text.length() <= limit ? text : text.substring(0, limit);
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Timestamp timestamp(Instant time) {
        return Timestamp.from(time);
    }

    private static ThreadPoolExecutor executor(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void offer(ThreadPoolExecutor executor, Work work, String operation, String id) {
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } catch (Exception ex) {
                    log.warn("AI entry observation {} failed id={} errorType={}", operation, id, ex.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException ex) {
            log.warn("AI entry observation {} dropped id={} errorType={}", operation, id, ex.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface Work { void run() throws Exception; }

    @PreDestroy
    void stop() {
        writes.shutdown();
        tracking.shutdownNow();
    }
}

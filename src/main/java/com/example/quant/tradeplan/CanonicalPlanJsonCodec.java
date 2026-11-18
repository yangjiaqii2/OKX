package com.example.quant.tradeplan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;

/** Small deterministic codec used for the immutable pending-order snapshot. */
public final class CanonicalPlanJsonCodec {
    public static final String SCHEMA_VERSION = "canonical-plan-schema-v1";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .findAndRegisterModules();

    private CanonicalPlanJsonCodec() {}

    public static String write(CanonicalExecutablePlan plan) {
        return write(plan, Instant.now());
    }

    /**
     * Serialize with an explicit compile timestamp so all persistence targets
     * can retain the exact same immutable snapshot metadata.
     */
    public static String write(CanonicalExecutablePlan plan, Instant compiledAt) {
        if (compiledAt == null) {
            throw new IllegalArgumentException("compiledAt 不能为空");
        }
        try {
            var wrapper = MAPPER.createObjectNode();
            wrapper.put("schemaVersion", SCHEMA_VERSION);
            wrapper.put("compilerVersion", plan.compilerVersion());
            wrapper.put("compiledAt", compiledAt.toString());
            wrapper.put("plan", MAPPER.writeValueAsString(plan));
            return MAPPER.writeValueAsString(wrapper);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化 canonical executable plan", ex);
        }
    }

    public static CanonicalExecutablePlan read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.has("schemaVersion")) {
                if (!SCHEMA_VERSION.equals(root.path("schemaVersion").asText(""))) {
                    throw new IllegalStateException("未知 canonical plan schemaVersion: "
                            + root.path("schemaVersion").asText(""));
                }
                JsonNode planNode = root.path("plan");
                if (planNode.isTextual()) {
                    return MAPPER.readValue(planNode.asText(), CanonicalExecutablePlan.class);
                }
                root = planNode;
            }
            return MAPPER.treeToValue(root, CanonicalExecutablePlan.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法恢复 canonical executable plan", ex);
        }
    }
}

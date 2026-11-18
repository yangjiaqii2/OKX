package com.example.quant.agent.exit;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable output of {@link ProfitProtectionEngine}. Carries the full evidence
 * set so that every protective-stop change is explainable from logs and so that
 * the same input can be replayed offline for parameter search.
 */
public record ExitDecision(
        ExitAction action,
        String reason,
        BigDecimal targetProtectiveStop,
        PositionExitState fromState,
        PositionExitState toState,
        Map<String, String> evidence
) {
    public ExitDecision {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(reason, "reason");
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    public static ExitDecision noOp(String reason, PositionExitState state, Map<String, String> evidence) {
        return new ExitDecision(ExitAction.NO_OP, reason, null, state, state, evidence);
    }

    public static ExitDecision hold(String reason, PositionExitState from, PositionExitState to,
                                    Map<String, String> evidence) {
        return new ExitDecision(ExitAction.HOLD, reason, null, from, to, evidence);
    }

    public static ExitDecision tighten(String reason, BigDecimal target, PositionExitState from,
                                       PositionExitState to, Map<String, String> evidence) {
        return new ExitDecision(ExitAction.TIGHTEN_STOP, reason, target, from, to, evidence);
    }

    public boolean tightens() {
        return action == ExitAction.TIGHTEN_STOP;
    }

    /** Mutable builder used only while assembling evidence inside the engine. */
    public static Evidence evidenceOf() {
        return new Evidence();
    }

    public static final class Evidence {
        private final Map<String, String> values = new LinkedHashMap<>();

        public Evidence put(String key, BigDecimal value) {
            values.put(key, value == null ? "null" : value.stripTrailingZeros().toPlainString());
            return this;
        }

        public Evidence put(String key, String value) {
            values.put(key, value == null ? "null" : value);
            return this;
        }

        public Evidence put(String key, Number value) {
            values.put(key, String.valueOf(value));
            return this;
        }

        public Map<String, String> build() {
            return Map.copyOf(values);
        }
    }
}

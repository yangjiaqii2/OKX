package com.example.quant.agent.lifecycle;

import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AutoTradeLifecycleService {
    private final PendingOrderService pendingOrderService;
    private final AutoTradeBudgetService budgetService;
    private final AgentProperties agentProperties;
    private final OkxOrderGateway okxOrderGateway;
    private final PositionSnapshotService positionSnapshotService;

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService) {
        this.pendingOrderService = pendingOrderService;
        this.budgetService = budgetService;
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
        this.okxOrderGateway = okxOrderGateway;
        this.positionSnapshotService = positionSnapshotService;
    }

    public LifecycleRunResult runOnce() {
        return runOnce(Instant.now());
    }

    public LifecycleRunResult runOnce(Instant now) {
        int entryTimeoutCancelled = 0;
        int partialFillProtected = 0;
        int sidewaysTpAdjusted = 0;
        int maxHoldCloseSubmitted = 0;
        List<PositionSummary> positions = positions();
        for (PendingOrder order : pendingOrderService.allOrders()) {
            if (order.submittedAt() == null) {
                continue;
            }
            PositionSummary position = matchingPosition(order, positions);
            Duration age = Duration.between(order.submittedAt(), now);
            if (position != null && shouldMaxHoldClose(order, age)) {
                maxHoldCloseSubmitted += submitMaxHoldClose(order);
                continue;
            }
            if (position != null && shouldAdjustSideways(order, position, age)) {
                sidewaysTpAdjusted += tightenTakeProfit(order, position);
                continue;
            }
            if (position == null && shouldCancelStaleEntry(order, age)) {
                EntryFill fill = queryEntryFill(order);
                cancelEntryOrder(order);
                if (fill.filledSize().signum() <= 0) {
                    order.markEntryTimeoutCancelled("ENTRY_TIMEOUT_CANCELLED");
                    if (order.budgetReservationId() != null) {
                        budgetService.release(order.budgetReservationId(), "ENTRY_TIMEOUT_CANCELLED");
                    }
                    entryTimeoutCancelled++;
                } else {
                    submitProtection(order, fill.avgPx(), fill.filledSize(), "PARTIAL_FILL_ENTRY_TIMEOUT");
                    if (order.budgetReservationId() != null) {
                        budgetService.markUsed(order.budgetReservationId());
                    }
                    order.markProtectionSubmitted("PARTIAL_FILL_PROTECTION_SUBMITTED");
                    partialFillProtected++;
                }
            }
        }
        return new LifecycleRunResult(entryTimeoutCancelled, partialFillProtected, sidewaysTpAdjusted, maxHoldCloseSubmitted);
    }

    public List<AutoTradeLifecycleSnapshot> snapshots() {
        List<PositionSummary> positions = positions();
        Instant now = Instant.now();
        return pendingOrderService.allOrders().stream()
                .filter(order -> order.budgetReservationId() != null || order.clientOrderId() != null)
                .map(order -> {
                    PositionSummary position = matchingPosition(order, positions);
                    Instant entryTime = order.submittedAt() == null ? order.createdAt() : order.submittedAt();
                    BudgetReservation reservation = order.budgetReservationId() == null
                            ? null
                            : budgetService.reservation(order.budgetReservationId()).orElse(null);
                    BigDecimal budgetUsed = reservation == null ? order.marginAmount() : reservation.amount();
                    return new AutoTradeLifecycleSnapshot(
                            order.instId(),
                            order.posSide(),
                            entryTime,
                            entryTime == null ? Duration.ZERO : Duration.between(entryTime, now),
                            order.price(),
                            position == null ? BigDecimal.ZERO : position.avgPrice(),
                            position == null ? BigDecimal.ZERO : position.unrealizedPnl(),
                            position == null ? BigDecimal.ZERO : pnlPct(position),
                            budgetUsed,
                            order.status().name(),
                            protectionStatus(order),
                            lifecycleStatus(order, position),
                            nextAction(order)
                    );
                })
                .toList();
    }

    private boolean shouldCancelStaleEntry(PendingOrder order, Duration age) {
        return order.status() == OrderStatus.SUBMITTED
                && age.toMinutes() >= Math.max(1, agentProperties.lifecycle().entryTimeoutMinutes());
    }

    private boolean shouldAdjustSideways(PendingOrder order, PositionSummary position, Duration age) {
        if (order.status() == OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED
                || order.status() == OrderStatus.CLOSE_SUBMITTED
                || order.status() == OrderStatus.CLOSED) {
            return false;
        }
        if (age.toHours() < Math.max(1, agentProperties.lifecycle().sidewaysPositionHours())) {
            return false;
        }
        BigDecimal pnlPct = pnlPct(position);
        return pnlPct.abs().compareTo(agentProperties.lifecycle().sidewaysPnlRangePct()) <= 0;
    }

    private boolean shouldMaxHoldClose(PendingOrder order, Duration age) {
        if (order.status() == OrderStatus.CLOSE_SUBMITTED || order.status() == OrderStatus.CLOSED) {
            return false;
        }
        return age.toHours() >= Math.max(1, agentProperties.lifecycle().maxHoldHours());
    }

    private int tightenTakeProfit(PendingOrder order, PositionSummary position) {
        BigDecimal avgPx = positive(position.avgPrice(), order.price());
        BigDecimal size = decimal(position.size());
        BigDecimal triggerPx = smallProfitTakeProfit(order.posSide(), avgPx);
        Map<String, String> payload = algoPayload(order, size, "SIDEWAYS_TP");
        payload.put("tpTriggerPx", value(triggerPx));
        payload.put("tpOrdPx", "-1");
        okxOrderGateway.placeAlgoOrder(payload);
        order.markSidewaysTimeoutTpAdjusted("SIDEWAYS_TIMEOUT_TP_ADJUSTED");
        return 1;
    }

    private int submitMaxHoldClose(PendingOrder order) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("mgnMode", order.tdMode());
        payload.put("autoCxl", "true");
        if (hasText(order.posSide()) && !"net".equalsIgnoreCase(order.posSide())) {
            payload.put("posSide", order.posSide());
        }
        try {
            JsonNode response = okxOrderGateway.closePosition(payload);
            order.markCloseSubmitted(closeId(response), "MAX_HOLD_TIMEOUT_CLOSE_SUBMITTED");
            return 1;
        } catch (RuntimeException ex) {
            order.markEmergencyAttentionRequired("MAX_HOLD_TIMEOUT_CLOSE_FAILED: " + ex.getMessage());
            return 0;
        }
    }

    private void submitProtection(PendingOrder order, BigDecimal avgPx, BigDecimal filledSize, String suffix) {
        OkxInstrumentRules rules = OkxInstrumentRules.defaultFor(order.instId());
        BigDecimal normalizedSize = rules.floorToLot(filledSize);
        for (Map<String, String> payload : protectionPayloads(order, rules, normalizedSize, avgPx, suffix)) {
            okxOrderGateway.placeAlgoOrder(payload);
        }
    }

    private List<Map<String, String>> protectionPayloads(PendingOrder order, OkxInstrumentRules rules,
                                                         BigDecimal filledSize, BigDecimal avgPx, String suffix) {
        if (filledSize == null || filledSize.signum() <= 0) {
            return List.of();
        }
        List<Map<String, String>> payloads = new ArrayList<>();
        Map<String, String> stopLoss = algoPayload(order, filledSize, suffix + "_SL");
        stopLoss.put("slTriggerPx", value(rules.normalizePrice(order.stopLossPrice())));
        stopLoss.put("slOrdPx", "-1");
        payloads.add(stopLoss);

        BigDecimal risk = positive(avgPx, order.price()).subtract(order.stopLossPrice()).abs();
        for (TakeProfitLeg leg : takeProfitLegs(filledSize, rules)) {
            BigDecimal triggerPx = takeProfitPrice(order, positive(avgPx, order.price()), risk, leg.rMultiple());
            Map<String, String> takeProfit = algoPayload(order, leg.size(), suffix + "_" + leg.suffix());
            takeProfit.put("tpTriggerPx", value(rules.normalizePrice(triggerPx)));
            takeProfit.put("tpOrdPx", "-1");
            payloads.add(takeProfit);
        }
        return payloads;
    }

    private List<TakeProfitLeg> takeProfitLegs(BigDecimal filledSize, OkxInstrumentRules rules) {
        BigDecimal tp1 = rules.floorToLot(filledSize.multiply(ratio(agentProperties.takeProfit().tp1Ratio())));
        BigDecimal tp2 = rules.floorToLot(filledSize.multiply(ratio(agentProperties.takeProfit().tp2Ratio())));
        if (tp1.compareTo(rules.minSize()) < 0) {
            tp1 = BigDecimal.ZERO;
        }
        if (tp2.compareTo(rules.minSize()) < 0) {
            tp2 = BigDecimal.ZERO;
        }
        BigDecimal tp3 = agentProperties.takeProfit().finalTpUseRemainingSize()
                ? rules.floorToLot(filledSize.subtract(tp1).subtract(tp2))
                : rules.floorToLot(filledSize.multiply(ratio(agentProperties.takeProfit().tp3Ratio())));
        if (tp3.compareTo(rules.minSize()) < 0) {
            if (tp2.compareTo(rules.minSize()) >= 0) {
                tp2 = tp2.add(tp3);
            } else if (tp1.compareTo(rules.minSize()) >= 0) {
                tp1 = tp1.add(tp3);
            } else {
                tp3 = filledSize;
            }
            if (!filledSize.equals(tp3)) {
                tp3 = BigDecimal.ZERO;
            }
        }
        List<TakeProfitLeg> legs = new ArrayList<>();
        if (tp1.compareTo(rules.minSize()) >= 0) {
            legs.add(new TakeProfitLeg("TP1", tp1, BigDecimal.ONE));
        }
        if (tp2.compareTo(rules.minSize()) >= 0) {
            legs.add(new TakeProfitLeg("TP2", tp2, BigDecimal.valueOf(2)));
        }
        if (tp3.compareTo(rules.minSize()) >= 0) {
            legs.add(new TakeProfitLeg("TP3", tp3, BigDecimal.valueOf(3)));
        }
        return legs;
    }

    private static BigDecimal takeProfitPrice(PendingOrder order, BigDecimal avgPx, BigDecimal risk, BigDecimal multiple) {
        BigDecimal distance = risk.multiply(multiple);
        return "short".equalsIgnoreCase(order.posSide()) ? avgPx.subtract(distance) : avgPx.add(distance);
    }

    private static BigDecimal ratio(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Map<String, String> algoPayload(PendingOrder order, BigDecimal size, String suffix) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("tdMode", order.tdMode());
        payload.put("side", closeSide(order));
        payload.put("posSide", order.posSide());
        payload.put("ordType", "conditional");
        payload.put("reduceOnly", "true");
        payload.put("sz", value(size));
        payload.put("algoClOrdId", lifecycleAlgoClOrdId(order, suffix));
        return payload;
    }

    private EntryFill queryEntryFill(PendingOrder order) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        if (hasText(order.externalOrderId())) {
            payload.put("ordId", order.externalOrderId());
        } else if (hasText(order.clientOrderId())) {
            payload.put("clOrdId", order.clientOrderId());
        }
        JsonNode item = firstDataItem(okxOrderGateway.queryOrder(payload));
        if (item == null) {
            return new EntryFill(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return new EntryFill(decimal(item, "avgPx"), decimal(item, "accFillSz"));
    }

    private void cancelEntryOrder(PendingOrder order) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        if (hasText(order.externalOrderId())) {
            payload.put("ordId", order.externalOrderId());
        } else if (hasText(order.clientOrderId())) {
            payload.put("clOrdId", order.clientOrderId());
        }
        okxOrderGateway.cancelOrder(payload);
    }

    private PositionSummary matchingPosition(PendingOrder order, List<PositionSummary> positions) {
        return positions.stream()
                .filter(position -> order.instId().equalsIgnoreCase(position.instId()))
                .filter(position -> !hasText(order.posSide()) || order.posSide().equalsIgnoreCase(position.posSide()))
                .filter(position -> decimal(position.size()).signum() > 0)
                .findFirst()
                .orElse(null);
    }

    private static String protectionStatus(PendingOrder order) {
        return switch (order.status()) {
            case PROTECTION_SUBMITTED, ACTIVE, ACTIVE_WITH_TP_WARNING, SIDEWAYS_TIMEOUT_TP_ADJUSTED -> "PROTECTION_SUBMITTED";
            case PROTECTION_FAILED, EMERGENCY_ATTENTION_REQUIRED -> "PROTECTION_FAILED";
            default -> "PROTECTION_PENDING";
        };
    }

    private static String lifecycleStatus(PendingOrder order, PositionSummary position) {
        if (order.status() == OrderStatus.SUBMITTED && position == null) {
            return "ENTRY_PENDING";
        }
        if (order.status() == OrderStatus.SUBMITTED && position != null) {
            return "NORMAL";
        }
        return order.status().name();
    }

    private static String nextAction(PendingOrder order) {
        return switch (order.status()) {
            case SUBMITTED -> "WAIT_ENTRY_FILL_OR_TIMEOUT";
            case ENTRY_TIMEOUT_CANCELLED -> "FALLBACK_NEXT_CANDIDATE_ALLOWED";
            case SIDEWAYS_TIMEOUT_TP_ADJUSTED -> "WAIT_SMALL_PROFIT_TP_OR_MAX_HOLD";
            case CLOSE_SUBMITTED -> "WAIT_CLOSE_SYNC";
            case EMERGENCY_ATTENTION_REQUIRED -> "MANUAL_ATTENTION_REQUIRED";
            default -> "MONITOR";
        };
    }

    private List<PositionSummary> positions() {
        if (positionSnapshotService == null) {
            return List.of();
        }
        try {
            return positionSnapshotService.positions();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private BigDecimal smallProfitTakeProfit(String posSide, BigDecimal avgPx) {
        BigDecimal profitRate = agentProperties.lifecycle().sidewaysExitProfitPct()
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal factor = "short".equalsIgnoreCase(posSide)
                ? BigDecimal.ONE.subtract(profitRate)
                : BigDecimal.ONE.add(profitRate);
        return avgPx.multiply(factor).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static BigDecimal pnlPct(PositionSummary position) {
        BigDecimal notional = position.notionalUsd() == null ? BigDecimal.ZERO : position.notionalUsd().abs();
        if (notional.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal pnl = position.unrealizedPnl() == null ? BigDecimal.ZERO : position.unrealizedPnl();
        return pnl.multiply(BigDecimal.valueOf(100)).divide(notional, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal positive(BigDecimal preferred, BigDecimal fallback) {
        return preferred != null && preferred.signum() > 0 ? preferred : fallback;
    }

    private static String closeSide(PendingOrder order) {
        return "buy".equalsIgnoreCase(order.side()) ? "sell" : "buy";
    }

    private static String lifecycleAlgoClOrdId(PendingOrder order, String suffix) {
        String base = order.id().toString().replace("-", "");
        String cleanSuffix = suffix == null ? "" : suffix.replaceAll("[^A-Za-z0-9]", "");
        String value = "lc" + base.substring(0, Math.min(20, base.length())) + cleanSuffix;
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    private static JsonNode firstDataItem(JsonNode response) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return null;
        }
        return data.get(0);
    }

    private static String closeId(JsonNode response) {
        JsonNode item = firstDataItem(response);
        return item == null ? null : item.path("ordId").asText("");
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null) {
            return BigDecimal.ZERO;
        }
        return decimal(node.path(field).asText(""));
    }

    private static BigDecimal decimal(String value) {
        if (!hasText(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value).abs();
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static String value(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record EntryFill(BigDecimal avgPx, BigDecimal filledSize) {
    }

    private record TakeProfitLeg(String suffix, BigDecimal size, BigDecimal rMultiple) {
    }

    public record LifecycleRunResult(
            int entryTimeoutCancelled,
            int partialFillProtected,
            int sidewaysTpAdjusted,
            int maxHoldCloseSubmitted
    ) {
    }
}

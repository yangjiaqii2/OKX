package com.example.quant.agent.budget;

import com.example.quant.config.AgentProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AutoTradeBudgetService {
    private final AgentProperties agentProperties;
    private final Map<UUID, BudgetReservation> reservations = new LinkedHashMap<>();

    public AutoTradeBudgetService(AgentProperties agentProperties) {
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
    }

    public synchronized BudgetAllocation allocate(BudgetAllocationRequest request) {
        AgentProperties.Budget config = agentProperties.budget();
        BigDecimal totalBudget = money(request.totalBudgetUsdt());
        BigDecimal target = pctAmount(totalBudget, config.targetUtilizationPct());
        BigDecimal minTarget = pctAmount(totalBudget, config.minTargetUtilizationPct());
        BigDecimal maxUtilization = pctAmount(totalBudget, config.maxUtilizationPct());
        BigDecimal usedBefore = usedBudget();
        BigDecimal reservedBefore = config.reserveInflightBudget() ? reservedBudget() : BigDecimal.ZERO;
        BigDecimal remaining = totalBudget.subtract(usedBefore).subtract(reservedBefore).max(BigDecimal.ZERO);
        BigDecimal slotWeight = config.slotWeight(request.slotIndex());
        BigDecimal slotBudget = totalBudget.multiply(slotWeight).setScale(8, RoundingMode.HALF_UP);
        BigDecimal scoreFactor = config.scoreFactor(request.score());
        BigDecimal qualityAdjusted = slotBudget.multiply(scoreFactor).setScale(8, RoundingMode.HALF_UP);
        BigDecimal riskBasedMax = positiveOrMax(request.riskBasedMaxMarginUsdt());
        BigDecimal maxSingle = pctAmount(totalBudget, config.maxSinglePositionBudgetPct());
        BigDecimal finalMargin = min(slotBudget, riskBasedMax, remaining, maxSingle);
        if (scoreFactor.signum() <= 0) {
            finalMargin = BigDecimal.ZERO;
        }
        finalMargin = finalMargin.max(BigDecimal.ZERO).setScale(8, RoundingMode.DOWN).stripTrailingZeros();
        List<String> underReasons = allocationReasons(config, request, finalMargin, riskBasedMax, maxSingle);
        BigDecimal utilizationAfter = totalBudget.signum() <= 0
                ? BigDecimal.ZERO
                : usedBefore.add(reservedBefore).add(finalMargin)
                .multiply(BigDecimal.valueOf(100))
                .divide(totalBudget, 8, RoundingMode.HALF_UP);
        String status = finalMargin.compareTo(config.minOrderMarginUsdt()) < 0 ? "MIN_ORDER_MARGIN_LIMIT" : "OK";
        return new BudgetAllocation(
                totalBudget,
                target.stripTrailingZeros(),
                minTarget.stripTrailingZeros(),
                maxUtilization.stripTrailingZeros(),
                usedBefore.stripTrailingZeros(),
                reservedBefore.stripTrailingZeros(),
                remaining.stripTrailingZeros(),
                request.slotIndex(),
                slotWeight.stripTrailingZeros(),
                slotBudget.stripTrailingZeros(),
                scoreFactor.stripTrailingZeros(),
                qualityAdjusted.stripTrailingZeros(),
                riskBasedMax.stripTrailingZeros(),
                maxSingle.stripTrailingZeros(),
                BigDecimal.ZERO,
                finalMargin,
                utilizationAfter,
                config.allocationMode(),
                status,
                underReasons,
                "根据" + totalBudget.stripTrailingZeros().toPlainString() + "U总预算、第" + request.slotIndex()
                        + "仓" + slotWeight.stripTrailingZeros().toPlainString() + "、评分"
                        + request.score().stripTrailingZeros().toPlainString() + "，最终分配"
                        + finalMargin.toPlainString() + "U"
        );
    }

    public synchronized BudgetReservation reserveBudget(UUID planId, UUID pendingOrderId, String symbol,
                                                        BigDecimal amount, BigDecimal totalBudgetUsdt) {
        BigDecimal normalized = money(amount);
        if (normalized.signum() <= 0) {
            return rejected(planId, pendingOrderId, symbol, normalized, "BUDGET_ORDER_MARGIN_BELOW_MIN");
        }
        BigDecimal totalBudget = money(totalBudgetUsdt);
        BigDecimal remaining = totalBudget.subtract(usedBudget()).subtract(reservedBudget());
        if (remaining.compareTo(normalized) < 0) {
            return rejected(planId, pendingOrderId, symbol, normalized, "TOTAL_BUDGET_EXHAUSTED");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        BudgetReservation reservation = new BudgetReservation(id, planId, pendingOrderId, symbol, normalized,
                BudgetReservationStatus.RESERVED, now, now, "RESERVED");
        reservations.put(id, reservation);
        return reservation;
    }

    public synchronized Optional<BudgetReservation> reservation(UUID reservationId) {
        return Optional.ofNullable(reservations.get(reservationId));
    }

    public synchronized Optional<BudgetReservation> reservationByPendingOrder(UUID pendingOrderId) {
        return reservations.values().stream()
                .filter(item -> pendingOrderId != null && pendingOrderId.equals(item.pendingOrderId()))
                .findFirst();
    }

    public synchronized boolean isReserved(UUID reservationId, UUID pendingOrderId) {
        return reservation(reservationId)
                .filter(item -> item.status() == BudgetReservationStatus.RESERVED)
                .filter(item -> pendingOrderId == null || pendingOrderId.equals(item.pendingOrderId()))
                .isPresent();
    }

    public synchronized BudgetReservation markUsed(UUID reservationId) {
        BudgetReservation current = reservations.get(reservationId);
        if (current == null || current.status() == BudgetReservationStatus.USED) {
            return current;
        }
        if (current.status() != BudgetReservationStatus.RESERVED) {
            return current;
        }
        BudgetReservation updated = withStatus(current, BudgetReservationStatus.USED, "USED");
        reservations.put(reservationId, updated);
        return updated;
    }

    public synchronized BudgetReservation release(UUID reservationId, String reason) {
        BudgetReservation current = reservations.get(reservationId);
        if (current == null || current.status() == BudgetReservationStatus.RELEASED) {
            return current;
        }
        BudgetReservation updated = withStatus(current, BudgetReservationStatus.RELEASED,
                reason == null || reason.isBlank() ? "RELEASED" : reason);
        reservations.put(reservationId, updated);
        return updated;
    }

    public synchronized BudgetStatusSnapshot snapshot(BigDecimal totalBudgetUsdt, int allowedSlots,
                                                      List<String> underUtilizedReasons) {
        AgentProperties.Budget config = agentProperties.budget();
        BigDecimal totalBudget = money(totalBudgetUsdt);
        BigDecimal used = usedBudget();
        BigDecimal reserved = reservedBudget();
        BigDecimal remaining = totalBudget.subtract(used).subtract(reserved).max(BigDecimal.ZERO);
        BigDecimal utilization = totalBudget.signum() <= 0
                ? BigDecimal.ZERO
                : used.add(reserved).multiply(BigDecimal.valueOf(100)).divide(totalBudget, 8, RoundingMode.HALF_UP);
        String status;
        if (utilization.compareTo(config.maxUtilizationPct()) >= 0
                || remaining.compareTo(config.minOrderMarginUsdt()) < 0) {
            status = "FULL";
        } else if (utilization.compareTo(config.minTargetUtilizationPct()) >= 0) {
            status = "OK";
        } else if (activeReservationCount() >= allowedSlots) {
            status = "UNDER_UTILIZED";
        } else {
            status = "PARTIALLY_USED";
        }
        List<String> reasons = "UNDER_UTILIZED".equals(status)
                ? new ArrayList<>(underUtilizedReasons == null || underUtilizedReasons.isEmpty()
                ? List.of("NO_ENOUGH_QUALIFIED_CANDIDATES")
                : underUtilizedReasons)
                : List.of();
        return new BudgetStatusSnapshot(
                totalBudget,
                pctAmount(totalBudget, config.targetUtilizationPct()).stripTrailingZeros(),
                pctAmount(totalBudget, config.minTargetUtilizationPct()).stripTrailingZeros(),
                used.stripTrailingZeros(),
                reserved.stripTrailingZeros(),
                remaining.stripTrailingZeros(),
                utilization,
                status,
                reasons
        );
    }

    public synchronized BigDecimal reservedBudget() {
        return reservations.values().stream()
                .filter(item -> item.status() == BudgetReservationStatus.RESERVED)
                .map(BudgetReservation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public synchronized BigDecimal usedBudget() {
        return reservations.values().stream()
                .filter(item -> item.status() == BudgetReservationStatus.USED)
                .map(BudgetReservation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int activeReservationCount() {
        return (int) reservations.values().stream()
                .filter(item -> item.status() == BudgetReservationStatus.RESERVED
                        || item.status() == BudgetReservationStatus.USED)
                .count();
    }

    private BudgetReservation rejected(UUID planId, UUID pendingOrderId, String symbol, BigDecimal amount, String reason) {
        Instant now = Instant.now();
        return new BudgetReservation(UUID.randomUUID(), planId, pendingOrderId, symbol, amount,
                BudgetReservationStatus.RELEASED, now, now, reason);
    }

    private static BudgetReservation withStatus(BudgetReservation current, BudgetReservationStatus status, String reason) {
        return new BudgetReservation(current.reservationId(), current.planId(), current.pendingOrderId(),
                current.symbol(), current.amount(), status, current.createdAt(), Instant.now(), reason);
    }

    private static List<String> allocationReasons(AgentProperties.Budget config, BudgetAllocationRequest request,
                                                  BigDecimal finalMargin, BigDecimal riskBasedMax, BigDecimal maxSingle) {
        List<String> reasons = new ArrayList<>();
        if (config.scoreFactor(request.score()).signum() <= 0) {
            reasons.add("LOW_SIGNAL_SCORE");
        }
        if (riskBasedMax.compareTo(finalMargin) <= 0) {
            reasons.add("RISK_BASED_MARGIN_LIMIT");
        }
        if (maxSingle.compareTo(finalMargin) <= 0) {
            reasons.add("MAX_SINGLE_POSITION_LIMIT");
        }
        if (finalMargin.compareTo(config.minOrderMarginUsdt()) < 0) {
            reasons.add("MIN_ORDER_MARGIN_LIMIT");
        }
        return reasons;
    }

    private static BigDecimal pctAmount(BigDecimal amount, BigDecimal percent) {
        if (amount == null || percent == null) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(percent).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal positiveOrMax(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return new BigDecimal("999999999");
        }
        return value;
    }

    private static BigDecimal min(BigDecimal first, BigDecimal... rest) {
        BigDecimal result = first;
        for (BigDecimal value : rest) {
            if (value.compareTo(result) < 0) {
                result = value;
            }
        }
        return result;
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.max(BigDecimal.ZERO).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}

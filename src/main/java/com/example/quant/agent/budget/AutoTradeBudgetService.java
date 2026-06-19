package com.example.quant.agent.budget;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AutoTradeBudgetService {
    private final AgentProperties agentProperties;
    private final Map<UUID, BudgetReservation> reservations = new LinkedHashMap<>();
    private final BudgetReservationRepository repository;

    public AutoTradeBudgetService(AgentProperties agentProperties) {
        this(agentProperties, null);
    }

    @Autowired
    public AutoTradeBudgetService(AgentProperties agentProperties, BudgetReservationRepository repository) {
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
        this.repository = repository;
        loadActiveReservations();
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
        BudgetReservation reservation = new BudgetReservation(id, planId, pendingOrderId, currentUserName(), symbol, normalized,
                BudgetReservationStatus.RESERVED, now, now, "RESERVED");
        reservations.put(id, reservation);
        save(reservation);
        return reservation;
    }

    public synchronized Optional<BudgetReservation> reservation(UUID reservationId) {
        if (reservationId == null) {
            return Optional.empty();
        }
        BudgetReservation current = reservations.get(reservationId);
        if (current != null) {
            return Optional.of(current);
        }
        if (repository == null) {
            return Optional.empty();
        }
        return findEntity(reservationId).map(this::fromEntity);
    }

    public synchronized Optional<BudgetReservation> reservationByPendingOrder(UUID pendingOrderId) {
        if (pendingOrderId == null) {
            return Optional.empty();
        }
        if (repository != null) {
            Optional<BudgetReservationEntity> row = repository.findFirstByUserNameAndPendingOrderId(
                    currentUserName(), pendingOrderId.toString());
            return row == null ? Optional.empty() : row.map(this::fromEntity);
        }
        return reservations.values().stream()
                .filter(item -> currentUserName().equals(item.userName()))
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
        BudgetReservation current = reservation(reservationId).orElse(null);
        if (current == null || current.status() == BudgetReservationStatus.USED) {
            return current;
        }
        if (current.status() != BudgetReservationStatus.RESERVED) {
            return current;
        }
        BudgetReservation updated = withStatus(current, BudgetReservationStatus.USED, "USED");
        reservations.put(reservationId, updated);
        save(updated);
        return updated;
    }

    public synchronized BudgetReservation release(UUID reservationId, String reason) {
        BudgetReservation current = reservation(reservationId).orElse(null);
        if (current == null || current.status() == BudgetReservationStatus.RELEASED) {
            return current;
        }
        BudgetReservation updated = withStatus(current, BudgetReservationStatus.RELEASED,
                reason == null || reason.isBlank() ? "RELEASED" : reason);
        reservations.put(reservationId, updated);
        save(updated);
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
        return activeReservations().stream()
                .filter(item -> item.status() == BudgetReservationStatus.RESERVED)
                .map(BudgetReservation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public synchronized BigDecimal usedBudget() {
        return activeReservations().stream()
                .filter(item -> item.status() == BudgetReservationStatus.USED)
                .map(BudgetReservation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int activeReservationCount() {
        return (int) activeReservations().stream()
                .filter(item -> item.status() == BudgetReservationStatus.RESERVED
                        || item.status() == BudgetReservationStatus.USED)
                .count();
    }

    private List<BudgetReservation> activeReservations() {
        if (repository == null) {
            return new ArrayList<>(reservations.values()).stream()
                    .filter(item -> currentUserName().equals(item.userName()))
                    .toList();
        }
        List<BudgetReservationEntity> rows = repository.findByUserNameAndStatusIn(
                currentUserName(), List.of("RESERVED", "USED"));
        if (rows == null) {
            return new ArrayList<>(reservations.values()).stream()
                    .filter(item -> currentUserName().equals(item.userName()))
                    .toList();
        }
        List<BudgetReservation> active = rows.stream()
                .map(this::fromEntity)
                .toList();
        Map<UUID, BudgetReservation> merged = new LinkedHashMap<>();
        for (BudgetReservation reservation : active) {
            merged.put(reservation.reservationId(), reservation);
        }
        reservations.values().stream()
                .filter(item -> currentUserName().equals(item.userName()))
                .filter(AutoTradeBudgetService::active)
                .forEach(item -> merged.put(item.reservationId(), item));
        for (BudgetReservation reservation : merged.values()) {
            reservations.put(reservation.reservationId(), reservation);
        }
        return new ArrayList<>(merged.values());
    }

    private void loadActiveReservations() {
        if (repository == null) {
            return;
        }
        for (BudgetReservation reservation : activeReservations()) {
            reservations.put(reservation.reservationId(), reservation);
        }
    }

    private void save(BudgetReservation reservation) {
        if (repository == null || reservation == null) {
            return;
        }
        BudgetReservationEntity entity = findEntity(reservation.reservationId()).orElseGet(BudgetReservationEntity::new);
        entity.setReservationId(reservation.reservationId().toString());
        entity.setPlanId(reservation.planId() == null ? null : reservation.planId().toString());
        entity.setPendingOrderId(reservation.pendingOrderId() == null ? null : reservation.pendingOrderId().toString());
        entity.setUserName(reservation.userName());
        entity.setSymbol(reservation.symbol());
        entity.setAmount(reservation.amount());
        entity.setStatus(reservation.status().name());
        entity.setReason(reservation.reason());
        entity.setCreatedAt(reservation.createdAt());
        entity.setUpdatedAt(reservation.updatedAt());
        repository.save(entity);
    }

    private Optional<BudgetReservationEntity> findEntity(UUID reservationId) {
        Optional<BudgetReservationEntity> row = repository.findByUserNameAndReservationId(
                currentUserName(), reservationId.toString());
        return row == null ? Optional.empty() : row;
    }

    private BudgetReservation fromEntity(BudgetReservationEntity entity) {
        BudgetReservation reservation = new BudgetReservation(
                UUID.fromString(entity.getReservationId()),
                uuid(entity.getPlanId()),
                uuid(entity.getPendingOrderId()),
                entity.getUserName() == null || entity.getUserName().isBlank()
                        ? OkxCredentialStore.SYSTEM_USER
                        : entity.getUserName(),
                entity.getSymbol(),
                entity.getAmount() == null ? BigDecimal.ZERO : entity.getAmount(),
                status(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getReason()
        );
        reservations.put(reservation.reservationId(), reservation);
        return reservation;
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value);
    }

    private static BudgetReservationStatus status(String value) {
        if (value == null || value.isBlank()) {
            return BudgetReservationStatus.RELEASED;
        }
        try {
            return BudgetReservationStatus.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            return BudgetReservationStatus.RELEASED;
        }
    }

    private BudgetReservation rejected(UUID planId, UUID pendingOrderId, String symbol, BigDecimal amount, String reason) {
        Instant now = Instant.now();
        return new BudgetReservation(UUID.randomUUID(), planId, pendingOrderId, currentUserName(), symbol, amount,
                BudgetReservationStatus.RELEASED, now, now, reason);
    }

    private static BudgetReservation withStatus(BudgetReservation current, BudgetReservationStatus status, String reason) {
        return new BudgetReservation(current.reservationId(), current.planId(), current.pendingOrderId(), current.userName(),
                current.symbol(), current.amount(), status, current.createdAt(), Instant.now(), reason);
    }

    private static boolean active(BudgetReservation reservation) {
        return reservation.status() == BudgetReservationStatus.RESERVED
                || reservation.status() == BudgetReservationStatus.USED;
    }

    private static String currentUserName() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
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

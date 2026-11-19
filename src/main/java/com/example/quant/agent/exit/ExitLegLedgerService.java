package com.example.quant.agent.exit;

import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Reconciles fixed TP legs from durable plan facts and fill facts.  It never
 * scales a filled leg from the current position and never submits orders; the
 * caller applies the returned submit/cancel intents after the invariant checks.
 */
@Service
public class ExitLegLedgerService {
    private final ExitLegLedgerRepository ledgerRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeFillRepository tradeFillRepository;

    public ExitLegLedgerService(ExitLegLedgerRepository ledgerRepository,
                                TradeOrderRepository tradeOrderRepository,
                                TradeFillRepository tradeFillRepository) {
        this.ledgerRepository = ledgerRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.tradeFillRepository = tradeFillRepository;
    }

    public Reconciliation reconcile(String userName, String pendingOrderId, String tradePlanId,
                                    String instId, List<CanonicalExecutablePlan.ExitLeg> plannedLegs,
                                    BigDecimal positionQuantity, List<ActiveProtection> active,
                                    Instant now) {
        if (positionQuantity == null || positionQuantity.signum() < 0) {
            throw new IllegalStateException("EXIT_LEDGER_INVALID_POSITION_QUANTITY");
        }
        Map<String, ExitLegLedgerEntity> rows = new LinkedHashMap<>();
        for (ExitLegLedgerEntity row : ledgerRepository
                .findByUserNameAndPendingOrderIdOrderByExitLegId(userName, pendingOrderId)) {
            rows.put(row.getExitLegId(), row);
        }
        for (CanonicalExecutablePlan.ExitLeg leg : plannedLegs) {
            if (leg.semanticId() == null || leg.semanticId().isBlank()
                    || leg.executableContractQuantity() == null
                    || leg.executableContractQuantity().signum() < 0) {
                throw new IllegalStateException("EXIT_LEDGER_INVALID_CANONICAL_LEG");
            }
            ExitLegLedgerEntity row = rows.get(leg.semanticId());
            if (row == null) {
                row = new ExitLegLedgerEntity();
                row.setUserName(userName);
                row.setPendingOrderId(pendingOrderId);
                row.setTradePlanId(tradePlanId);
                row.setInstId(instId);
                row.setExitLegId(leg.semanticId());
                row.setPlannedQuantity(leg.executableContractQuantity());
                row.setCreatedAt(now);
                rows.put(leg.semanticId(), row);
            }
            row.setTradePlanId(tradePlanId);
            row.setInstId(instId);
            row.setLegType("TAKE_PROFIT");
            row.setTargetPrice(leg.executableTargetPrice());
            row.setTargetR(leg.targetR());
        }

        List<TradeOrderEntity> orderRows = tradeOrderRepository
                .findByUserNameAndPendingOrderIdOrderByCreatedAtAsc(userName, pendingOrderId);
        List<TradeFillEntity> fillRows = tradeFillRepository
                .findByUserNameAndPendingOrderId(userName, pendingOrderId);
        Map<String, BigDecimal> filledByLeg = filledByLeg(orderRows, fillRows, rows.keySet());
        boolean lifecycleHasFill = rows.values().stream()
                .anyMatch(row -> nz(row.getFilledQuantity()).signum() > 0)
                || filledByLeg.values().stream().anyMatch(value -> value.signum() > 0);
        if (!lifecycleHasFill) {
            // This preserves the existing entry-partial-fill behaviour: an
            // unfilled plan may grow to the observed position. Once any leg
            // fills, all planned quantities are frozen for this lifecycle.
            for (CanonicalExecutablePlan.ExitLeg leg : plannedLegs) {
                rows.get(leg.semanticId()).setPlannedQuantity(leg.executableContractQuantity());
            }
        } else {
            for (ExitLegLedgerEntity row : rows.values()) {
                if (row.getPlannedQuantity() == null) {
                    throw new IllegalStateException("EXIT_LEDGER_PLANNED_QUANTITY_MISSING");
                }
            }
        }
        Map<String, BigDecimal> activeByLeg = activeByLeg(active);
        BigDecimal activeTotal = activeByLeg.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (activeTotal.compareTo(positionQuantity) > 0) {
            throw new IllegalStateException("EXIT_LEDGER_ACTIVE_EXCEEDS_POSITION");
        }
        Map<String, BigDecimal> submit = new LinkedHashMap<>();
        Set<String> cancel = new LinkedHashSet<>();
        BigDecimal outstandingTotal = BigDecimal.ZERO;

        for (Map.Entry<String, ExitLegLedgerEntity> entry : rows.entrySet()) {
            String legId = entry.getKey();
            ExitLegLedgerEntity row = entry.getValue();
            BigDecimal planned = nz(row.getPlannedQuantity());
            BigDecimal filled = filledByLeg.getOrDefault(legId, nz(row.getFilledQuantity()));
            if (filled.compareTo(planned) > 0) {
                throw new IllegalStateException("EXIT_LEDGER_FILLED_EXCEEDS_PLANNED:" + legId);
            }
            BigDecimal remaining = planned.subtract(filled);
            row.setFilledQuantity(filled);
            row.setRemainingQuantity(remaining);
            outstandingTotal = outstandingTotal.add(remaining);
            BigDecimal activeSize = activeByLeg.getOrDefault(legId, BigDecimal.ZERO);
            if (remaining.signum() == 0) {
                row.setStatus(ExitLegLedgerStatus.FILLED);
                if (activeSize.signum() > 0) {
                    cancel.add(legId);
                }
            } else if (activeSize.compareTo(remaining) > 0) {
                row.setStatus(ExitLegLedgerStatus.REPLACED);
                cancel.add(legId);
            } else {
                row.setStatus(filled.signum() > 0
                        ? ExitLegLedgerStatus.PARTIALLY_FILLED
                        : activeSize.signum() > 0 ? ExitLegLedgerStatus.SUBMITTED : ExitLegLedgerStatus.PLANNED);
                if (activeSize.compareTo(remaining) < 0) {
                    submit.put(legId, remaining.subtract(activeSize));
                }
            }
            row.setUpdatedAt(now);
        }
        if (outstandingTotal.compareTo(positionQuantity) > 0) {
            throw new IllegalStateException("EXIT_LEDGER_OUTSTANDING_EXCEEDS_POSITION");
        }
        List<ExitLegLedgerEntity> saved = new ArrayList<>();
        for (ExitLegLedgerEntity row : rows.values()) {
            saved.add(ledgerRepository.save(row));
        }
        return new Reconciliation(saved, submit, cancel, outstandingTotal);
    }

    public boolean firstLegHasFill(String owner, String pendingOrderId) {
        if (ledgerRepository.findByUserNameAndPendingOrderIdAndExitLegId(owner, pendingOrderId, "TP1")
                .map(row -> nz(row.getFilledQuantity()).signum() > 0).orElse(false)) return true;
        return filledByLeg(tradeOrderRepository.findByUserNameAndPendingOrderIdOrderByCreatedAtAsc(owner, pendingOrderId),
                tradeFillRepository.findByUserNameAndPendingOrderId(owner, pendingOrderId), Set.of("TP1"))
                .getOrDefault("TP1", BigDecimal.ZERO).signum() > 0;
    }

    public void recordSubmitted(String userName, String pendingOrderId, String exitLegId,
                                 String clOrdId, String algoId, Instant now) {
        ExitLegLedgerEntity row = ledgerRepository
                .findByUserNameAndPendingOrderIdAndExitLegId(userName, pendingOrderId, exitLegId)
                .orElseThrow(() -> new IllegalStateException("EXIT_LEDGER_LEG_MISSING:" + exitLegId));
        row.setCurrentClOrdId(clOrdId);
        row.setCurrentAlgoId(algoId);
        if (row.getRemainingQuantity().signum() > 0) {
            row.setStatus(ExitLegLedgerStatus.SUBMITTED);
        }
        row.setUpdatedAt(now);
        ledgerRepository.save(row);
    }

    public List<ExitLegLedgerEntity> markFlat(String userName, String pendingOrderId, Instant now) {
        List<ExitLegLedgerEntity> rows = ledgerRepository
                .findByUserNameAndPendingOrderIdOrderByExitLegId(userName, pendingOrderId);
        for (ExitLegLedgerEntity row : rows) {
            row.setStatus(ExitLegLedgerStatus.OBSOLETE);
            row.setRemainingQuantity(BigDecimal.ZERO);
            row.setUpdatedAt(now);
            ledgerRepository.save(row);
        }
        return rows;
    }

    private static Map<String, BigDecimal> filledByLeg(List<TradeOrderEntity> orders,
                                                       List<TradeFillEntity> fills,
                                                       Set<String> legIds) {
        Map<String, Set<String>> orderIds = new HashMap<>();
        for (TradeOrderEntity order : orders) {
            if (!order.isReduceOnly() || !legIds.contains(order.getOrderRole())) {
                continue;
            }
            Set<String> ids = orderIds.computeIfAbsent(order.getOrderRole(), ignored -> new HashSet<>());
            if (order.getOkxOrdId() != null) ids.add("O:" + order.getOkxOrdId());
            if (order.getClOrdId() != null) ids.add("C:" + order.getClOrdId());
        }
        Map<String, BigDecimal> result = new HashMap<>();
        Set<String> seenTradeIds = new HashSet<>();
        for (TradeFillEntity fill : fills) {
            if (fill.getTradeId() == null || !seenTradeIds.add(fill.getTradeId())
                    || fill.getFillSize() == null || fill.getFillSize().signum() <= 0) {
                continue;
            }
            for (Map.Entry<String, Set<String>> entry : orderIds.entrySet()) {
                if (entry.getValue().contains("O:" + fill.getOrdId())
                        || entry.getValue().contains("C:" + fill.getClOrdId())) {
                    result.merge(entry.getKey(), fill.getFillSize(), BigDecimal::add);
                    break;
                }
            }
        }
        return result;
    }

    private static Map<String, BigDecimal> activeByLeg(List<ActiveProtection> active) {
        Map<String, BigDecimal> result = new HashMap<>();
        for (ActiveProtection item : active == null ? List.<ActiveProtection>of() : active) {
            if (item.role() == null || item.size() == null || item.size().signum() < 0) continue;
            result.merge(item.role(), item.size(), BigDecimal::add);
        }
        return result;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record ActiveProtection(String clientOrderId, String role, BigDecimal size) {}

    public record Reconciliation(List<ExitLegLedgerEntity> legs,
                                 Map<String, BigDecimal> submitQuantities,
                                 Set<String> cancelRoles,
                                 BigDecimal outstandingQuantity) {
        public Reconciliation {
            legs = List.copyOf(legs);
            submitQuantities = Map.copyOf(submitQuantities);
            cancelRoles = Set.copyOf(cancelRoles);
        }
    }
}

package com.example.quant.okxtrade;

import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.ledger.TradeFillLedgerService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class OkxCurrentOrderSyncService {
    private static final List<String> ACTIVE_ENTRY_STATUSES = List.of(
            "SUBMITTED", "PROTECTION_SUBMITTED", "PARTIALLY_FILLED", "OPEN", "RECONCILING",
            "ALGO_EFFECTIVE", "ALGO_PARTIALLY_EFFECTIVE"
    );
    private static final List<String> TERMINAL_FILL_STATUSES = List.of("FILLED", "PARTIALLY_FILLED_CANCELED");
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "FILLED", "CANCELED", "PARTIALLY_FILLED_CANCELED", "REJECTED", "ALGO_EFFECTIVE");
    private static final String RECONCILIATION_PENDING = "ORDER_RECONCILIATION_PENDING: ";
    private static final Duration TERMINAL_FILL_LOOKBACK = Duration.ofHours(24);

    private final OkxCurrentOrderService currentOrderService;
    private final TradeOrderRepository tradeOrderRepository;
    private final Set<String> reconciledTerminalFillOrderIds = ConcurrentHashMap.newKeySet();
    private TradeFillLedgerService fillLedgerService;

    public OkxCurrentOrderSyncService(OkxCurrentOrderService currentOrderService,
                                      TradeOrderRepository tradeOrderRepository) {
        this.currentOrderService = currentOrderService;
        this.tradeOrderRepository = tradeOrderRepository;
    }

    @Autowired
    void configureFillLedger(TradeFillLedgerService fillLedgerService) {
        this.fillLedgerService = fillLedgerService;
    }

    @Transactional
    public SyncResult syncOnce() {
        if (currentOrderService == null || tradeOrderRepository == null) {
            return new SyncResult(0, 0, 0, false, null, List.of());
        }
        if (!currentOrderService.hasCredentials()) {
            return new SyncResult(0, 0, 0, false, "OKX_ACCOUNT_UNBOUND", List.of());
        }
        try {
            List<OkxCurrentOrderView> normalOrders = currentOrderService.currentOrders();
            List<OkxCurrentOrderView> algoOrders = currentOrderService.currentAlgoOrders();
            Set<String> activeInstIds = new LinkedHashSet<>();
            Set<String> activeOkxOrderIds = new LinkedHashSet<>();
            Set<String> activeClientOrderIds = new LinkedHashSet<>();
            List<com.example.quant.account.dto.PositionSummary> activeEntryExposures = new ArrayList<>();
            int upserted = 0;
            for (OkxCurrentOrderView order : normalOrders) {
                if (upsert(order)) upserted++;
                addInstId(activeInstIds, order.instId());
                addText(activeOkxOrderIds, order.ordId());
                addText(activeClientOrderIds, order.clOrdId());
                addEntryExposure(activeEntryExposures, order);
            }
            for (OkxCurrentOrderView order : algoOrders) {
                if (upsert(order)) upserted++;
                addInstId(activeInstIds, order.instId());
                addText(activeOkxOrderIds, order.algoId());
                addText(activeClientOrderIds, order.algoClOrdId());
            }
            int pendingDetails = reconcileMissingActiveOrders(activeOkxOrderIds, activeClientOrderIds);
            int incompleteTerminalFills = reconcileTerminalFills();
            int reconciliationRequired = pendingDetails + reconciliationRequired();
            String reconciliationError = reconciliationRequired > 0
                    ? "ORDER_RECONCILIATION_REQUIRED:" + reconciliationRequired
                    : incompleteTerminalFills > 0
                    ? "FILL_RECONCILIATION_REQUIRED:" + incompleteTerminalFills : null;
            return new SyncResult(normalOrders.size(), algoOrders.size(), upserted, false, reconciliationError,
                    new ArrayList<>(activeInstIds), activeEntryExposures);
        } catch (RuntimeException ex) {
            return new SyncResult(0, 0, 0, true, compact(ex.getMessage()), List.of());
        }
    }

    private int reconcileTerminalFills() {
        if (fillLedgerService == null) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(TERMINAL_FILL_LOOKBACK);
        int incomplete = 0;
        for (String status : TERMINAL_FILL_STATUSES) {
            List<TradeOrderEntity> rows = tradeOrderRepository.findByStatus(status);
            if (rows == null) {
                continue;
            }
            for (TradeOrderEntity entry : rows) {
                if (entry == null || !ownedByCurrentUser(entry) || entry.isReduceOnly()
                        || !hasText(entry.getOkxOrdId()) || nonNull(entry.getFilledSize()).signum() <= 0
                        || entry.getUpdatedAt() == null || entry.getUpdatedAt().isBefore(cutoff)
                        || reconciledTerminalFillOrderIds.contains(entry.getOkxOrdId())) {
                    continue;
                }
                List<OkxCurrentOrderService.Fill> exchangeFills = currentOrderService.fills(
                        entry.getInstId(), entry.getOkxOrdId());
                BigDecimal total = BigDecimal.ZERO;
                for (OkxCurrentOrderService.Fill fill : exchangeFills) {
                    fillLedgerService.record(entry, fill);
                    total = total.add(nonNull(fill.fillSize()));
                }
                if (total.compareTo(nonNull(entry.getFilledSize())) >= 0) {
                    reconciledTerminalFillOrderIds.add(entry.getOkxOrdId());
                } else {
                    incomplete++;
                }
            }
        }
        return incomplete;
    }

    private int reconciliationRequired() {
        List<TradeOrderEntity> rows = tradeOrderRepository.findByStatus("RECONCILING");
        int count = rows == null ? 0 : (int) rows.stream()
                .filter(OkxCurrentOrderSyncService::ownedByCurrentUser).count();
        for (String status : List.of("ALGO_EFFECTIVE", "ALGO_PARTIALLY_EFFECTIVE")) {
            List<TradeOrderEntity> effective = tradeOrderRepository.findByStatus(status);
            if (effective != null) {
                count += (int) effective.stream()
                        .filter(OkxCurrentOrderSyncService::ownedByCurrentUser)
                        .filter(row -> hasPendingDetail(row)
                                || !LedgerCompleteness.COMPLETE.name().equals(row.getPnlCompleteness()))
                        .count();
            }
        }
        return count;
    }

    private boolean upsert(OkxCurrentOrderView order) {
        TradeOrderEntity entity = findExisting(order).orElseGet(TradeOrderEntity::new);
        boolean algo = hasText(order.algoId());
        String status = algo ? localAlgoStatus(order.status()) : localStatus(order.status(), order.filledSize());
        if (!canMergeFacts(entity, status, order.filledSize(), order.updatedAt())) return false;
        entity.setUserName(currentUsername());
        Instant now = Instant.now();
        if (!hasText(entity.getOrderRole())) {
            entity.setOrderRole(defaultText(order.role(), order.reduceOnly() ? "TAKE_PROFIT" : "ENTRY"));
        }
        entity.setInstId(defaultText(order.instId(), entity.getInstId()));
        entity.setSide(defaultText(order.side(), entity.getSide()));
        entity.setPosSide(defaultText(order.posSide(), entity.getPosSide()));
        entity.setOrdType(defaultText(order.ordType(), entity.getOrdType()));
        entity.setTdMode(defaultText(entity.getTdMode(), "cross"));
        entity.setSize(nonNull(order.size()));
        entity.setFilledSize(nonNull(order.filledSize()));
        entity.setRemainingSize(remaining(order.size(), order.filledSize()));
        entity.setPrice(algo ? nonNull(order.triggerPrice()) : nonNull(order.price()));
        entity.setAverageFillPrice(nonNull(order.averageFillPrice()));
        entity.setOkxUpdatedAt(order.updatedAt());
        entity.setReduceOnly(order.reduceOnly());
        entity.setClOrdId(defaultText(algo ? order.algoClOrdId() : order.clOrdId(), entity.getClOrdId()));
        entity.setOkxOrdId(defaultText(algo ? order.algoId() : order.ordId(), entity.getOkxOrdId()));
        entity.setOkxState(order.status());
        entity.setStatus(status);
        if (hasPendingDetail(entity)) entity.setErrorMessage(null);
        if (!hasText(entity.getPnlCompleteness())) {
            entity.setPnlCompleteness(LedgerCompleteness.UNKNOWN.name());
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(order.createdAt() == null ? now : order.createdAt());
        }
        entity.setUpdatedAt(now);
        tradeOrderRepository.save(entity);
        return true;
    }

    private int reconcileMissingActiveOrders(Set<String> activeOkxOrderIds, Set<String> activeClientOrderIds) {
        int pendingDetails = 0;
        Instant now = Instant.now();
        Set<String> reconciledOrderIds = new HashSet<>();
        for (String status : ACTIVE_ENTRY_STATUSES) {
            List<TradeOrderEntity> rows = tradeOrderRepository.findByStatus(status);
            if (rows == null) {
                continue;
            }
            List<TradeOrderEntity> activeEntries = rows.stream()
                    .filter(OkxCurrentOrderSyncService::ownedByCurrentUser)
                    .toList();
            for (TradeOrderEntity entry : activeEntries) {
                if (entry == null) {
                    continue;
                }
                boolean hasOkxId = hasText(entry.getOkxOrdId());
                boolean hasClientId = hasText(entry.getClOrdId());
                if (!hasOkxId && !hasClientId) {
                    continue;
                }
                String reconciliationId = hasOkxId ? entry.getOkxOrdId() : entry.getClOrdId();
                if (!reconciledOrderIds.add(reconciliationId)) {
                    continue;
                }
                boolean stillLive = (hasOkxId && activeOkxOrderIds.contains(entry.getOkxOrdId()))
                        || (hasClientId && activeClientOrderIds.contains(entry.getClOrdId()));
                if (!stillLive) {
                    reconcileMissing(entry);
                    entry.setUpdatedAt(now);
                    tradeOrderRepository.save(entry);
                }
                if (hasPendingDetail(entry) && !"RECONCILING".equals(entry.getStatus())
                        && !isEffective(entry.getOkxState())) pendingDetails++;
            }
        }
        return pendingDetails;
    }

    private void reconcileMissing(TradeOrderEntity entry) {
        if (entry.isReduceOnly() && !hasText(entry.getParentAlgoId()) && !"REDUCE_ONLY".equals(entry.getOrderRole())) {
            reconcileMissingAlgo(entry);
            return;
        }
        try {
            OkxCurrentOrderService.OrderDetail detail = currentOrderService.orderDetail(
                    entry.getInstId(), entry.getOkxOrdId(), entry.getClOrdId());
            if (detail == null) {
                markReconciling(entry, "ORDER_DETAILS_UNAVAILABLE");
                return;
            }
            if (fillLedgerService != null && nonNull(detail.filledSize()).signum() > 0) {
                currentOrderService.fills(entry.getInstId(), entry.getOkxOrdId())
                        .forEach(fill -> fillLedgerService.record(entry, fill));
            }
            if (!applyDetail(entry, detail)) markReconciling(entry, "ORDER_DETAILS_STALE_OR_INCONSISTENT");
        } catch (RuntimeException ex) {
            markReconciling(entry, "ORDER_DETAILS_UNAVAILABLE: " + compact(ex.getMessage()));
        }
    }

    private void reconcileMissingAlgo(TradeOrderEntity entry) {
        try {
            OkxCurrentOrderService.AlgoOrderDetail detail = currentOrderService.algoOrderDetail(
                    entry.getOkxOrdId(), entry.getClOrdId());
            if (detail == null) {
                markReconciling(entry, "ALGO_ORDER_DETAILS_UNAVAILABLE");
                return;
            }
            if (hasText(detail.algoId()) && hasText(entry.getOkxOrdId())
                    && !entry.getOkxOrdId().equals(detail.algoId())) {
                markReconciling(entry, "ALGO_ID_MISMATCH");
                return;
            }
            BigDecimal requested = nonNull(detail.requestedSize());
            BigDecimal actual = nonNull(detail.actualSize());
            String status = localAlgoStatus(detail.state());
            if (!canMergeFacts(entry, status, actual, detail.updatedAt())) {
                markReconciling(entry, "ALGO_ORDER_DETAILS_STALE_OR_INCONSISTENT");
                return;
            }
            entry.setSize(requested);
            entry.setFilledSize(actual);
            entry.setRemainingSize(remaining(requested, actual));
            entry.setAverageFillPrice(nonNull(detail.actualPrice()));
            entry.setOkxUpdatedAt(detail.updatedAt());
            entry.setOkxState(detail.state());
            entry.setStatus(status);
            if (!isEffective(detail.state())) {
                entry.setPnlCompleteness(LedgerCompleteness.UNKNOWN.name());
                entry.setErrorMessage(hasText(detail.failCode()) ? "OKX_ALGO_FAIL_CODE:" + detail.failCode() : null);
                return;
            }
            reconcileAlgoChildren(entry, detail);
        } catch (RuntimeException ex) {
            markReconciling(entry, "ALGO_ORDER_DETAILS_UNAVAILABLE: " + compact(ex.getMessage()));
        }
    }

    private void reconcileAlgoChildren(TradeOrderEntity parent, OkxCurrentOrderService.AlgoOrderDetail algo) {
        if (algo.childOrderIds() == null || algo.childOrderIds().isEmpty()) {
            parent.setPnlCompleteness(LedgerCompleteness.PARTIAL.name());
            parent.setErrorMessage("ALGO_CHILD_ORDER_PENDING");
            return;
        }
        BigDecimal totalChildFilled = BigDecimal.ZERO;
        BigDecimal totalLedgerFilled = BigDecimal.ZERO;
        boolean allChildrenComplete = true;
        for (String childOrderId : algo.childOrderIds()) {
            ChildReconciliation child = reconcileAlgoChild(parent, childOrderId);
            totalChildFilled = totalChildFilled.add(child.orderFilledSize());
            totalLedgerFilled = totalLedgerFilled.add(child.ledgerFilledSize());
            allChildrenComplete &= child.complete();
        }
        BigDecimal expected = nonNull(algo.actualSize()).abs();
        boolean complete = expected.signum() > 0
                && totalChildFilled.compareTo(expected) >= 0
                && totalLedgerFilled.compareTo(expected) >= 0
                && allChildrenComplete;
        parent.setPnlCompleteness(complete
                ? LedgerCompleteness.COMPLETE.name() : LedgerCompleteness.PARTIAL.name());
        parent.setErrorMessage(complete ? null : "ALGO_CHILD_FILL_RECONCILIATION_PENDING");
    }

    private ChildReconciliation reconcileAlgoChild(TradeOrderEntity parent, String childOrderId) {
        if (!hasText(childOrderId)) {
            return ChildReconciliation.incomplete();
        }
        OkxCurrentOrderService.OrderDetail detail = currentOrderService.orderDetail(
                parent.getInstId(), childOrderId, "");
        TradeOrderEntity child = tradeOrderRepository.findFirstByUserNameAndOkxOrdId(
                        parent.getUserName(), childOrderId)
                .orElseGet(TradeOrderEntity::new);
        copyChildIdentity(parent, child, childOrderId);
        if (detail == null || !hasText(detail.ordId()) || !childOrderId.equals(detail.ordId())) {
            markReconciling(child, "ALGO_CHILD_ORDER_DETAILS_UNAVAILABLE");
            child.setUpdatedAt(Instant.now());
            tradeOrderRepository.save(child);
            return ChildReconciliation.incomplete();
        }
        if (!applyDetail(child, detail)) {
            markReconciling(child, "CHILD_ORDER_DETAILS_STALE_OR_INCONSISTENT");
            tradeOrderRepository.save(child);
            return ChildReconciliation.incomplete();
        }
        List<OkxCurrentOrderService.Fill> fills = currentOrderService.fills(parent.getInstId(), childOrderId);
        if (fillLedgerService != null) {
            fills.forEach(fill -> fillLedgerService.record(child, fill));
        }
        AlgoFillReconciliation summary = AlgoFillReconciliation.summarize(detail.filledSize(), fills);
        boolean terminal = isTerminalNormalOrder(detail.state());
        boolean complete = fillLedgerService != null && terminal
                && summary.completeness() == LedgerCompleteness.COMPLETE;
        child.setPnlCompleteness(complete
                ? LedgerCompleteness.COMPLETE.name() : LedgerCompleteness.PARTIAL.name());
        child.setErrorMessage(complete ? null : "CHILD_FILL_RECONCILIATION_PENDING");
        child.setUpdatedAt(Instant.now());
        tradeOrderRepository.save(child);
        return new ChildReconciliation(nonNull(detail.filledSize()), summary.totalSize(), complete);
    }

    private static void copyChildIdentity(TradeOrderEntity parent, TradeOrderEntity child, String childOrderId) {
        Instant now = Instant.now();
        child.setUserName(parent.getUserName());
        child.setTradePlanId(parent.getTradePlanId());
        child.setPendingOrderId(parent.getPendingOrderId());
        child.setOrderRole(parent.getOrderRole());
        child.setInstId(parent.getInstId());
        child.setSide(parent.getSide());
        child.setPosSide(parent.getPosSide());
        child.setOrdType("market");
        child.setTdMode(parent.getTdMode());
        child.setReduceOnly(true);
        child.setClOrdId(null);
        child.setOkxOrdId(childOrderId);
        child.setParentAlgoId(parent.getOkxOrdId());
        if (child.getCreatedAt() == null) {
            child.setCreatedAt(now);
        }
        child.setUpdatedAt(now);
    }

    private static boolean isEffective(String state) {
        return "effective".equalsIgnoreCase(state) || "partially_effective".equalsIgnoreCase(state);
    }

    private static boolean isTerminalNormalOrder(String state) {
        return "filled".equalsIgnoreCase(state)
                || "canceled".equalsIgnoreCase(state)
                || "mmp_canceled".equalsIgnoreCase(state);
    }

    private static boolean applyDetail(TradeOrderEntity entry, OkxCurrentOrderService.OrderDetail detail) {
        BigDecimal requested = nonNull(detail.requestedSize());
        BigDecimal filled = nonNull(detail.filledSize());
        String status = localStatus(detail.state(), filled);
        if (!canMergeFacts(entry, status, filled, detail.updatedAt())) return false;
        entry.setSize(requested);
        entry.setFilledSize(filled);
        entry.setRemainingSize(remaining(requested, filled));
        entry.setAverageFillPrice(nonNull(detail.averageFillPrice()));
        entry.setOkxUpdatedAt(detail.updatedAt());
        entry.setOkxState(detail.state());
        entry.setStatus(status);
        entry.setErrorMessage(null);
        return true;
    }

    static boolean canMergeFacts(TradeOrderEntity entry, String status, BigDecimal filled, Instant updatedAt) {
        Instant previousTime = entry.getOkxUpdatedAt();
        if (previousTime != null && (updatedAt == null || updatedAt.isBefore(previousTime))) return false;
        if (nonNull(filled).compareTo(nonNull(entry.getFilledSize())) < 0) return false;
        String previousStatus = entry.getStatus();
        if ("RECONCILING".equals(previousStatus) && isTerminalNormalOrder(entry.getOkxState())) {
            // The WS order fact can be terminal while its fill ledger is still incomplete.
            previousStatus = localStatus(entry.getOkxState(), entry.getFilledSize());
        }
        if (previousStatus == null || previousStatus.equals(status)) return true;
        return !isTerminalStatus(previousStatus)
                && !"RECONCILING".equals(status)
                && !("PARTIALLY_FILLED".equals(previousStatus) && "SUBMITTED".equals(status))
                && !("ALGO_PARTIALLY_EFFECTIVE".equals(previousStatus) && "PROTECTION_SUBMITTED".equals(status));
    }

    static boolean isTerminalStatus(String status) {
        return status != null && TERMINAL_STATUSES.contains(status);
    }

    private static void markReconciling(TradeOrderEntity entry, String reason) {
        if (!hasText(entry.getStatus())) entry.setStatus("RECONCILING");
        entry.setErrorMessage(RECONCILIATION_PENDING + reason);
    }

    private static boolean hasPendingDetail(TradeOrderEntity entry) {
        return entry.getErrorMessage() != null && entry.getErrorMessage().startsWith(RECONCILIATION_PENDING);
    }

    static String localStatus(String okxState, BigDecimal filledSize) {
        String state = okxState == null ? "" : okxState.trim().toLowerCase();
        BigDecimal filled = nonNull(filledSize);
        return switch (state) {
            case "live" -> filled.signum() > 0 ? "PARTIALLY_FILLED" : "SUBMITTED";
            case "partially_filled" -> "PARTIALLY_FILLED";
            case "filled" -> "FILLED";
            case "canceled", "mmp_canceled" -> filled.signum() > 0
                    ? "PARTIALLY_FILLED_CANCELED" : "CANCELED";
            case "rejected" -> "REJECTED";
            default -> "RECONCILING";
        };
    }

    private static String localAlgoStatus(String okxState) {
        String state = okxState == null ? "" : okxState.trim().toLowerCase();
        return switch (state) {
            case "live" -> "PROTECTION_SUBMITTED";
            case "effective" -> "ALGO_EFFECTIVE";
            case "partially_effective" -> "ALGO_PARTIALLY_EFFECTIVE";
            case "canceled" -> "CANCELED";
            case "order_failed" -> "REJECTED";
            case "partially_failed" -> "RECONCILING";
            default -> "RECONCILING";
        };
    }

    static BigDecimal remaining(BigDecimal requested, BigDecimal filled) {
        BigDecimal value = nonNull(requested).subtract(nonNull(filled));
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private Optional<TradeOrderEntity> findExisting(OkxCurrentOrderView order) {
        String okxOrderId = defaultText(order.ordId(), order.algoId());
        if (hasText(okxOrderId)) {
            Optional<TradeOrderEntity> byOkxId = tradeOrderRepository.findFirstByUserNameAndOkxOrdId(
                    currentUsername(), okxOrderId);
            if (byOkxId.isPresent()) {
                return byOkxId;
            }
        }
        String clientOrderId = defaultText(order.clOrdId(), order.algoClOrdId());
        if (hasText(clientOrderId)) {
            return tradeOrderRepository.findFirstByUserNameAndClOrdId(currentUsername(), clientOrderId);
        }
        return Optional.empty();
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    private static boolean ownedByCurrentUser(TradeOrderEntity row) {
        return row != null && currentUsername().equals(row.getUserName());
    }

    private static void addInstId(Set<String> activeInstIds, String instId) {
        if (hasText(instId)) {
            activeInstIds.add(instId);
        }
    }

    private static void addText(Set<String> values, String value) {
        if (hasText(value)) {
            values.add(value);
        }
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String defaultText(String value, String fallback) {
        if (hasText(value)) {
            return value;
        }
        return hasText(fallback) ? fallback : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String compact(String message) {
        if (!hasText(message)) {
            return "unknown";
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 240 ? compact.substring(0, 240) : compact;
    }

    private static void addEntryExposure(List<com.example.quant.account.dto.PositionSummary> exposures,
                                         OkxCurrentOrderView order) {
        if (order == null || order.reduceOnly() || !hasText(order.instId())) {
            return;
        }
        String posSide = hasText(order.posSide()) && !"net".equalsIgnoreCase(order.posSide())
                ? order.posSide().toLowerCase()
                : ("sell".equalsIgnoreCase(order.side()) ? "short" : "long");
        BigDecimal price = order.price() != null ? order.price()
                : (order.averageFillPrice() != null ? order.averageFillPrice() : BigDecimal.ZERO);
        exposures.add(new com.example.quant.account.dto.PositionSummary(
                order.instId(), posSide, "1", price, BigDecimal.ZERO, "OKX_CURRENT_ENTRY"));
    }

    public record SyncResult(
            int normalOrders,
            int algoOrders,
            int upserted,
            boolean failed,
            String errorMessage,
            List<String> activeInstIds,
            List<com.example.quant.account.dto.PositionSummary> activeEntryExposures
    ) {
        public SyncResult(int normalOrders, int algoOrders, int upserted, boolean failed,
                          String errorMessage, List<String> activeInstIds) {
            this(normalOrders, algoOrders, upserted, failed, errorMessage, activeInstIds, List.of());
        }

        public List<com.example.quant.account.dto.PositionSummary> activeEntryExposures() {
            return activeEntryExposures == null ? List.of() : activeEntryExposures;
        }
    }

    private record ChildReconciliation(BigDecimal orderFilledSize, BigDecimal ledgerFilledSize, boolean complete) {
        private static ChildReconciliation incomplete() {
            return new ChildReconciliation(BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
    }
}

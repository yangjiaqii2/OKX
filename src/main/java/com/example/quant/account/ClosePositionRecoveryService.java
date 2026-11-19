package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.event.TradeEventPayload;
import com.example.quant.agent.event.TradeEventService;
import com.example.quant.agent.event.TradeEventType;
import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.agent.review.TradeReviewService;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.ledger.TradeFillLedgerService;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.okxtrade.OkxCurrentOrderService;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxCancelAcknowledgement;
import com.example.quant.okxtrade.EntryOrderReconciliation;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.risk.DailyRiskService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClosePositionRecoveryService {
    private static final int PROTECTION_CANCEL_MAX_ATTEMPTS = 3;
    private static final List<String> ACTIVE_PROTECTION_STATUSES = List.of(
            "SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE"
    );
    private static final Set<OrderStatus> POSITION_CONFIRMED_STATUSES = EnumSet.of(
            OrderStatus.ENTRY_FILLED,
            OrderStatus.EXECUTED,
            OrderStatus.PROTECTION_SUBMITTED,
            OrderStatus.ACTIVE,
            OrderStatus.ACTIVE_WITH_TP_WARNING,
            OrderStatus.SIDEWAYS_TIMEOUT,
            OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED,
            OrderStatus.MAX_HOLD_TIMEOUT,
            OrderStatus.PROTECTION_FAILED,
            OrderStatus.PROTECTION_REPLAN_REQUIRED,
            OrderStatus.PROTECTION_RULES_STALE,
            OrderStatus.EMERGENCY_ATTENTION_REQUIRED,
            OrderStatus.CLOSED_RECONCILING
    );

    private final ClosePositionRecordRepository closePositionRecordRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final PendingOrderService pendingOrderService;
    private final AutoTradeBudgetService budgetService;
    private final PositionSnapshotService positionSnapshotService;
    private final OkxOrderGateway okxOrderGateway;
    private final TradeReviewService tradeReviewService;
    private final TradeEventService tradeEventService;
    private DailyRiskService dailyRiskService;
    @Autowired
    private ClosedPositionFunding closedPositionFunding;
    private OkxCurrentOrderService currentOrderService;
    private TradeFillLedgerService fillLedgerService;

    public ClosePositionRecoveryService(ClosePositionRecordRepository closePositionRecordRepository,
                                        TradeOrderRepository tradeOrderRepository,
                                        PendingOrderService pendingOrderService,
                                        AutoTradeBudgetService budgetService,
                                        PositionSnapshotService positionSnapshotService) {
        this(closePositionRecordRepository, tradeOrderRepository, pendingOrderService, budgetService,
                positionSnapshotService, null, null, null);
    }

    public ClosePositionRecoveryService(ClosePositionRecordRepository closePositionRecordRepository,
                                        TradeOrderRepository tradeOrderRepository,
                                        PendingOrderService pendingOrderService,
                                        AutoTradeBudgetService budgetService,
                                        PositionSnapshotService positionSnapshotService,
                                        OkxOrderGateway okxOrderGateway) {
        this(closePositionRecordRepository, tradeOrderRepository, pendingOrderService, budgetService,
                positionSnapshotService, okxOrderGateway, null, null);
    }

    @Autowired
    public ClosePositionRecoveryService(ClosePositionRecordRepository closePositionRecordRepository,
                                        TradeOrderRepository tradeOrderRepository,
                                        PendingOrderService pendingOrderService,
                                        AutoTradeBudgetService budgetService,
                                        PositionSnapshotService positionSnapshotService,
                                        OkxOrderGateway okxOrderGateway,
                                        TradeReviewService tradeReviewService,
                                        TradeEventService tradeEventService) {
        this.closePositionRecordRepository = closePositionRecordRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.pendingOrderService = pendingOrderService;
        this.budgetService = budgetService;
        this.positionSnapshotService = positionSnapshotService;
        this.okxOrderGateway = okxOrderGateway;
        this.tradeReviewService = tradeReviewService;
        this.tradeEventService = tradeEventService;
    }

    @Autowired(required = false)
    void configureDailyRisk(DailyRiskService dailyRiskService) {
        this.dailyRiskService = dailyRiskService;
    }

    @Autowired(required = false)
    void configureCloseLedger(OkxCurrentOrderService currentOrderService,
                              TradeFillLedgerService fillLedgerService) {
        this.currentOrderService = currentOrderService;
        this.fillLedgerService = fillLedgerService;
    }

    @Transactional
    public CloseRecoveryResult runOnce() {
        return runOnce(Instant.now());
    }

    @Transactional
    public CloseRecoveryResult runOnce(Instant now) {
        int closed = 0;
        ProtectionCancelRetryResult retryResult = retryFailedProtectionCancels(now);
        List<ClosePositionRecordEntity> submittedRecords = unresolvedCloseRecords();
        List<PositionSummary> positions;
        try {
            positions = positionSnapshotService.positions();
        } catch (RuntimeException ex) {
            markPositionQueryFailed(submittedRecords, now, ex);
            return new CloseRecoveryResult(0, retryResult.retried(), retryResult.attentionRequired());
        }
        for (ClosePositionRecordEntity record : submittedRecords) {
            if (!hasText(record.getCloseOrderId()) && hasText(record.getCloseClOrdId())) {
                discoverCloseOrder(record);
            }
            boolean entrySettled = entrySettledForClose(record);
            if (hasOpenPosition(record, positions)) {
                if ("CLOSE_SUBMITTING".equals(record.getStatus())) {
                    record.setStatus("CLOSE_SUBMIT_UNKNOWN");
                    record.setErrorMessage("APPLICATION_RESTARTED_DURING_CLOSE_SUBMIT");
                    record.setUpdatedAt(now);
                    closePositionRecordRepository.save(record);
                }
                continue;
            }
            if (!entrySettled || !reconcileCloseExecution(record)) {
                continue;
            }
            record.setStatus("CLOSED");
            record.setUpdatedAt(now);
            closePositionRecordRepository.save(record);
            closeLocalPendingOrder(record);
            invalidateProtectionOrders(record, now);
            createTradeReview(record);
            recordDailyRiskOutcome(record, now);
            recordEvent(record, TradeEventType.CLOSE_CONFIRMED, "CLOSE_SUBMITTED", "CLOSED",
                    "CLOSE_CONFIRMED", "OKX当前持仓已消失，平仓确认完成");
            closed++;
        }
        closed += closeOrdersWithoutPositions(positions, now);
        repairClosedLedger(now);
        return new CloseRecoveryResult(closed, retryResult.retried(), retryResult.attentionRequired());
    }

    private boolean entrySettledForClose(ClosePositionRecordEntity record) {
        if (!hasText(record.getPendingOrderId())) return true;
        try {
            return entrySettledAndFlat(pendingOrderService.get(UUID.fromString(record.getPendingOrderId())));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean entrySettledAndFlat(PendingOrder order) {
        if (order.submittedAt() == null) return true;
        try {
            return EntryOrderReconciliation.ensureTerminal(okxOrderGateway, order)
                    && !hasOpenPosition(order, positionSnapshotService.positions());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean reconcileCloseExecution(ClosePositionRecordEntity record) {
        if (currentOrderService == null || !hasText(record.getCloseClOrdId())) {
            return true;
        }
        try {
            String orderId = usableOrderId(record.getCloseOrderId());
            OkxCurrentOrderService.OrderDetail detail = currentOrderService.orderDetail(
                    record.getInstId(), orderId, record.getCloseClOrdId());
            if (detail == null) {
                detail = currentOrderService.orderHistory(record.getInstId(), orderId, record.getCloseClOrdId());
                record.setRecoveryResult(detail == null ? "CURRENT_AND_HISTORY_NOT_FOUND" : "HISTORY_ORDER_FOUND");
            } else {
                record.setRecoveryResult("CURRENT_ORDER_FOUND");
            }
            if (detail == null || !hasText(detail.ordId()) || detail.filledSize() == null
                    || detail.filledSize().signum() <= 0) {
                record.setErrorMessage("CLOSE_ORDER_RECONCILIATION_PENDING");
                closePositionRecordRepository.save(record);
                return false;
            }
            List<OkxCurrentOrderService.Fill> fills = currentOrderService.fills(record.getInstId(), detail.ordId());
            if (fills.isEmpty()) {
                record.setErrorMessage("CLOSE_FILL_RECONCILIATION_PENDING");
                closePositionRecordRepository.save(record);
                return false;
            }
            record.setCloseOrderId(detail.ordId());
            record.setSize(detail.filledSize());
            record.setAvgPx(detail.averageFillPrice());
            if (fills.stream().anyMatch(fill -> fill.fillPnl() == null || fill.fee() == null)) {
                record.setPnlCompleteness("PARTIAL");
                record.setErrorMessage("CLOSE_FILL_PNL_OR_FEE_INCOMPLETE");
                closePositionRecordRepository.save(record);
                return false;
            }
            record.setRealizedPnl(fills.stream().map(OkxCurrentOrderService.Fill::fillPnl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            record.setFee(fills.stream().map(OkxCurrentOrderService.Fill::fee)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            record.setPnlCompleteness("COMPLETE");
            record.setErrorMessage(null);
            if (fillLedgerService != null) {
                String detailOrderId = detail.ordId();
                fills.forEach(fill -> fillLedgerService.record(record, detailOrderId, fill));
            }
            return true;
        } catch (RuntimeException ex) {
            record.setErrorMessage("CLOSE_ORDER_RECONCILIATION_PENDING: " + compact(ex.getMessage()));
            closePositionRecordRepository.save(record);
            return false;
        }
    }

    private void discoverCloseOrder(ClosePositionRecordEntity record) {
        if (currentOrderService == null) {
            return;
        }
        try {
            OkxCurrentOrderService.OrderDetail detail = currentOrderService.orderDetail(
                    record.getInstId(), "", record.getCloseClOrdId());
            if (detail == null) {
                detail = currentOrderService.orderHistory(record.getInstId(), "", record.getCloseClOrdId());
            }
            if (detail != null && hasText(detail.ordId())) {
                record.setCloseOrderId(detail.ordId());
                record.setRecoveryResult("ORDER_FOUND_BY_CLORDID");
                record.setUpdatedAt(Instant.now());
                closePositionRecordRepository.save(record);
            } else {
                record.setRecoveryResult("ORDER_NOT_FOUND_QUERY_IN_PROGRESS");
                closePositionRecordRepository.save(record);
            }
        } catch (RuntimeException ex) {
            record.setRecoveryResult("ORDER_QUERY_UNKNOWN");
            record.setErrorMessage("CLOSE_ORDER_QUERY_UNKNOWN: " + compact(ex.getMessage()));
            closePositionRecordRepository.save(record);
        }
    }

    private void repairClosedLedger(Instant now) {
        if (currentOrderService == null) {
            return;
        }
        List<ClosePositionRecordEntity> closed = closePositionRecordRepository.findByUserNameAndStatus(currentUsername(), "CLOSED");
        closed.stream()
                .filter(record -> hasText(record.getCloseClOrdId()))
                .filter(record -> record.getRealizedPnl() == null || record.getFee() == null)
                .forEach(record -> {
                    if (reconcileCloseExecution(record)) {
                        record.setUpdatedAt(now);
                        closePositionRecordRepository.save(record);
                    }
                });
        if (dailyRiskService != null) {
            for (var record : closed) {
                if (hasText(record.getPendingOrderId())
                        && dailyRiskService.awaitsOutcome(currentUsername(), record.getPendingOrderId())) {
                    recordDailyRiskOutcome(record, now);
                }
            }
        }
    }

    private static String usableOrderId(String orderId) {
        return hasText(orderId) && !orderId.contains(":") ? orderId : "";
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private List<ClosePositionRecordEntity> unresolvedCloseRecords() {
        String username = currentUsername();
        List<ClosePositionRecordEntity> records = new ArrayList<>();
        for (String status : List.of("CLOSE_SUBMITTING", "CLOSE_SUBMIT_UNKNOWN", "CLOSE_SUBMITTED")) {
            records.addAll(closePositionRecordRepository.findByUserNameAndStatus(username, status));
        }
        return records;
    }

    /** Read-only deployment-boundary check; it never mutates exchange or DB state. */
    public int unresolvedCloseIntentCount() {
        return unresolvedCloseRecords().size();
    }

    private int closeOrdersWithoutPositions(List<PositionSummary> positions, Instant now) {
        int closed = 0;
        for (PendingOrder order : pendingOrderService.allOrders()) {
            if (!POSITION_CONFIRMED_STATUSES.contains(order.status()) || hasOpenPosition(order, positions)) {
                continue;
            }
            if (!entrySettledAndFlat(order)) continue;
            OrderStatus previousStatus = order.status();
            ClosePositionRecordEntity record = naturalCloseRecord(order, now);
            boolean ledgerComplete = reconcileNaturalClose(order, record);
            OrderStatus lifecycleStatus = ledgerComplete ? OrderStatus.CLOSED : OrderStatus.CLOSED_RECONCILING;
            if (ledgerComplete) {
                record.setStatus("CLOSED");
                record.setUpdatedAt(now);
                closePositionRecordRepository.save(record);
                order.markClosed("POSITION_GONE");
            } else {
                order.markClosedReconciling("POSITION_GONE_PNL_INCOMPLETE");
            }
            if (ledgerComplete && order.budgetReservationId() != null) {
                budgetService.release(order.budgetReservationId(), "POSITION_GONE");
                recordEvent(record, TradeEventType.BUDGET_RELEASED, "USED", "RELEASED",
                        "POSITION_GONE", "持仓消失后释放自动交易预算");
            }
            invalidateProtectionOrders(record, now);
            if (ledgerComplete) {
                createTradeReview(record);
                recordDailyRiskOutcome(record, now);
                recordEvent(record, TradeEventType.CLOSE_CONFIRMED, previousStatus.name(), "CLOSED",
                        "POSITION_GONE", "OKX当前持仓已消失且成交账本已闭环");
                closed++;
            } else {
                recordEvent(record, TradeEventType.RECOVERY_FAILED, previousStatus.name(),
                        lifecycleStatus.name(), "POSITION_GONE_PNL_INCOMPLETE",
                        "OKX持仓已消失，但 child fills/fee/pnl 尚未完整，等待恢复");
            }
        }
        return closed;
    }

    private ClosePositionRecordEntity naturalCloseRecord(PendingOrder order, Instant now) {
        List<ClosePositionRecordEntity> records = closePositionRecordRepository
                .findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(currentUsername(), order.id().toString());
        if (records != null && !records.isEmpty()) {
            ClosePositionRecordEntity record = records.get(0);
            if (!"CLOSED".equals(record.getStatus())) {
                record.setStatus("CLOSED_RECONCILING");
            }
            record.setUpdatedAt(now);
            return closePositionRecordRepository.save(record);
        }

        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName(currentUsername());
        record.setInstId(order.instId());
        record.setPosSide(order.posSide());
        record.setMarginMode(order.tdMode());
        record.setSize(order.size());
        record.setAvgPx(order.price());
        record.setStatus("CLOSED_RECONCILING");
        record.setPnlCompleteness("PARTIAL");
        record.setSource("POSITION_GONE");
        record.setPendingOrderId(order.id().toString());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return closePositionRecordRepository.save(record);
    }

    private boolean reconcileNaturalClose(PendingOrder order, ClosePositionRecordEntity record) {
        if (adoptUniqueCompleteOrphanManualClose(order, record)) {
            return true;
        }
        if (fillLedgerService == null || tradeOrderRepository == null || record == null) {
            return false;
        }
        List<TradeOrderEntity> lifecycleOrders = tradeOrderRepository
                .findByPendingOrderIdOrderByCreatedAtAsc(order.id().toString());
        BigDecimal expectedEntry = lifecycleOrders.stream()
                .filter(item -> item != null && !item.isReduceOnly())
                .map(TradeOrderEntity::getFilledSize)
                .filter(value -> value != null && value.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<TradeFillEntity> fills = fillLedgerService.fillsForPendingOrder(
                currentUsername(), order.id().toString());
        List<TradeFillEntity> exits = fills.stream()
                .filter(fill -> fill != null && fill.getFillPnl() != null && fill.getFee() != null)
                .filter(fill -> lifecycleOrders.stream().anyMatch(orderRow ->
                        orderRow != null && orderRow.isReduceOnly()
                                && fill.getOrdId() != null && fill.getOrdId().equals(orderRow.getOkxOrdId())))
                .toList();
        BigDecimal closeSize = exits.stream().map(TradeFillEntity::getFillSize)
                .filter(value -> value != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (exits.isEmpty() || expectedEntry.signum() <= 0 || closeSize.compareTo(expectedEntry) < 0) {
            record.setPnlCompleteness("PARTIAL");
            record.setErrorMessage("POSITION_GONE_PNL_INCOMPLETE");
            record.setUpdatedAt(Instant.now());
            closePositionRecordRepository.save(record);
            return false;
        }
        BigDecimal pnl = exits.stream().map(TradeFillEntity::getFillPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fee = exits.stream().map(TradeFillEntity::getFee).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (exits.stream().anyMatch(fill -> fill.getFillPrice() == null || fill.getFillSize() == null)) {
            record.setPnlCompleteness("PARTIAL");
            record.setErrorMessage("POSITION_GONE_FILL_PRICE_INCOMPLETE");
            record.setUpdatedAt(Instant.now());
            closePositionRecordRepository.save(record);
            return false;
        }
        BigDecimal notional = exits.stream().map(fill -> fill.getFillPrice().multiply(fill.getFillSize()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        record.setSize(closeSize);
        record.setAvgPx(notional.divide(closeSize, 12, java.math.RoundingMode.HALF_UP));
        record.setRealizedPnl(pnl);
        record.setFee(fee);
        record.setPnlCompleteness("COMPLETE");
        record.setCloseOrderId(exits.get(0).getOrdId());
        record.setErrorMessage(null);
        record.setUpdatedAt(Instant.now());
        closePositionRecordRepository.save(record);
        return true;
    }

    private boolean adoptUniqueCompleteOrphanManualClose(PendingOrder order,
                                                         ClosePositionRecordEntity lifecycleRecord) {
        if (order == null || lifecycleRecord == null || order.executableContractQuantity() == null
                || order.executableContractQuantity().signum() <= 0 || lifecycleRecord.getCreatedAt() == null) {
            return false;
        }
        List<ClosePositionRecordEntity> matches = closePositionRecordRepository
                .findByUserNameAndStatus(currentUsername(), "CLOSED").stream()
                .filter(record -> record != null && !hasText(record.getPendingOrderId()))
                .filter(record -> "MANUAL".equals(record.getSource()))
                .filter(record -> "COMPLETE".equals(record.getPnlCompleteness()))
                .filter(record -> Boolean.TRUE.equals(record.getRequestSent()))
                .filter(record -> hasText(record.getCloseOrderId()) && hasText(record.getCloseClOrdId()))
                .filter(record -> record.getSize() != null
                        && record.getSize().compareTo(order.executableContractQuantity()) == 0)
                .filter(record -> record.getAvgPx() != null
                        && record.getRealizedPnl() != null && record.getFee() != null)
                .filter(record -> order.instId().equalsIgnoreCase(record.getInstId()))
                .filter(record -> !hasText(order.posSide())
                        || order.posSide().equalsIgnoreCase(record.getPosSide()))
                .filter(record -> record.getCreatedAt() != null
                        && Duration.between(record.getCreatedAt(), lifecycleRecord.getCreatedAt()).abs()
                                .compareTo(Duration.ofMinutes(10)) <= 0)
                .toList();
        if (matches.size() != 1) {
            return false;
        }
        ClosePositionRecordEntity matched = matches.get(0);
        lifecycleRecord.setCloseOrderId(matched.getCloseOrderId());
        lifecycleRecord.setSize(matched.getSize());
        lifecycleRecord.setAvgPx(matched.getAvgPx());
        lifecycleRecord.setRealizedPnl(matched.getRealizedPnl());
        lifecycleRecord.setFee(matched.getFee());
        lifecycleRecord.setFundingFee(matched.getFundingFee());
        lifecycleRecord.setPnlCompleteness("COMPLETE");
        lifecycleRecord.setRequestSent(true);
        lifecycleRecord.setErrorClass(null);
        lifecycleRecord.setRecoveryResult("ORPHAN_MANUAL_CLOSE_FACTS_MATCHED");
        lifecycleRecord.setErrorMessage(null);
        lifecycleRecord.setUpdatedAt(Instant.now());
        closePositionRecordRepository.save(lifecycleRecord);
        return true;
    }

    private ProtectionCancelRetryResult retryFailedProtectionCancels(Instant now) {
        if (tradeOrderRepository == null) {
            return new ProtectionCancelRetryResult(0, 0);
        }
        List<TradeOrderEntity> failedProtections = tradeOrderRepository.findByStatus("PROTECTION_CANCEL_FAILED").stream()
                .filter(ClosePositionRecoveryService::ownedByCurrentUser)
                .toList();
        int retried = 0;
        int attentionRequired = 0;
        for (TradeOrderEntity protection : failedProtections) {
            int attempts = cancelRetryAttempts(protection.getErrorMessage()) + 1;
            if (cancelProtectionOrder(protection)) {
                protection.setStatus("INVALID");
                protection.setOkxState("CANCELLED");
                protection.setErrorMessage("PROTECTION_CANCEL_RETRY_SUCCEEDED: cancelRetry=" + attempts);
                retried++;
            } else if (attempts >= PROTECTION_CANCEL_MAX_ATTEMPTS) {
                protection.setStatus("EMERGENCY_ATTENTION_REQUIRED");
                protection.setErrorMessage("PROTECTION_CANCEL_RETRY_LIMIT: cancelRetry=" + attempts
                        + "; " + compact(protection.getErrorMessage()));
                attentionRequired++;
            } else {
                protection.setStatus("PROTECTION_CANCEL_FAILED");
                protection.setErrorMessage("PROTECTION_CANCEL_FAILED: cancelRetry=" + attempts
                        + "; " + compact(protection.getErrorMessage()));
            }
            protection.setUpdatedAt(now);
        }
        if (!failedProtections.isEmpty()) {
            tradeOrderRepository.saveAll(failedProtections);
        }
        return new ProtectionCancelRetryResult(retried, attentionRequired);
    }

    private void markPositionQueryFailed(List<ClosePositionRecordEntity> submittedRecords, Instant now, RuntimeException ex) {
        String message = "POSITION_QUERY_FAILED: " + compact(ex.getMessage());
        for (ClosePositionRecordEntity record : submittedRecords) {
            record.setErrorMessage(message);
            record.setUpdatedAt(now);
            closePositionRecordRepository.save(record);
            recordEvent(record, TradeEventType.RECOVERY_FAILED, record.getStatus(), record.getStatus(),
                    "POSITION_QUERY_FAILED", message);
        }
    }

    private void closeLocalPendingOrder(ClosePositionRecordEntity record) {
        if (!hasText(record.getPendingOrderId())) {
            return;
        }
        try {
            PendingOrder order = pendingOrderService.get(UUID.fromString(record.getPendingOrderId()));
            order.markClosed("CLOSE_SYNC_CONFIRMED");
            if (order.budgetReservationId() != null) {
                budgetService.release(order.budgetReservationId(), "POSITION_CLOSED");
                recordEvent(record, TradeEventType.BUDGET_RELEASED, "USED", "RELEASED",
                        "POSITION_CLOSED", "平仓确认后释放自动交易预算");
            }
        } catch (IllegalArgumentException ignored) {
            // A close record can exist for manual positions that do not have a local auto-trade pending order.
        }
    }

    private void invalidateProtectionOrders(ClosePositionRecordEntity record, Instant now) {
        if (tradeOrderRepository == null || !hasText(record.getInstId())) {
            return;
        }
        List<TradeOrderEntity> protections = new ArrayList<>(tradeOrderRepository
                .findByInstIdAndReduceOnlyTrueAndStatusIn(record.getInstId(), ACTIVE_PROTECTION_STATUSES).stream()
                .filter(ClosePositionRecoveryService::ownedByCurrentUser)
                .filter(protection -> matchesPositionSide(record, protection))
                .filter(protection -> !hasText(record.getPendingOrderId())
                        || record.getPendingOrderId().equals(protection.getPendingOrderId()))
                .toList());
        if (hasText(record.getPendingOrderId())) {
            List<TradeOrderEntity> boundProtections = tradeOrderRepository
                    .findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(
                            record.getPendingOrderId(), ACTIVE_PROTECTION_STATUSES).stream()
                    .filter(ClosePositionRecoveryService::ownedByCurrentUser)
                    .toList();
            for (TradeOrderEntity protection : boundProtections) {
                if (record.getInstId().equalsIgnoreCase(protection.getInstId())
                        && matchesPositionSide(record, protection) && !protections.contains(protection)) {
                    protections.add(protection);
                }
            }
        }
        for (TradeOrderEntity protection : protections) {
            if (cancelProtectionOrder(protection)) {
                protection.setStatus("INVALID");
                protection.setOkxState("CANCELLED");
                protection.setErrorMessage("POSITION_CLOSED");
            } else {
                protection.setStatus("PROTECTION_CANCEL_FAILED");
                protection.setErrorMessage("PROTECTION_CANCEL_FAILED: cancelRetry=1; "
                        + compact(protection.getErrorMessage()));
            }
            protection.setUpdatedAt(now);
        }
        if (!protections.isEmpty()) {
            tradeOrderRepository.saveAll(protections);
        }
        invalidateClosedEntryOrders(record, now);
    }

    private void invalidateClosedEntryOrders(ClosePositionRecordEntity record, Instant now) {
        if (!hasText(record.getPendingOrderId())) {
            return;
        }
        List<TradeOrderEntity> inactiveEntries = tradeOrderRepository
                .findByPendingOrderIdOrderByCreatedAtAsc(record.getPendingOrderId()).stream()
                .filter(ClosePositionRecoveryService::ownedByCurrentUser)
                .filter(order -> !order.isReduceOnly())
                .filter(order -> ACTIVE_PROTECTION_STATUSES.contains(order.getStatus()))
                .toList();
        for (TradeOrderEntity entry : inactiveEntries) {
            entry.setStatus("INACTIVE");
            entry.setOkxState("POSITION_CLOSED");
            entry.setErrorMessage("POSITION_CLOSED");
            entry.setUpdatedAt(now);
        }
        if (!inactiveEntries.isEmpty()) {
            tradeOrderRepository.saveAll(inactiveEntries);
        }
    }

    private boolean matchesPositionSide(ClosePositionRecordEntity record, TradeOrderEntity protection) {
        if (!hasText(record.getPosSide()) || "net".equalsIgnoreCase(record.getPosSide())) {
            return true;
        }
        // Protection side closes exposure, so invert it before interpreting a NET row.
        String entrySide = "sell".equalsIgnoreCase(protection.getSide()) ? "buy"
                : "buy".equalsIgnoreCase(protection.getSide()) ? "sell" : null;
        return record.getPosSide().equalsIgnoreCase(
                PositionSideMatcher.entryDirection(protection.getPosSide(), entrySide));
    }

    private boolean cancelProtectionOrder(TradeOrderEntity protection) {
        if (okxOrderGateway == null) {
            protection.setOkxState("CANCEL_FAILED");
            protection.setErrorMessage("PROTECTION_CANCEL_FAILED: GATEWAY_UNAVAILABLE");
            return false;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", protection.getInstId());
        if (hasText(protection.getOkxOrdId())) {
            payload.put("algoId", protection.getOkxOrdId());
        } else if (hasText(protection.getClOrdId())) {
            payload.put("algoClOrdId", protection.getClOrdId());
        }
        if (!hasText(protection.getInstId()) || payload.size() <= 1) {
            protection.setOkxState("CANCEL_FAILED");
            protection.setErrorMessage("PROTECTION_CANCEL_FAILED: CANCEL_IDENTITY_UNAVAILABLE");
            return false;
        }
        try {
            OkxCancelAcknowledgement.requireSuccess(okxOrderGateway.cancelAlgoOrder(payload));
            return true;
        } catch (RuntimeException ex) {
            protection.setOkxState("CANCEL_FAILED");
            protection.setErrorMessage("PROTECTION_CANCEL_FAILED: " + compact(ex.getMessage()));
            return false;
        }
    }

    private void createTradeReview(ClosePositionRecordEntity record) {
        if (tradeReviewService == null || record == null || record.getId() == null) {
            return;
        }
        try {
            tradeReviewService.reviewClosedTrade(record);
        } catch (RuntimeException ignored) {
            // Review persistence is diagnostic and must not block close confirmation recovery.
        }
    }

    private void recordDailyRiskOutcome(ClosePositionRecordEntity record, Instant now) {
        if (dailyRiskService == null || record == null || !hasText(record.getPendingOrderId())
                || record.getRealizedPnl() == null || record.getFee() == null) {
            return;
        }
        BigDecimal netPnl = record.getRealizedPnl().add(record.getFee());
        if (fillLedgerService != null) {
            var fills = fillLedgerService.fillsForPendingOrder(record.getUserName(), record.getPendingOrderId());
            if (!fills.isEmpty()) {
                BigDecimal bought = BigDecimal.ZERO;
                BigDecimal sold = BigDecimal.ZERO;
                BigDecimal allFillsNet = BigDecimal.ZERO;
                for (TradeFillEntity fill : fills) {
                    if (fill.getFee() == null || fill.getFillPnl() == null || fill.getFillSize() == null
                            || !"USDT".equalsIgnoreCase(fill.getFeeCurrency())) return;
                    if ("buy".equalsIgnoreCase(fill.getSide())) bought = bought.add(fill.getFillSize());
                    else if ("sell".equalsIgnoreCase(fill.getSide())) sold = sold.add(fill.getFillSize());
                    else return;
                    allFillsNet = allFillsNet.add(fill.getFillPnl()).add(fill.getFee());
                }
                if (bought.signum() <= 0 || bought.compareTo(sold) != 0) return;
                netPnl = allFillsNet;
                if (record.getFundingFee() == null && closedPositionFunding != null) {
                    try {
                        closedPositionFunding.lookup(record, fills, bought, allFillsNet).ifPresent(value -> {
                            record.setFundingFee(value);
                            closePositionRecordRepository.save(record);
                        });
                    } catch (RuntimeException unavailable) {
                        record.setRecoveryResult("FUNDING_HISTORY_RECONCILIATION_PENDING");
                    }
                }
            } else if (record.getPendingOrderId() != null) {
                try {
                    if (pendingOrderService.get(UUID.fromString(record.getPendingOrderId())).submittedAt() != null) return;
                } catch (IllegalArgumentException missingOrder) {
                    return;
                }
            }
        }
        if (record.getFundingFee() != null) {
            netPnl = netPnl.add(record.getFundingFee());
        } else if (netPnl.signum() >= 0) {
            // Unknown funding must not reset a loss streak based on an unverified positive outcome.
            return;
        }
        dailyRiskService.recordReconciledOutcome(record.getUserName(), record.getPendingOrderId(), netPnl, now);
    }

    private void recordEvent(ClosePositionRecordEntity record, TradeEventType eventType, String oldStatus,
                             String newStatus, String reasonCode, String reasonMessage) {
        if (tradeEventService == null || record == null) {
            return;
        }
        try {
            tradeEventService.record(new TradeEventPayload(
                    record.getUserName(),
                    record.getInstId(),
                    record.getPendingOrderId(),
                    record.getAutoTradeRecordId(),
                    null,
                    eventType,
                    oldStatus,
                    newStatus,
                    reasonCode,
                    reasonMessage,
                    record.getCloseOrderId(),
                    record.getCloseClOrdId(),
                    null
            ));
        } catch (RuntimeException ignored) {
            // Event persistence must not block close recovery.
        }
    }

    private boolean hasOpenPosition(ClosePositionRecordEntity record, List<PositionSummary> positions) {
        return positions.stream()
                .anyMatch(position -> PositionSideMatcher.matches(record.getInstId(), record.getPosSide(), null, position));
    }

    private boolean hasOpenPosition(PendingOrder order, List<PositionSummary> positions) {
        return positions.stream()
                .anyMatch(position -> PositionSideMatcher.matches(order.instId(), order.posSide(), order.side(), position));
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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    private static boolean ownedByCurrentUser(TradeOrderEntity row) {
        return row != null && currentUsername().equals(row.getUserName());
    }

    private static String compact(String value) {
        if (!hasText(value)) {
            return "unknown";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static int cancelRetryAttempts(String errorMessage) {
        if (!hasText(errorMessage)) {
            return 0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("cancelRetry=(\\d+)").matcher(errorMessage);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private record ProtectionCancelRetryResult(int retried, int attentionRequired) {
    }

    public record CloseRecoveryResult(int closed, int protectionCancelRetried, int protectionCancelAttentionRequired) {
        public CloseRecoveryResult(int closed) {
            this(closed, 0, 0);
        }
    }
}

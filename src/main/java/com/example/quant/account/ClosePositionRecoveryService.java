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
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import java.math.BigDecimal;
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
            OrderStatus.EMERGENCY_ATTENTION_REQUIRED
    );

    private final ClosePositionRecordRepository closePositionRecordRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final PendingOrderService pendingOrderService;
    private final AutoTradeBudgetService budgetService;
    private final PositionSnapshotService positionSnapshotService;
    private final OkxOrderGateway okxOrderGateway;
    private final TradeReviewService tradeReviewService;
    private final TradeEventService tradeEventService;

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

    @Transactional
    public CloseRecoveryResult runOnce() {
        return runOnce(Instant.now());
    }

    @Transactional
    public CloseRecoveryResult runOnce(Instant now) {
        int closed = 0;
        ProtectionCancelRetryResult retryResult = retryFailedProtectionCancels(now);
        List<ClosePositionRecordEntity> submittedRecords = closePositionRecordRepository
                .findByUserNameAndStatus(currentUsername(), "CLOSE_SUBMITTED");
        List<PositionSummary> positions;
        try {
            positions = positionSnapshotService.positions();
        } catch (RuntimeException ex) {
            markPositionQueryFailed(submittedRecords, now, ex);
            return new CloseRecoveryResult(0, retryResult.retried(), retryResult.attentionRequired());
        }
        for (ClosePositionRecordEntity record : submittedRecords) {
            if (hasOpenPosition(record, positions)) {
                continue;
            }
            record.setStatus("CLOSED");
            record.setUpdatedAt(now);
            closePositionRecordRepository.save(record);
            closeLocalPendingOrder(record);
            invalidateProtectionOrders(record, now);
            createTradeReview(record);
            recordEvent(record, TradeEventType.CLOSE_CONFIRMED, "CLOSE_SUBMITTED", "CLOSED",
                    "CLOSE_CONFIRMED", "OKX当前持仓已消失，平仓确认完成");
            closed++;
        }
        closed += closeOrdersWithoutPositions(positions, now);
        return new CloseRecoveryResult(closed, retryResult.retried(), retryResult.attentionRequired());
    }

    private int closeOrdersWithoutPositions(List<PositionSummary> positions, Instant now) {
        int closed = 0;
        for (PendingOrder order : pendingOrderService.allOrders()) {
            if (!POSITION_CONFIRMED_STATUSES.contains(order.status()) || hasOpenPosition(order, positions)) {
                continue;
            }
            OrderStatus previousStatus = order.status();
            ClosePositionRecordEntity record = naturalCloseRecord(order, now);
            order.markClosed("POSITION_GONE");
            if (order.budgetReservationId() != null) {
                budgetService.release(order.budgetReservationId(), "POSITION_GONE");
                recordEvent(record, TradeEventType.BUDGET_RELEASED, "USED", "RELEASED",
                        "POSITION_GONE", "持仓消失后释放自动交易预算");
            }
            invalidateProtectionOrders(record, now);
            createTradeReview(record);
            recordEvent(record, TradeEventType.CLOSE_CONFIRMED, previousStatus.name(), "CLOSED",
                    "POSITION_GONE", "OKX当前持仓已消失，自动关闭本地交易生命周期");
            closed++;
        }
        return closed;
    }

    private ClosePositionRecordEntity naturalCloseRecord(PendingOrder order, Instant now) {
        List<ClosePositionRecordEntity> records = closePositionRecordRepository
                .findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(currentUsername(), order.id().toString());
        if (records != null && !records.isEmpty()) {
            ClosePositionRecordEntity record = records.get(0);
            record.setStatus("CLOSED");
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
        record.setStatus("CLOSED");
        record.setSource("POSITION_GONE");
        record.setPendingOrderId(order.id().toString());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return closePositionRecordRepository.save(record);
    }

    private ProtectionCancelRetryResult retryFailedProtectionCancels(Instant now) {
        if (tradeOrderRepository == null) {
            return new ProtectionCancelRetryResult(0, 0);
        }
        List<TradeOrderEntity> failedProtections = tradeOrderRepository.findByStatus("PROTECTION_CANCEL_FAILED");
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
                .filter(protection -> matchesPositionSide(record, protection))
                .toList());
        if (hasText(record.getPendingOrderId())) {
            List<TradeOrderEntity> boundProtections = tradeOrderRepository
                    .findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(
                            record.getPendingOrderId(), ACTIVE_PROTECTION_STATUSES);
            for (TradeOrderEntity protection : boundProtections) {
                if (matchesPositionSide(record, protection) && !protections.contains(protection)) {
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
        return !hasText(record.getPosSide())
                || record.getPosSide().equalsIgnoreCase(protection.getPosSide());
    }

    private boolean cancelProtectionOrder(TradeOrderEntity protection) {
        if (okxOrderGateway == null) {
            return true;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", protection.getInstId());
        if (hasText(protection.getOkxOrdId())) {
            payload.put("algoId", protection.getOkxOrdId());
        } else if (hasText(protection.getClOrdId())) {
            payload.put("algoClOrdId", protection.getClOrdId());
        }
        if (payload.size() <= 1) {
            return true;
        }
        try {
            okxOrderGateway.cancelAlgoOrder(payload);
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
                .filter(position -> record.getInstId().equalsIgnoreCase(position.instId()))
                .filter(position -> !hasText(record.getPosSide()) || record.getPosSide().equalsIgnoreCase(position.posSide()))
                .anyMatch(position -> decimal(position.size()).signum() > 0);
    }

    private boolean hasOpenPosition(PendingOrder order, List<PositionSummary> positions) {
        return positions.stream()
                .filter(position -> order.instId().equalsIgnoreCase(position.instId()))
                .filter(position -> !hasText(order.posSide()) || order.posSide().equalsIgnoreCase(position.posSide()))
                .anyMatch(position -> decimal(position.size()).signum() > 0);
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

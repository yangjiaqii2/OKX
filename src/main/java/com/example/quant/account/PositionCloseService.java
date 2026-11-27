package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.event.TradeEventPayload;
import com.example.quant.agent.event.TradeEventService;
import com.example.quant.agent.event.TradeEventType;
import com.example.quant.agent.execution.AutoTradeRecordRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.common.PageResult;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.okxtrade.TradingOperationContext;
import com.example.quant.okxtrade.TradingOperationType;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class PositionCloseService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PositionCloseService.class);
    private static final int SAFETY_CLOSE_MAX_ATTEMPTS = 3;
    private static final long SAFETY_CLOSE_RETRY_BACKOFF_SECONDS = 5;
    private static final List<OrderStatus> AUTO_CLOSE_BINDING_STATUSES = List.of(
            OrderStatus.SUBMITTED,
            OrderStatus.UNKNOWN_SUBMIT_STATUS,
            OrderStatus.ENTRY_FILLED,
            OrderStatus.PROTECTION_SUBMITTED,
            OrderStatus.ACTIVE,
            OrderStatus.ACTIVE_WITH_TP_WARNING,
            OrderStatus.SIDEWAYS_TIMEOUT,
            OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED,
            OrderStatus.MAX_HOLD_TIMEOUT,
            OrderStatus.PROTECTION_REPLAN_REQUIRED,
            OrderStatus.PROTECTION_RULES_STALE,
            OrderStatus.CLOSE_SUBMITTED
    );

    private final PositionSnapshotService positionSnapshotService;
    private final OkxTradeAdapter okxTradeAdapter;
    private final ClosePositionRecordRepository closePositionRecordRepository;
    private final PendingOrderService pendingOrderService;
    private final AutoTradeRecordRepository autoTradeRecordRepository;
    private final TradeEventService tradeEventService;

    public PositionCloseService(PositionSnapshotService positionSnapshotService, OkxTradeAdapter okxTradeAdapter) {
        this(positionSnapshotService, okxTradeAdapter, null, null, null);
    }

    public PositionCloseService(PositionSnapshotService positionSnapshotService, OkxTradeAdapter okxTradeAdapter,
                                ClosePositionRecordRepository closePositionRecordRepository) {
        this(positionSnapshotService, okxTradeAdapter, closePositionRecordRepository, null, null);
    }

    public PositionCloseService(PositionSnapshotService positionSnapshotService, OkxTradeAdapter okxTradeAdapter,
                                ClosePositionRecordRepository closePositionRecordRepository,
                                PendingOrderService pendingOrderService,
                                AutoTradeRecordRepository autoTradeRecordRepository) {
        this(positionSnapshotService, okxTradeAdapter, closePositionRecordRepository, pendingOrderService,
                autoTradeRecordRepository, null);
    }

    @Autowired
    public PositionCloseService(PositionSnapshotService positionSnapshotService, OkxTradeAdapter okxTradeAdapter,
                                ClosePositionRecordRepository closePositionRecordRepository,
                                PendingOrderService pendingOrderService,
                                AutoTradeRecordRepository autoTradeRecordRepository,
                                TradeEventService tradeEventService) {
        this.positionSnapshotService = positionSnapshotService;
        this.okxTradeAdapter = okxTradeAdapter;
        this.closePositionRecordRepository = closePositionRecordRepository;
        this.pendingOrderService = pendingOrderService;
        this.autoTradeRecordRepository = autoTradeRecordRepository;
        this.tradeEventService = tradeEventService;
    }

    public OrderExecutionResult closePosition(String instId, String posSide, String marginMode) {
        if (!hasText(instId)) {
            throw new IllegalArgumentException("平仓合约不能为空");
        }
        List<PositionSummary> matching = positionSnapshotService.positions().stream()
                .filter(item -> item != null && (!hasText(marginMode) || marginMode.equalsIgnoreCase(marginModeOrCross(item))))
                .filter(item -> PositionSideMatcher.matches(instId, posSide, null, item))
                .limit(2)
                .toList();
        if (matching.isEmpty()) {
            throw new IllegalArgumentException("当前没有可平仓持仓：" + instId);
        }
        if (matching.size() > 1) {
            throw new IllegalArgumentException("无法唯一确定要平仓的持仓");
        }
        PositionSummary position = matching.get(0);
        String closePosSide = "net".equalsIgnoreCase(position.posSide())
                ? (PositionSideMatcher.signedSize(instId, position.size()).signum() < 0 ? "short" : "long")
                : position.posSide();
        String closeMarginMode = marginModeOrCross(position);
        rejectDuplicateUnresolvedClose(position, closeMarginMode);
        String closeClOrdId = closeClientOrderId();
        ClosePositionRecordEntity record = createCloseIntent(position, closePosSide, closeMarginMode, closeClOrdId);
        Optional<PendingOrder> boundOrder = bindAutoTradeReference(record, position);
        boundOrder.ifPresent(okxTradeAdapter::settleEntryBeforeClose);
        boundOrder.map(PendingOrder::tradePlanId).map(UUID::toString).ifPresent(record::setTradePlanId);
        save(record);
        try {
            OrderExecutionResult result = okxTradeAdapter.closePosition(
                    position.instId(), position.posSide(), closeMarginMode, closeClOrdId,
                    TradingOperationContext.of(TradingOperationType.CLOSE_POSITION,
                            record.getUserName(), record.getPendingOrderId(),
                            boundOrder.map(PendingOrder::tradePlanId).map(UUID::toString).orElse(null))
                            .withDirection(closePosSide));
            record.setCloseOrderId(result.externalOrderId());
            record.setStatus("CLOSE_SUBMITTED");
            record.setRequestSent(true);
            record.setErrorClass(null);
            record.setRecoveryResult("ACK_SUBMITTED");
            record.setUpdatedAt(Instant.now());
            boundOrder.ifPresent(order -> order.markCloseSubmitted(
                    result.externalOrderId(), "MANUAL_CLOSE_SUBMITTED"));
            save(record);
            recordManualCloseSubmitted(record);
            return result;
        } catch (RuntimeException ex) {
            boolean beforeSend = beforeSendFailure(ex);
            boolean rejected = exchangeRejected(ex);
            record.setStatus(beforeSend ? "CLOSE_FAILED_BEFORE_SEND" : rejected ? "CLOSE_REJECTED" : "CLOSE_SUBMIT_UNKNOWN");
            record.setRequestSent(!beforeSend);
            record.setErrorClass(beforeSend ? "LOCAL_VALIDATION" : rejected ? "EXCHANGE_REJECTED" : "UNKNOWN_AFTER_SEND");
            record.setRecoveryResult(beforeSend ? "NOT_SENT" : rejected ? "TERMINAL_REJECTED" : "QUERY_REQUIRED");
            record.setErrorMessage(compact(ex.getMessage()));
            record.setUpdatedAt(Instant.now());
            boundOrder.ifPresent(order -> order.markCloseSubmitted(null,
                    beforeSend ? "MANUAL_CLOSE_FAILED_BEFORE_SEND" : "MANUAL_CLOSE_SUBMIT_UNKNOWN"));
            save(record);
            throw ex;
        }
    }

    /**
     * Terminal risk-reduction contract used when an already-filled position
     * cannot be protected by its approved canonical stop. The client id is
     * deterministic so an UNKNOWN response is recovered by the existing close
     * reconciliation flow instead of submitting a second close order.
     */
    public OrderExecutionResult closePositionForSafety(PendingOrder order, PositionSummary position, String reason) {
        if (order == null || position == null || decimal(position.size()).signum() == 0) {
            throw new IllegalArgumentException("安全减仓要求已有正向持仓");
        }
        String username = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        String safetyReason = hasText(reason) ? reason : "CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY";
        String closeClOrdId = safetyCloseClientOrderId(order, safetyReason);
        Instant attemptAt = Instant.now();
        ClosePositionRecordEntity existing = closePositionRecordRepository == null ? null
                : closePositionRecordRepository.findByUserNameAndCloseClOrdId(username, closeClOrdId).orElse(null);
        if (existing != null) {
            if (List.of("CLOSE_SUBMITTING", "CLOSE_SUBMIT_UNKNOWN", "CLOSE_SUBMITTED", "CLOSED")
                    .contains(existing.getStatus())) {
                return OrderExecutionResult.submitted(existing.getCloseOrderId(),
                        "SAFETY_CLOSE_ALREADY_RECORDED: " + safetyReason);
            }
            if ("CLOSE_REJECTED".equals(existing.getStatus())) {
                throw new IllegalStateException("SAFETY_CLOSE_REJECTED: " + compact(existing.getErrorMessage()));
            }
            if ("EMERGENCY_ATTENTION_REQUIRED".equals(existing.getStatus())
                    || existing.getRetryCount() >= SAFETY_CLOSE_MAX_ATTEMPTS) {
                existing.setStatus("EMERGENCY_ATTENTION_REQUIRED");
                existing.setNextAttemptAt(null);
                existing.setUpdatedAt(attemptAt);
                save(existing);
                throw new IllegalStateException("SAFETY_CLOSE_RETRY_EXHAUSTED: " + safetyReason);
            }
            existing.setStatus("CLOSE_SUBMITTING");
            existing.setRequestSent(false);
            existing.setErrorMessage(safetyReason);
            existing.setRecoveryResult("SAFETY_RETRY_CLAIMED");
            existing.setRetryCount(existing.getRetryCount() + 1);
            existing.setLastAttemptAt(attemptAt);
            existing.setNextAttemptAt(null);
            existing.setUpdatedAt(attemptAt);
        }
        ClosePositionRecordEntity record = existing == null
                ? createSafetyCloseIntent(order, position, username, closeClOrdId, safetyReason)
                : existing;
        if (existing == null) {
            record.setRetryCount(1);
            record.setLastAttemptAt(attemptAt);
        }
        try {
            okxTradeAdapter.settleEntryBeforeClose(order);
        } catch (RuntimeException entryFailure) {
            // An unresolved entry must not prevent emergency reduction of existing exposure.
            log.warn("Emergency close requires entry reconciliation pendingOrderId={}", order.id(), entryFailure);
        }
        save(record);
        try {
            OrderExecutionResult result = okxTradeAdapter.closePosition(
                    position.instId(), position.posSide(),
                    hasText(position.marginMode()) ? position.marginMode() : order.tdMode(),
                    closeClOrdId,
                    TradingOperationContext.of(TradingOperationType.CLOSE_POSITION,
                            username, order.id().toString(),
                            order.tradePlanId() == null ? null : order.tradePlanId().toString()).withDirection(order.posSide()));
            record.setCloseOrderId(result.externalOrderId());
            record.setStatus("CLOSE_SUBMITTED");
            record.setRequestSent(true);
            record.setErrorClass(null);
            record.setRecoveryResult("SAFETY_CLOSE_ACK_SUBMITTED");
            record.setErrorMessage(safetyReason);
            record.setNextAttemptAt(null);
            record.setUpdatedAt(attemptAt);
            order.markCloseSubmitted(result.externalOrderId(), safetyReason);
            save(record);
            return result;
        } catch (RuntimeException ex) {
            boolean beforeSend = beforeSendFailure(ex);
            boolean rejected = exchangeRejected(ex);
            boolean retryable = beforeSend && retryableSafetyFailure(ex);
            boolean exhausted = record.getRetryCount() >= SAFETY_CLOSE_MAX_ATTEMPTS;
            record.setStatus(retryable && !exhausted ? "CLOSE_FAILED_BEFORE_SEND"
                    : beforeSend || rejected ? "EMERGENCY_ATTENTION_REQUIRED" : "CLOSE_SUBMIT_UNKNOWN");
            record.setRequestSent(!beforeSend);
            record.setErrorClass(beforeSend ? "LOCAL_VALIDATION"
                    : rejected ? "EXCHANGE_REJECTED" : "UNKNOWN_AFTER_SEND");
            record.setRecoveryResult(retryable && !exhausted ? "SAFETY_RETRY_SCHEDULED"
                    : beforeSend ? "SAFETY_TERMINAL_ATTENTION"
                    : rejected ? "SAFETY_TERMINAL_REJECTED" : "SAFETY_QUERY_REQUIRED");
            record.setErrorMessage(safetyReason + ": " + compact(ex.getMessage()));
            record.setLastAttemptAt(attemptAt);
            record.setNextAttemptAt(retryable && !exhausted
                    ? attemptAt.plusSeconds(SAFETY_CLOSE_RETRY_BACKOFF_SECONDS) : null);
            record.setUpdatedAt(attemptAt);
            if (beforeSend) {
                order.markEmergencyAttentionRequired((retryable && !exhausted
                        ? "SAFETY_CLOSE_RETRYABLE_NOT_SENT: " : "SAFETY_CLOSE_NOT_SENT: ") + safetyReason);
            } else {
                order.markCloseSubmitted(null, "SAFETY_CLOSE_UNKNOWN: " + safetyReason);
            }
            save(record);
            throw ex;
        }
    }

    private static boolean retryableSafetyFailure(RuntimeException ex) {
        String message = ex == null ? "" : String.valueOf(ex.getMessage()).toLowerCase();
        return !message.contains("invalid request")
                && !message.contains("position_min_size")
                && !message.contains("canonical")
                && !message.contains("risk");
    }

    public PageResult<ClosePositionRecordView> closeRecords(int page, int size) {
        if (closePositionRecordRepository == null) {
            return new PageResult<>(List.of(), 0, Math.max(0, page), Math.max(1, size));
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        String username = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        List<ClosePositionRecordView> rows = closePositionRecordRepository
                .findByUserNameOrderByCreatedAtDesc(username, PageRequest.of(safePage, safeSize))
                .stream()
                .map(ClosePositionRecordView::from)
                .toList();
        return new PageResult<>(rows, closePositionRecordRepository.countByUserName(username), safePage, safeSize);
    }

    private ClosePositionRecordEntity createCloseIntent(PositionSummary position, String posSide,
                                                        String marginMode, String closeClOrdId) {
        Instant now = Instant.now();
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName(AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER));
        record.setInstId(position.instId());
        record.setPosSide(posSide);
        record.setMarginMode(marginMode);
        record.setCloseClOrdId(closeClOrdId);
        record.setSize(decimal(position.size()).abs());
        record.setAvgPx(position.avgPrice());
        record.setStatus("CLOSE_SUBMITTING");
        record.setSource("MANUAL");
        record.setRequestSent(false);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    private static ClosePositionRecordEntity createSafetyCloseIntent(PendingOrder order,
                                                                       PositionSummary position,
                                                                       String username,
                                                                       String closeClOrdId,
                                                                       String reason) {
        Instant now = Instant.now();
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName(username);
        record.setInstId(position.instId());
        record.setPosSide(position.posSide());
        record.setMarginMode(hasText(position.marginMode()) ? position.marginMode() : order.tdMode());
        record.setCloseClOrdId(closeClOrdId);
        record.setSize(decimal(position.size()).abs());
        record.setAvgPx(position.avgPrice());
        record.setStatus("CLOSE_SUBMITTING");
        record.setSource("SAFETY");
        record.setRequestSent(false);
        record.setErrorMessage(reason);
        record.setRecoveryResult("SAFETY_CLOSE_PENDING");
        record.setPendingOrderId(order.id().toString());
        record.setTradePlanId(order.tradePlanId() == null ? null : order.tradePlanId().toString());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    private static String closeClientOrderId() {
        return "CLOSE" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static String safetyCloseClientOrderId(PendingOrder order, String reason) {
        String reasonCode = reason == null ? "SLU"
                : reason.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        reasonCode = reasonCode.length() <= 3 ? reasonCode : reasonCode.substring(0, 3);
        return "SAFE" + order.id().toString().replace("-", "").substring(0, 24) + reasonCode;
    }

    private Optional<PendingOrder> bindAutoTradeReference(ClosePositionRecordEntity record, PositionSummary position) {
        Optional<PendingOrder> matched = matchingActivePendingOrder(position, record.getMarginMode());
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        PendingOrder order = matched.get();
        record.setPendingOrderId(order.id().toString());
        // Persist the lifecycle direction; the exchange payload still uses its observed NET/hedge mode.
        record.setPosSide(PositionSideMatcher.entryDirection(order.posSide(), order.side()));
        if (autoTradeRecordRepository != null) {
            autoTradeRecordRepository.findFirstByPendingOrderIdOrderByCreatedAtDesc(order.id().toString())
                    .ifPresent(autoTradeRecord -> record.setAutoTradeRecordId(autoTradeRecord.getId()));
        }
        return matched;
    }

    private void rejectDuplicateUnresolvedClose(PositionSummary position, String marginMode) {
        if (closePositionRecordRepository == null) {
            return;
        }
        String username = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        for (String status : List.of("CLOSE_SUBMITTING", "CLOSE_SUBMIT_UNKNOWN", "CLOSE_SUBMITTED")) {
            boolean duplicate = closePositionRecordRepository.findByUserNameAndStatus(username, status).stream()
                    .filter(record -> !hasText(record.getMarginMode()) || marginMode.equalsIgnoreCase(record.getMarginMode()))
                    .anyMatch(record -> PositionSideMatcher.matches(record.getInstId(), record.getPosSide(), null, position));
            if (duplicate) {
                throw new IllegalStateException("已有待确认平仓，请先等待OKX持仓同步，禁止重复提交：" + position.instId());
            }
        }
    }

    private void save(ClosePositionRecordEntity record) {
        if (closePositionRecordRepository != null) {
            closePositionRecordRepository.save(record);
        }
    }

    private void recordManualCloseSubmitted(ClosePositionRecordEntity record) {
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
                    TradeEventType.MANUAL_CLOSE_SUBMITTED,
                    null,
                    "CLOSE_SUBMITTED",
                    "MANUAL_CLOSE_SUBMITTED",
                    "用户手动提交平仓",
                    record.getCloseOrderId(),
                    record.getCloseClOrdId(),
                    null
            ));
        } catch (RuntimeException ignored) {
            // Event persistence must not block manual close submission.
        }
    }

    private Optional<PendingOrder> matchingActivePendingOrder(PositionSummary position, String marginMode) {
        if (pendingOrderService == null) {
            return Optional.empty();
        }
        return pendingOrderService.allOrders().stream()
                .filter(order -> AUTO_CLOSE_BINDING_STATUSES.contains(order.status()))
                .filter(order -> marginMode.equalsIgnoreCase(hasText(order.tdMode()) ? order.tdMode() : "cross"))
                .filter(order -> PositionSideMatcher.matches(order.instId(), order.posSide(), order.side(), position))
                .findFirst();
    }

    private static String marginModeOrCross(PositionSummary position) {
        return hasText(position.marginMode()) ? position.marginMode() : "cross";
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

    private static String compact(String value) {
        if (!hasText(value)) {
            return "UNKNOWN_CLOSE_SUBMIT_ERROR";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static boolean beforeSendFailure(RuntimeException ex) {
        String message = ex == null ? "" : String.valueOf(ex.getMessage()).toLowerCase();
        return message.contains("未发送") || message.contains("未向交易所发送") || message.contains("not sent")
                || message.contains("position_not_confirmed")
                || message.contains("trading_mode_mismatch")
                || message.contains("owner_mismatch")
                || message.contains("reduction_");
    }

    private static boolean exchangeRejected(RuntimeException ex) {
        String message = ex == null ? "" : String.valueOf(ex.getMessage()).toLowerCase();
        return message.contains("okx code=") || message.contains("okx http ")
                || message.contains("s_code=") || message.contains("smsg=");
    }
}

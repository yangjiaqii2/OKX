package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.event.TradeEventPayload;
import com.example.quant.agent.event.TradeEventService;
import com.example.quant.agent.event.TradeEventType;
import com.example.quant.agent.execution.AutoTradeRecordRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.common.PageResult;
import com.example.quant.okxtrade.OkxTradeAdapter;
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
        PositionSummary position = positionSnapshotService.positions().stream()
                .filter(item -> instId.equalsIgnoreCase(item.instId()))
                .filter(item -> !hasText(posSide) || posSide.equalsIgnoreCase(item.posSide()))
                .filter(item -> decimal(item.size()).signum() > 0)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前没有可平仓持仓：" + instId));
        String closePosSide = hasText(posSide) ? posSide : position.posSide();
        String closeMarginMode = hasText(marginMode) ? marginMode : position.marginMode();
        if (!hasText(closeMarginMode)) {
            closeMarginMode = "cross";
        }
        String closeClOrdId = closeClientOrderId();
        OrderExecutionResult result = okxTradeAdapter.closePosition(position.instId(), closePosSide, closeMarginMode, closeClOrdId);
        recordCloseSubmitted(position, closePosSide, closeMarginMode, result, closeClOrdId);
        return result;
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

    private void recordCloseSubmitted(PositionSummary position, String posSide, String marginMode,
                                      OrderExecutionResult result, String closeClOrdId) {
        if (closePositionRecordRepository == null) {
            return;
        }
        Instant now = Instant.now();
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName(AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER));
        record.setInstId(position.instId());
        record.setPosSide(posSide);
        record.setMarginMode(marginMode);
        record.setCloseOrderId(result.externalOrderId());
        record.setCloseClOrdId(closeClOrdId);
        record.setSize(decimal(position.size()));
        record.setAvgPx(position.avgPrice());
        record.setStatus("CLOSE_SUBMITTED");
        record.setSource("MANUAL");
        bindAutoTradeLifecycle(record, result);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        closePositionRecordRepository.save(record);
        recordManualCloseSubmitted(record);
    }

    private static String closeClientOrderId() {
        return "CLOSE" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private void bindAutoTradeLifecycle(ClosePositionRecordEntity record, OrderExecutionResult result) {
        Optional<PendingOrder> matched = matchingAutoPendingOrder(record.getInstId(), record.getPosSide());
        if (matched.isEmpty()) {
            return;
        }
        PendingOrder order = matched.get();
        record.setPendingOrderId(order.id().toString());
        if (autoTradeRecordRepository != null) {
            autoTradeRecordRepository.findFirstByPendingOrderIdOrderByCreatedAtDesc(order.id().toString())
                    .ifPresent(autoTradeRecord -> record.setAutoTradeRecordId(autoTradeRecord.getId()));
        }
        order.markCloseSubmitted(result.externalOrderId(), "MANUAL_CLOSE_SUBMITTED");
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

    private Optional<PendingOrder> matchingAutoPendingOrder(String instId, String posSide) {
        if (pendingOrderService == null) {
            return Optional.empty();
        }
        return pendingOrderService.allOrders().stream()
                .filter(order -> order.budgetReservationId() != null)
                .filter(order -> instId.equalsIgnoreCase(order.instId()))
                .filter(order -> !hasText(posSide) || posSide.equalsIgnoreCase(order.posSide()))
                .filter(order -> AUTO_CLOSE_BINDING_STATUSES.contains(order.status()))
                .findFirst();
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
}

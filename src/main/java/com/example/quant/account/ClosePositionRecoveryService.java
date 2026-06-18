package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClosePositionRecoveryService {
    private static final List<String> ACTIVE_PROTECTION_STATUSES = List.of(
            "SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE"
    );

    private final ClosePositionRecordRepository closePositionRecordRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final PendingOrderService pendingOrderService;
    private final AutoTradeBudgetService budgetService;
    private final PositionSnapshotService positionSnapshotService;

    public ClosePositionRecoveryService(ClosePositionRecordRepository closePositionRecordRepository,
                                        TradeOrderRepository tradeOrderRepository,
                                        PendingOrderService pendingOrderService,
                                        AutoTradeBudgetService budgetService,
                                        PositionSnapshotService positionSnapshotService) {
        this.closePositionRecordRepository = closePositionRecordRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.pendingOrderService = pendingOrderService;
        this.budgetService = budgetService;
        this.positionSnapshotService = positionSnapshotService;
    }

    @Transactional
    public CloseRecoveryResult runOnce() {
        return runOnce(Instant.now());
    }

    @Transactional
    public CloseRecoveryResult runOnce(Instant now) {
        int closed = 0;
        List<PositionSummary> positions = positions();
        for (ClosePositionRecordEntity record : closePositionRecordRepository.findByStatus("CLOSE_SUBMITTED")) {
            if (hasOpenPosition(record, positions)) {
                continue;
            }
            record.setStatus("CLOSED");
            record.setUpdatedAt(now);
            closePositionRecordRepository.save(record);
            closeLocalPendingOrder(record);
            invalidateProtectionOrders(record, now);
            closed++;
        }
        return new CloseRecoveryResult(closed);
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
            }
        } catch (IllegalArgumentException ignored) {
            // A close record can exist for manual positions that do not have a local auto-trade pending order.
        }
    }

    private void invalidateProtectionOrders(ClosePositionRecordEntity record, Instant now) {
        if (!hasText(record.getPendingOrderId())) {
            return;
        }
        List<TradeOrderEntity> protections = tradeOrderRepository
                .findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(record.getPendingOrderId(), ACTIVE_PROTECTION_STATUSES);
        for (TradeOrderEntity protection : protections) {
            protection.setStatus("INVALID");
            protection.setOkxState("INVALID");
            protection.setErrorMessage("POSITION_CLOSED");
            protection.setUpdatedAt(now);
        }
        if (!protections.isEmpty()) {
            tradeOrderRepository.saveAll(protections);
        }
    }

    private boolean hasOpenPosition(ClosePositionRecordEntity record, List<PositionSummary> positions) {
        return positions.stream()
                .filter(position -> record.getInstId().equalsIgnoreCase(position.instId()))
                .filter(position -> !hasText(record.getPosSide()) || record.getPosSide().equalsIgnoreCase(position.posSide()))
                .anyMatch(position -> decimal(position.size()).signum() > 0);
    }

    private List<PositionSummary> positions() {
        try {
            return positionSnapshotService.positions();
        } catch (RuntimeException ex) {
            return List.of();
        }
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

    public record CloseRecoveryResult(int closed) {
    }
}

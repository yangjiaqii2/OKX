package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.order.PendingOrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PositionReconciliationService {
    private final PositionSnapshotService snapshots;
    private final TradeFillRepository fills;
    private final ExchangePositionRepository positions;
    private final PendingOrderService pendingOrders;

    public PositionReconciliationService(PositionSnapshotService snapshots, TradeFillRepository fills,
                                         ExchangePositionRepository positions) {
        this(snapshots, fills, positions, null);
    }

    @Autowired
    public PositionReconciliationService(PositionSnapshotService snapshots, TradeFillRepository fills,
                                         ExchangePositionRepository positions,
                                         PendingOrderService pendingOrders) {
        this.snapshots = snapshots;
        this.fills = fills;
        this.positions = positions;
        this.pendingOrders = pendingOrders;
    }

    @Transactional
    public Result reconcile() {
        String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        List<PositionSummary> exchange = snapshots.positions();
        List<TradeFillEntity> localFills = fills.findByUserName(owner);
        Set<String> activePendingOrderIds = activePendingOrderIds();
        Set<String> activeKeys = new HashSet<>();
        int active = 0;
        int unmanaged = 0;
        for (PositionSummary position : exchange) {
            BigDecimal exchangeSize = decimal(position.size());
            if (exchangeSize.signum() == 0) {
                continue;
            }
            active++;
            String posSide = normalizedSide(position.posSide());
            activeKeys.add(key(position.instId(), posSide));
            BigDecimal localSize = localExposure(localFills, activePendingOrderIds, position.instId(), posSide);
            boolean traceable = hasTraceableFill(localFills, activePendingOrderIds, position.instId(), posSide);
            boolean managed = traceable && localSize.compareTo(exchangeSize) == 0;
            if (!managed) {
                unmanaged++;
            }
            ExchangePositionEntity row = positions.findByUserNameAndInstIdAndPosSide(
                    owner, position.instId(), posSide).orElseGet(ExchangePositionEntity::new);
            row.setUserName(owner);
            row.setInstId(position.instId());
            row.setPosSide(posSide);
            row.setSize(exchangeSize);
            row.setAveragePrice(position.avgPrice());
            row.setUnrealizedPnl(position.unrealizedPnl());
            row.setOwnership(managed ? "MANAGED" : "UNMANAGED");
            row.setReason(managed ? pendingOrders == null ? "LOCAL_FILL_CHAIN_MATCHED" : "ACTIVE_LOCAL_FILL_CHAIN_MATCHED"
                    : traceable ? "EXCHANGE_LOCAL_SIZE_MISMATCH" : "NO_TRACEABLE_LOCAL_FILL_CHAIN");
            row.setUpdatedAt(Instant.now());
            positions.save(row);
        }
        for (ExchangePositionEntity row : positions.findByUserName(owner)) {
            if (row.getSize() != null && row.getSize().signum() != 0
                    && !activeKeys.contains(key(row.getInstId(), row.getPosSide()))) {
                row.setSize(BigDecimal.ZERO);
                row.setOwnership("CLOSED");
                row.setReason("EXCHANGE_POSITION_ABSENT");
                row.setUpdatedAt(Instant.now());
                positions.save(row);
            }
        }
        return new Result(active, unmanaged, false, null);
    }

    public long unmanagedCount() {
        String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        return positions.countByUserNameAndOwnership(owner, "UNMANAGED");
    }

    private Set<String> activePendingOrderIds() {
        if (pendingOrders == null) {
            return Set.of();
        }
        return pendingOrders.allOrders().stream()
                .map(order -> order.id().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private BigDecimal localExposure(List<TradeFillEntity> fills, Set<String> activePendingOrderIds,
                                     String instId, String posSide) {
        return fills.stream()
                .filter(fill -> instId.equals(fill.getInstId()))
                .filter(fill -> normalizedSide(fill.getPosSide()).equals(posSide))
                .filter(fill -> traceable(fill, activePendingOrderIds))
                .map(fill -> signedSize(fill, posSide))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean hasTraceableFill(List<TradeFillEntity> fills, Set<String> activePendingOrderIds,
                                     String instId, String posSide) {
        return fills.stream().anyMatch(fill -> instId.equals(fill.getInstId())
                && normalizedSide(fill.getPosSide()).equals(posSide)
                && traceable(fill, activePendingOrderIds));
    }

    private boolean traceable(TradeFillEntity fill, Set<String> activePendingOrderIds) {
        if (pendingOrders != null) {
            return hasText(fill.getPendingOrderId()) && activePendingOrderIds.contains(fill.getPendingOrderId());
        }
        return hasText(fill.getTradePlanId()) || hasText(fill.getPendingOrderId());
    }

    private static BigDecimal signedSize(TradeFillEntity fill, String posSide) {
        BigDecimal size = fill.getFillSize() == null ? BigDecimal.ZERO : fill.getFillSize();
        boolean positive = "short".equals(posSide)
                ? "sell".equalsIgnoreCase(fill.getSide())
                : "buy".equalsIgnoreCase(fill.getSide());
        return positive ? size : size.negate();
    }

    private static BigDecimal decimal(String value) {
        try {
            return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("INVALID_EXCHANGE_POSITION_SIZE");
        }
    }

    private static String normalizedSide(String value) {
        return value == null || value.isBlank() ? "net" : value.trim().toLowerCase();
    }

    private static String key(String instId, String posSide) {
        return instId + "|" + posSide;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Result(int activePositions, int unmanagedPositions, boolean failed, String errorMessage) {
    }
}

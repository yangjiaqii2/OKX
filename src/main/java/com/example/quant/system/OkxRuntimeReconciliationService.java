package com.example.quant.system;

import com.example.quant.account.PositionReconciliationService;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.okxtrade.OkxCurrentOrderService;
import com.example.quant.okxtrade.OkxCurrentOrderSyncService;
import com.example.quant.ledger.AccountBillLedgerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OkxRuntimeReconciliationService {
    private final OkxCurrentOrderSyncService orders;
    private final PositionReconciliationService positions;
    private final OkxCurrentOrderService currentOrders;
    private final PositionSnapshotService positionSnapshots;
    private AccountBillLedgerService bills;

    public OkxRuntimeReconciliationService(OkxCurrentOrderSyncService orders,
                                           PositionReconciliationService positions) {
        this(orders, positions, null, null);
    }

    @Autowired
    public OkxRuntimeReconciliationService(OkxCurrentOrderSyncService orders,
                                           PositionReconciliationService positions,
                                           OkxCurrentOrderService currentOrders,
                                           PositionSnapshotService positionSnapshots) {
        this.orders = orders;
        this.positions = positions;
        this.currentOrders = currentOrders;
        this.positionSnapshots = positionSnapshots;
    }

    @Autowired(required = false)
    void configureBillLedger(AccountBillLedgerService bills) {
        this.bills = bills;
    }

    public Result reconcile(String owner) {
        if (owner == null || owner.isBlank()) {
            return new Result(false, "TRADING_OWNER_MISSING", 0);
        }
        try {
            return AuthUserContext.callAs(owner, this::reconcileCurrentOwner);
        } catch (RuntimeException ex) {
            return new Result(false, "EXCHANGE_RECONCILIATION_FAILED", 0);
        }
    }

    /** Read-only startup boundary check. It never cancels or modifies exchange objects. */
    public SafetyReport startupSafety(String owner) {
        if (owner == null || owner.isBlank() || currentOrders == null || positionSnapshots == null) {
            return new SafetyReport(false, false, false, false, false,
                    "EXCHANGE_PREFLIGHT_UNAVAILABLE");
        }
        try {
            return AuthUserContext.callAs(owner, this::collectStartupSafety);
        } catch (RuntimeException ex) {
            return new SafetyReport(false, false, false, false, false,
                    "EXCHANGE_PREFLIGHT_FAILED");
        }
    }

    private SafetyReport collectStartupSafety() {
        boolean positionFlat = positionSnapshots.positions().stream()
                .allMatch(position -> position == null || decimal(position.size()).signum() == 0);
        var normal = currentOrders.currentOrders();
        var algos = currentOrders.currentAlgoOrders();
        boolean normalFlat = normal == null || normal.isEmpty();
        boolean ownedAlgoFlat = true;
        String reason = "EXCHANGE_FLAT";
        if (algos != null && !algos.isEmpty()) {
            boolean hasForeign = algos.stream().anyMatch(item -> !systemOwned(item.algoClOrdId()));
            ownedAlgoFlat = false;
            reason = hasForeign ? "EXCHANGE_ALGO_OWNERSHIP_UNKNOWN"
                    : "EXCHANGE_SYSTEM_OWNED_ALGO_ACTIVE";
        } else if (!positionFlat) {
            reason = "EXCHANGE_ORPHAN_POSITION";
        } else if (!normalFlat) {
            reason = "EXCHANGE_ACTIVE_ENTRY_ORDER";
        }
        return new SafetyReport(true, positionFlat, normalFlat, ownedAlgoFlat,
                positionFlat && normalFlat && ownedAlgoFlat, reason);
    }

    private static boolean systemOwned(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return false;
        }
        String normalized = clientOrderId.trim().toLowerCase();
        // AUTO is the production entry namespace; lc/qa cover lifecycle and
        // test-generated protection ids. Anything else remains foreign/unknown.
        return normalized.startsWith("auto") || normalized.startsWith("lc") || normalized.startsWith("qa");
    }

    private static java.math.BigDecimal decimal(String value) {
        try {
            return value == null || value.isBlank() ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(value).abs();
        } catch (NumberFormatException ex) {
            return java.math.BigDecimal.ZERO;
        }
    }

    private Result reconcileCurrentOwner() {
        OkxCurrentOrderSyncService.SyncResult orderResult = orders.syncOnce();
        if (orderResult == null || orderResult.failed() || orderResult.errorMessage() != null) {
            return new Result(false, orderResult == null || orderResult.errorMessage() == null
                    ? "ORDER_RECONCILIATION_FAILED" : orderResult.errorMessage(), 0);
        }
        PositionReconciliationService.Result positionResult = positions.reconcile();
        if (positionResult == null || positionResult.failed()) {
            return new Result(false, "POSITION_RECONCILIATION_FAILED", 0);
        }
        if (positionResult.unmanagedPositions() > 0) {
            return new Result(false, "UNMANAGED_POSITIONS:" + positionResult.unmanagedPositions(),
                    positionResult.unmanagedPositions());
        }
        if (bills != null) {
            AccountBillLedgerService.SyncResult billResult = bills.syncRecent();
            if (billResult == null || !billResult.ready()) {
                return new Result(false, "LEDGER_RECONCILIATION_FAILED", 0);
            }
        }
        return new Result(true, "EXCHANGE_RECONCILED", 0);
    }

    public record Result(boolean ready, String reason, int unmanagedPositions) {
    }

    public record SafetyReport(boolean dbFlat, boolean exchangePositionFlat,
                               boolean exchangeOrdersFlat, boolean ownedAlgoFlat,
                               boolean legacyCanonicalSafe, String reason) {
        public boolean ready() {
            return dbFlat && exchangePositionFlat && exchangeOrdersFlat
                    && ownedAlgoFlat && legacyCanonicalSafe;
        }
    }
}

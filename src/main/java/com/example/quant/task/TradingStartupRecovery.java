package com.example.quant.task;

import com.example.quant.account.OkxAccountBoundEvent;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingProperties;
import com.example.quant.system.OkxRuntimeReconciliationService;
import com.example.quant.system.SystemControlService;
import com.example.quant.system.TradingEngineState;
import com.example.quant.system.TradingReadinessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
public class TradingStartupRecovery {
    private final AutoTradeRecoveryTask recoveryTask;
    private final TradingReadinessService readiness;
    private final OkxRuntimeReconciliationService exchangeReconciliation;
    private final SystemControlService systemControl;
    private final TradingProperties trading;

    public TradingStartupRecovery(AutoTradeRecoveryTask recoveryTask, TradingReadinessService readiness) {
        this(recoveryTask, readiness, null, null, null);
    }

    public TradingStartupRecovery(AutoTradeRecoveryTask recoveryTask, TradingReadinessService readiness,
                                  OkxRuntimeReconciliationService exchangeReconciliation,
                                  SystemControlService systemControl) {
        this(recoveryTask, readiness, exchangeReconciliation, systemControl, null);
    }

    @Autowired
    public TradingStartupRecovery(AutoTradeRecoveryTask recoveryTask, TradingReadinessService readiness,
                                  OkxRuntimeReconciliationService exchangeReconciliation,
                                  SystemControlService systemControl, TradingProperties trading) {
        this.recoveryTask = recoveryTask;
        this.readiness = readiness;
        this.exchangeReconciliation = exchangeReconciliation;
        this.systemControl = systemControl;
        this.trading = trading;
    }

    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        String owner = systemControl == null
                ? OkxCredentialStore.SYSTEM_USER : systemControl.autoTradeOwnerUsername();
        recover(owner, false);
    }

    @EventListener
    public void recoverAfterBinding(OkxAccountBoundEvent event) {
        if (systemControl != null) {
            systemControl.selectTradingOwner(event.username());
        }
        recover(event.username(), true);
    }

    private void recover(String owner, boolean explicitOwner) {
        int legacyActive;
        try {
            legacyActive = AuthUserContext.callAs(owner, recoveryTask::legacyActiveLifecycleCount);
        } catch (RuntimeException ex) {
            legacyActive = 1;
        }
        if (legacyActive > 0) {
            readiness.updateStartup(TradingEngineState.BLOCKED, "LEGACY_ACTIVE_LIFECYCLE_REQUIRES_FLAT_V34_BOUNDARY");
            return;
        }
        try {
            if (recoveryTask.unresolvedPendingOrderCount() > 0
                    || recoveryTask.unresolvedCloseIntentCount() > 0) {
                readiness.updateStartup(TradingEngineState.BLOCKED,
                        "V34_DEPLOYMENT_REQUIRES_NO_UNRESOLVED_LIFECYCLE");
                return;
            }
        } catch (RuntimeException ex) {
            readiness.updateStartup(TradingEngineState.BLOCKED, "V34_DEPLOYMENT_DB_PREFLIGHT_FAILED");
            return;
        }
        readiness.updateStartup(TradingEngineState.RECOVERING,
                explicitOwner ? "ACCOUNT_BOUND_RECONCILIATION" : "STARTUP_RECONCILIATION");
        try {
            AutoTradeRecoveryTask.RecoveryResult result = explicitOwner
                    ? recoveryTask.runOnceForOwner(owner) : recoveryTask.runOnce();
            if (!result.exchangeSynchronized() || result.attentionRequired() > 0) {
                readiness.updateStartup(TradingEngineState.BLOCKED,
                        !result.exchangeSynchronized() ? "EXCHANGE_RECONCILIATION_FAILED"
                                : "MANUAL_RECONCILIATION_REQUIRED");
                return;
            }
            if (exchangeReconciliation != null) {
                OkxRuntimeReconciliationService.Result exchangeResult = exchangeReconciliation.reconcile(owner);
                if (!exchangeResult.ready()) {
                    readiness.updateStartup(TradingEngineState.BLOCKED, exchangeResult.reason());
                    return;
                }
                OkxRuntimeReconciliationService.SafetyReport safety = exchangeReconciliation.startupSafety(owner);
                if (safety == null || !safety.ready()) {
                    readiness.updateStartup(TradingEngineState.BLOCKED,
                            safety == null ? "EXCHANGE_PREFLIGHT_UNAVAILABLE" : safety.reason());
                    return;
                }
            }
            if (trading != null && trading.mode().writesEnabled()) {
                readiness.updateStartup(TradingEngineState.DEGRADED, "PRIVATE_WS_RECONCILIATION_PENDING");
            } else {
                readiness.updateStartup(TradingEngineState.READY,
                        explicitOwner ? "ACCOUNT_BOUND_RECONCILIATION_COMPLETE" : "STARTUP_RECONCILIATION_COMPLETE");
            }
        } catch (RuntimeException ex) {
            readiness.updateStartup(TradingEngineState.BLOCKED,
                    explicitOwner ? "ACCOUNT_BOUND_RECOVERY_FAILED" : "STARTUP_RECOVERY_FAILED");
        }
    }
}

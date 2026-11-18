package com.example.quant.agent.execution;

import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxCurrentOrderSyncService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Prevents an expensive new-entry market scan when exchange capacity is full.
 *
 * <p>This gate does not stop the recovery/lifecycle scheduler. It is deliberately
 * fail-open when exchange state is unavailable so an unknown state is never
 * mistaken for a confirmed full-capacity state.
 */
@Service
public class PositionCapacityScanGate {
    private static final Logger log = LoggerFactory.getLogger(PositionCapacityScanGate.class);

    private final AgentProperties agentProperties;
    private final PositionSnapshotService positionSnapshotService;
    private final OkxCurrentOrderSyncService currentOrderSyncService;
    private final TradeOrderRecordService tradeOrderRecordService;

    public PositionCapacityScanGate(AgentProperties agentProperties,
                                    PositionSnapshotService positionSnapshotService,
                                    OkxCurrentOrderSyncService currentOrderSyncService,
                                    TradeOrderRecordService tradeOrderRecordService) {
        this.agentProperties = agentProperties;
        this.positionSnapshotService = positionSnapshotService;
        this.currentOrderSyncService = currentOrderSyncService;
        this.tradeOrderRecordService = tradeOrderRecordService;
    }

    public Decision evaluate() {
        int maxOpenPositions = Math.max(1, agentProperties.autoTrade().maxOpenPositions());
        if (!agentProperties.autoTrade().enabled()) {
            return new Decision(false, "AUTO_TRADE_DISABLED", 0, maxOpenPositions);
        }
        try {
            Set<String> openOrPendingInstIds = new LinkedHashSet<>();
            addPositionInstIds(openOrPendingInstIds, positionSnapshotService.positions());
            addInstIds(openOrPendingInstIds, tradeOrderRecordService.activeEntryInstIds());
            if (openOrPendingInstIds.size() < maxOpenPositions) {
                return new Decision(false, "POSITION_CAPACITY_AVAILABLE",
                        openOrPendingInstIds.size(), maxOpenPositions);
            }

            OkxCurrentOrderSyncService.SyncResult orderSync = currentOrderSyncService.syncOnce();
            if (orderSync == null || orderSync.failed()) {
                String reason = orderSync == null ? "ORDER_SYNC_UNAVAILABLE" : orderSync.errorMessage();
                log.warn("position capacity scan gate unavailable reason={}", reason);
                return new Decision(false, "POSITION_CAPACITY_UNKNOWN", openOrPendingInstIds.size(), maxOpenPositions);
            }
            addInstIds(openOrPendingInstIds, orderSync.activeInstIds());

            int openOrPendingCount = openOrPendingInstIds.size();
            if (openOrPendingCount >= maxOpenPositions) {
                return new Decision(true, "POSITION_CAPACITY_FULL", openOrPendingCount, maxOpenPositions);
            }
            return new Decision(false, "POSITION_CAPACITY_AVAILABLE", openOrPendingCount, maxOpenPositions);
        } catch (RuntimeException ex) {
            log.warn("position capacity scan gate unavailable message={}", ex.getMessage());
            return new Decision(false, "POSITION_CAPACITY_UNKNOWN", 0, maxOpenPositions);
        }
    }

    private static void addPositionInstIds(Set<String> target, List<PositionSummary> positions) {
        if (positions == null) {
            return;
        }
        positions.stream()
                .filter(position -> position != null)
                .map(PositionSummary::instId)
                .filter(PositionCapacityScanGate::hasText)
                .forEach(target::add);
    }

    private static void addInstIds(Set<String> target, List<String> instIds) {
        if (instIds == null) {
            return;
        }
        instIds.stream().filter(PositionCapacityScanGate::hasText).forEach(target::add);
    }

    private static void addInstIds(Set<String> target, Set<String> instIds) {
        if (instIds == null) {
            return;
        }
        instIds.stream().filter(PositionCapacityScanGate::hasText).forEach(target::add);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Decision(boolean skipScan, String reason, int openOrPendingCount, int maxOpenPositions) {
    }
}

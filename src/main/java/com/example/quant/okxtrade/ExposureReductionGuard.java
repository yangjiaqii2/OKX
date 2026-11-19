package com.example.quant.okxtrade;

import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ExposureReductionGuard {
    private final PositionSnapshotService positionSnapshotService;
    private final TradingMode tradingMode;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.quant.account.PositionReconciliationService reconciliation;

    public ExposureReductionGuard(PositionSnapshotService positionSnapshotService, TradingProperties tradingProperties) {
        this.positionSnapshotService = positionSnapshotService;
        this.tradingMode = tradingProperties == null ? TradingMode.DISABLED : tradingProperties.mode();
    }

    public void verify(TradingOperationContext context, Map<String, String> payload) {
        if (context == null || context.operationType() == null || !context.operationType().reducesExposure()) {
            reject("OPERATION_NOT_RISK_REDUCING");
        }
        String currentOwner = AuthUserContext.currentUsername().orElse("local-admin");
        if (!hasText(context.owner()) || !currentOwner.equals(context.owner())) {
            reject("OWNER_MISMATCH");
        }
        String instId = text(payload, "instId");
        if (!hasText(instId)) {
            reject("INST_ID_REQUIRED");
        }
        if (context.operationType() == TradingOperationType.AMEND_PROTECTION) {
            // Amending an existing TP/SL algo order changes a trigger price, not
            // exposure. OKX accepts no sz/reduceOnly in this request body; what
            // must be present is a stable identity so the amend is idempotent.
            if (!hasText(text(payload, "algoId")) && !hasText(text(payload, "algoClOrdId"))) {
                reject("IDEMPOTENCY_KEY_REQUIRED");
            }
        } else if (context.operationType() != TradingOperationType.CLOSE_POSITION) {
            if (!"true".equalsIgnoreCase(text(payload, "reduceOnly"))) {
                reject("REDUCE_ONLY_REQUIRED");
            }
            String idempotencyKey = context.operationType() == TradingOperationType.PLACE_STOP_LOSS
                    || context.operationType() == TradingOperationType.PLACE_TAKE_PROFIT
                    ? text(payload, "algoClOrdId")
                    : hasText(text(payload, "clOrdId")) ? text(payload, "clOrdId") : text(payload, "algoClOrdId");
            if (!hasText(idempotencyKey)) {
                reject("IDEMPOTENCY_KEY_REQUIRED");
            }
        } else if (!hasText(text(payload, "clOrdId"))) {
            reject("IDEMPOTENCY_KEY_REQUIRED");
        }

        PositionSummary position = matchingPosition(payload, instId);
        if (!"MANAGED".equalsIgnoreCase(position.ownership()) && reconciliation != null) {
            reconciliation.reconcile();
            position = matchingPosition(payload, instId);
        }
        if (context.positionDirection() != null && !com.example.quant.account.PositionSideMatcher.matches(
                instId, context.positionDirection(), null, position)) reject("POSITION_DIRECTION_CHANGED");
        String expectedMode = tradingMode == TradingMode.DEMO ? "OKX_DEMO"
                : tradingMode == TradingMode.LIVE ? "OKX_REAL" : "DISABLED";
        if (!expectedMode.equalsIgnoreCase(position.mode())) {
            reject("TRADING_MODE_MISMATCH");
        }
        if (!"MANAGED".equalsIgnoreCase(position.ownership())) {
            reject("POSITION_OWNERSHIP_UNCONFIRMED");
        }
        String requestedMarginMode = text(payload, "tdMode");
        if (!hasText(requestedMarginMode)) {
            requestedMarginMode = text(payload, "mgnMode");
        }
        if (hasText(requestedMarginMode) && hasText(position.marginMode())
                && !requestedMarginMode.equalsIgnoreCase(position.marginMode())) {
            reject("MARGIN_MODE_MISMATCH");
        }
        if (context.operationType() == TradingOperationType.AMEND_PROTECTION && hasText(text(payload, "newSz"))) {
            BigDecimal newSize = decimal(text(payload, "newSz"));
            if (newSize.signum() <= 0 || newSize.compareTo(decimal(position.size()).abs()) > 0) {
                reject("INVALID_AMENDED_PROTECTION_SIZE");
            }
        }
        if (context.operationType() == TradingOperationType.CLOSE_POSITION
                || context.operationType() == TradingOperationType.AMEND_PROTECTION) {
            return;
        }
        BigDecimal requestedSize = decimal(text(payload, "sz")).abs();
        BigDecimal positionSize = decimal(position.size()).abs();
        if (requestedSize.signum() <= 0) {
            reject("REDUCTION_SIZE_REQUIRED");
        }
        if (requestedSize.compareTo(positionSize) > 0) {
            reject("REDUCTION_SIZE_EXCEEDS_POSITION");
        }
        if (!reductionSideMatches(text(payload, "side"), position)) {
            reject("REDUCTION_SIDE_MISMATCH");
        }
    }

    private PositionSummary matchingPosition(Map<String, String> payload, String instId) {
        List<PositionSummary> positions;
        try {
            positions = positionSnapshotService.positions();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("POSITION_SNAPSHOT_UNAVAILABLE: " + compact(ex.getMessage()), ex);
        }
        String requestedPosSide = normalizedPosSide(text(payload, "posSide"));
        return positions.stream()
                .filter(item -> instId.equalsIgnoreCase(item.instId()))
                .filter(item -> positionSideMatches(requestedPosSide, item.posSide()))
                .filter(item -> decimal(item.size()).signum() != 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("POSITION_NOT_CONFIRMED"));
    }

    private static boolean positionSideMatches(String requested, String actual) {
        String normalizedActual = normalizedPosSide(actual);
        return requested.equals(normalizedActual) || ("net".equals(requested) && "net".equals(normalizedActual));
    }

    private static boolean reductionSideMatches(String side, PositionSummary position) {
        if (!hasText(side)) {
            return false;
        }
        String posSide = normalizedPosSide(position.posSide());
        if ("long".equals(posSide)) {
            return "sell".equalsIgnoreCase(side);
        }
        if ("short".equals(posSide)) {
            return "buy".equalsIgnoreCase(side);
        }
        BigDecimal signedSize = decimal(position.size());
        return signedSize.signum() > 0 ? "sell".equalsIgnoreCase(side)
                : signedSize.signum() < 0 && "buy".equalsIgnoreCase(side);
    }

    private static String normalizedPosSide(String value) {
        return hasText(value) ? value.trim().toLowerCase() : "net";
    }

    private static BigDecimal decimal(String value) {
        try {
            return hasText(value) ? new BigDecimal(value) : BigDecimal.ZERO;
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static String text(Map<String, String> payload, String key) {
        return payload == null ? "" : payload.getOrDefault(key, "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String compact(String value) {
        return hasText(value) ? value.replaceAll("\\s+", " ").trim() : "unknown";
    }

    private static void reject(String reason) {
        throw new IllegalStateException(reason + "; OKX写请求未发送");
    }
}

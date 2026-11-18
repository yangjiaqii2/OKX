package com.example.quant.agent.execution;

import com.example.quant.agent.audit.AgentAuditLogger;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.order.PendingOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeOrderRecordService {
    private static final Logger log = LoggerFactory.getLogger(TradeOrderRecordService.class);
    private static final List<String> ACTIVE_ENTRY_STATUSES = List.of("SUBMITTED", "PROTECTION_SUBMITTED", "PARTIALLY_FILLED", "OPEN");

    private final TradeOrderRepository repository;
    private final AgentAuditLogger auditLogger;

    public TradeOrderRecordService(TradeOrderRepository repository, AgentAuditLogger auditLogger) {
        this.repository = repository;
        this.auditLogger = auditLogger;
    }

    public Set<String> activeEntryInstIds() {
        return activeEntryExposures().stream()
                .map(com.example.quant.account.dto.PositionSummary::instId)
                .filter(instId -> instId != null && !instId.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 未终结入场单（含已提交未成交）的方向敞口，供跨轮敞口累计。
     */
    public List<com.example.quant.account.dto.PositionSummary> activeEntryExposures() {
        String owner = currentUsername();
        return ACTIVE_ENTRY_STATUSES.stream().flatMap(status -> repository.findByStatus(status).stream())
                .filter(row -> owner.equals(row.getUserName()) && !row.isReduceOnly())
                .filter(row -> row.getInstId() != null && !row.getInstId().isBlank())
                .map(row -> new com.example.quant.account.dto.PositionSummary(
                        row.getInstId(),
                        normalizePosSide(row.getPosSide(), row.getSide()),
                        "1",
                        row.getPrice() == null ? BigDecimal.ZERO : row.getPrice(),
                        BigDecimal.ZERO,
                        "LOCAL_ACTIVE_ENTRY"))
                .toList();
    }

    private static String normalizePosSide(String posSide, String side) {
        if (posSide != null && !posSide.isBlank() && !"net".equalsIgnoreCase(posSide)) {
            return posSide.toLowerCase();
        }
        if (side != null && "sell".equalsIgnoreCase(side.trim())) {
            return "short";
        }
        return "long";
    }

    @Transactional
    public void recordSubmitted(PendingOrder order, OrderRecordPayload payload) {
        try {
            TradeOrderEntity entity = row(order, payload, "SUBMITTED", null);
            repository.save(entity);
            auditLogger.info(
                    "TRADE_ORDER_SUBMITTED",
                    "trade_order",
                    payload.clOrdId(),
                    "Recorded OKX order submission",
                    Map.of("instId", order.instId(), "role", payload.orderRole(), "okxId", value(payload.okxOrdId()))
            );
        } catch (RuntimeException ex) {
            log.warn("Failed to record submitted trade order instId={} clOrdId={} message={}",
                    order.instId(), payload.clOrdId(), ex.getMessage());
            auditLogger.warn("TRADE_ORDER_RECORD_FAILED", "trade_order", payload.clOrdId(), ex.getMessage(), null);
        }
    }

    @Transactional
    public void recordFailed(PendingOrder order, OrderRecordPayload payload, String errorMessage) {
        try {
            repository.save(row(order, payload, "FAILED", errorMessage));
            auditLogger.warn(
                    "TRADE_ORDER_FAILED",
                    "trade_order",
                    payload.clOrdId(),
                    errorMessage,
                    Map.of("instId", order.instId(), "role", payload.orderRole())
            );
        } catch (RuntimeException ex) {
            log.warn("Failed to record failed trade order instId={} clOrdId={} message={}",
                    order.instId(), payload.clOrdId(), ex.getMessage());
        }
    }

    private static TradeOrderEntity row(PendingOrder order, OrderRecordPayload payload,
                                        String status, String errorMessage) {
        Instant now = Instant.now();
        TradeOrderEntity entity = new TradeOrderEntity();
        entity.setUserName(currentUsername());
        entity.setTradePlanId(order.tradePlanId() == null ? null : order.tradePlanId().toString());
        entity.setPendingOrderId(order.id().toString());
        entity.setOrderRole(payload.orderRole());
        entity.setInstId(order.instId());
        entity.setSide(payload.side());
        entity.setPosSide(payload.posSide());
        entity.setOrdType(payload.ordType());
        entity.setTdMode(order.tdMode());
        entity.setSize(payload.size());
        entity.setPrice(payload.price());
        entity.setReduceOnly(payload.reduceOnly());
        entity.setClOrdId(payload.clOrdId());
        entity.setOkxOrdId(payload.okxOrdId());
        entity.setOkxState(status);
        entity.setStatus(status);
        entity.setErrorMessage(errorMessage);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    public record OrderRecordPayload(
            String orderRole,
            String side,
            String posSide,
            String ordType,
            BigDecimal size,
            BigDecimal price,
            boolean reduceOnly,
            String clOrdId,
            String okxOrdId
    ) {
        public OrderRecordPayload withOkxOrdId(String nextOkxOrdId) {
            return new OrderRecordPayload(orderRole, side, posSide, ordType, size, price, reduceOnly, clOrdId, nextOkxOrdId);
        }
    }
}

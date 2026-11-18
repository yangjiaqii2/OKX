package com.example.quant.okxtrade;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.ledger.TradeFillLedgerService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OkxOrderEventService {
    private final TradeOrderRepository repository;
    private final TradeFillLedgerService fillLedger;

    public OkxOrderEventService(TradeOrderRepository repository, TradeFillLedgerService fillLedger) {
        this.repository = repository;
        this.fillLedger = fillLedger;
    }

    @Transactional
    public boolean apply(JsonNode event) {
        if (event == null || !text(event, "instType").isBlank() && !"SWAP".equals(text(event, "instType"))) {
            return false;
        }
        Optional<TradeOrderEntity> existing = find(event);
        if (existing.isEmpty()) {
            return false;
        }
        TradeOrderEntity order = existing.get();
        Instant eventTime = time(text(event, "uTime"));
        if (eventTime == null) {
            return false;
        }
        BigDecimal filled = decimal(event, "accFillSz");
        if (!newer(order, eventTime, filled)) {
            return false;
        }
        String exchangeStatus = OkxCurrentOrderSyncService.localStatus(text(event, "state"), filled);
        if (terminal(order.getStatus()) || rank(exchangeStatus) < rank(order.getStatus())) {
            return false;
        }
        BigDecimal lastFillSize = decimal(event, "fillSz");
        boolean terminalFillsIncomplete = "FILLED".equals(exchangeStatus)
                && lastFillSize.compareTo(filled) < 0;
        String nextStatus = terminalFillsIncomplete ? "RECONCILING" : exchangeStatus;
        BigDecimal requested = decimal(event, "sz");
        order.setSize(requested);
        order.setFilledSize(filled);
        order.setRemainingSize(OkxCurrentOrderSyncService.remaining(requested, filled));
        order.setAverageFillPrice(decimal(event, "avgPx"));
        order.setOkxUpdatedAt(eventTime);
        order.setOkxState(text(event, "state"));
        order.setStatus(nextStatus);
        order.setErrorMessage(terminalFillsIncomplete ? "TERMINAL_FILLS_INCOMPLETE"
                : "RECONCILING".equals(nextStatus) ? "UNRECOGNIZED_WS_ORDER_STATE" : null);
        order.setUpdatedAt(Instant.now());
        repository.save(order);
        recordFill(order, event);
        return true;
    }

    private Optional<TradeOrderEntity> find(JsonNode event) {
        String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        String ordId = text(event, "ordId");
        if (!ordId.isBlank()) {
            Optional<TradeOrderEntity> byOrderId = repository.findFirstByUserNameAndOkxOrdId(owner, ordId);
            if (byOrderId.isPresent()) {
                return byOrderId;
            }
        }
        String clOrdId = text(event, "clOrdId");
        return clOrdId.isBlank() ? Optional.empty() : repository.findFirstByUserNameAndClOrdId(owner, clOrdId);
    }

    private void recordFill(TradeOrderEntity order, JsonNode event) {
        String tradeId = text(event, "tradeId");
        BigDecimal fillSize = decimal(event, "fillSz");
        if (tradeId.isBlank() || fillSize.signum() <= 0) {
            return;
        }
        fillLedger.record(order, new OkxCurrentOrderService.Fill(
                tradeId,
                text(event, "side"),
                text(event, "posSide"),
                decimal(event, "fillPx"),
                fillSize,
                decimal(event, "fillFee"),
                text(event, "fillFeeCcy"),
                decimal(event, "fillPnl"),
                time(text(event, "fillTime"))
        ));
    }

    private static boolean newer(TradeOrderEntity order, Instant eventTime, BigDecimal filled) {
        Instant currentTime = order.getOkxUpdatedAt();
        if (currentTime == null || eventTime == null) {
            return currentTime == null;
        }
        int timeOrder = eventTime.compareTo(currentTime);
        if (timeOrder > 0) {
            return true;
        }
        return timeOrder == 0 && filled.compareTo(nonNull(order.getFilledSize())) > 0;
    }

    private static boolean terminal(String status) {
        return "FILLED".equals(status) || "CANCELED".equals(status)
                || "PARTIALLY_FILLED_CANCELED".equals(status) || "REJECTED".equals(status);
    }

    private static int rank(String status) {
        if (terminal(status)) {
            return 3;
        }
        if ("PARTIALLY_FILLED".equals(status)) {
            return 2;
        }
        return 1;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        try {
            String value = text(node, field);
            return value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static Instant time(String epochMillis) {
        try {
            return epochMillis.isBlank() ? null : Instant.ofEpochMilli(Long.parseLong(epochMillis));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

package com.example.quant.okxtrade;

import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OkxCurrentOrderSyncService {
    private final OkxCurrentOrderService currentOrderService;
    private final TradeOrderRepository tradeOrderRepository;

    public OkxCurrentOrderSyncService(OkxCurrentOrderService currentOrderService,
                                      TradeOrderRepository tradeOrderRepository) {
        this.currentOrderService = currentOrderService;
        this.tradeOrderRepository = tradeOrderRepository;
    }

    @Transactional
    public SyncResult syncOnce() {
        if (currentOrderService == null || tradeOrderRepository == null) {
            return new SyncResult(0, 0, 0, false, null, List.of());
        }
        try {
            List<OkxCurrentOrderView> normalOrders = currentOrderService.currentOrders();
            List<OkxCurrentOrderView> algoOrders = currentOrderService.currentAlgoOrders();
            Set<String> activeInstIds = new LinkedHashSet<>();
            int upserted = 0;
            for (OkxCurrentOrderView order : normalOrders) {
                upsert(order);
                addInstId(activeInstIds, order.instId());
                upserted++;
            }
            for (OkxCurrentOrderView order : algoOrders) {
                upsert(order);
                addInstId(activeInstIds, order.instId());
                upserted++;
            }
            return new SyncResult(normalOrders.size(), algoOrders.size(), upserted, false, null,
                    new ArrayList<>(activeInstIds));
        } catch (RuntimeException ex) {
            return new SyncResult(0, 0, 0, true, compact(ex.getMessage()), List.of());
        }
    }

    private void upsert(OkxCurrentOrderView order) {
        TradeOrderEntity entity = findExisting(order).orElseGet(TradeOrderEntity::new);
        Instant now = Instant.now();
        if (!hasText(entity.getOrderRole())) {
            entity.setOrderRole(defaultText(order.role(), order.reduceOnly() ? "TAKE_PROFIT" : "ENTRY"));
        }
        entity.setInstId(defaultText(order.instId(), entity.getInstId()));
        entity.setSide(defaultText(order.side(), entity.getSide()));
        entity.setPosSide(defaultText(order.posSide(), entity.getPosSide()));
        entity.setOrdType(defaultText(order.ordType(), entity.getOrdType()));
        entity.setTdMode(defaultText(entity.getTdMode(), "cross"));
        entity.setSize(nonNull(order.size()));
        entity.setPrice(order.reduceOnly() ? nonNull(order.triggerPrice()) : nonNull(order.price()));
        entity.setReduceOnly(order.reduceOnly());
        entity.setClOrdId(defaultText(order.clOrdId(), order.algoClOrdId()));
        entity.setOkxOrdId(defaultText(order.ordId(), order.algoId()));
        entity.setOkxState(order.status());
        entity.setStatus(order.reduceOnly() ? "PROTECTION_SUBMITTED" : "SUBMITTED");
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(order.createdAt() == null ? now : order.createdAt());
        }
        entity.setUpdatedAt(now);
        tradeOrderRepository.save(entity);
    }

    private Optional<TradeOrderEntity> findExisting(OkxCurrentOrderView order) {
        String okxOrderId = defaultText(order.ordId(), order.algoId());
        if (hasText(okxOrderId)) {
            Optional<TradeOrderEntity> byOkxId = tradeOrderRepository.findFirstByOkxOrdId(okxOrderId);
            if (byOkxId.isPresent()) {
                return byOkxId;
            }
        }
        String clientOrderId = defaultText(order.clOrdId(), order.algoClOrdId());
        if (hasText(clientOrderId)) {
            return tradeOrderRepository.findFirstByClOrdId(clientOrderId);
        }
        return Optional.empty();
    }

    private static void addInstId(Set<String> activeInstIds, String instId) {
        if (hasText(instId)) {
            activeInstIds.add(instId);
        }
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String compact(String message) {
        if (!hasText(message)) {
            return "unknown";
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 240 ? compact.substring(0, 240) : compact;
    }

    public record SyncResult(
            int normalOrders,
            int algoOrders,
            int upserted,
            boolean failed,
            String errorMessage,
            List<String> activeInstIds
    ) {
    }
}

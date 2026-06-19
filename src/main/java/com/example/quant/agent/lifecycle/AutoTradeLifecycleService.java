package com.example.quant.agent.lifecycle;

import com.example.quant.account.ClosePositionRecordEntity;
import com.example.quant.account.ClosePositionRecordRepository;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.budget.AutoTradeBudgetService;
import com.example.quant.agent.budget.BudgetReservation;
import com.example.quant.agent.event.TradeEventPayload;
import com.example.quant.agent.event.TradeEventService;
import com.example.quant.agent.event.TradeEventType;
import com.example.quant.agent.event.TradeEventView;
import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxInstrumentRulesProvider;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxPositionMode;
import com.example.quant.okxtrade.OkxPositionModeProvider;
import com.example.quant.order.OrderStatus;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AutoTradeLifecycleService {
    private static final List<String> ACTIVE_PROTECTION_STATUSES = List.of(
            "SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE"
    );

    private final PendingOrderService pendingOrderService;
    private final AutoTradeBudgetService budgetService;
    private final AgentProperties agentProperties;
    private final OkxOrderGateway okxOrderGateway;
    private final PositionSnapshotService positionSnapshotService;
    private final OkxInstrumentRulesProvider instrumentRulesProvider;
    private final OkxPositionModeProvider positionModeProvider;
    private final ClosePositionRecordRepository closePositionRecordRepository;
    private final TradeOrderRepository tradeOrderRepository;
    private final TradeEventService tradeEventService;

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                OkxInstrumentRules::defaultFor, null, null, null, null);
    }

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     ClosePositionRecordRepository closePositionRecordRepository) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                OkxInstrumentRules::defaultFor, closePositionRecordRepository, null, null, null);
    }

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     ClosePositionRecordRepository closePositionRecordRepository,
                                     TradeOrderRepository tradeOrderRepository) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                OkxInstrumentRules::defaultFor, closePositionRecordRepository, tradeOrderRepository, null, null);
    }

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     OkxInstrumentRulesProvider instrumentRulesProvider,
                                     ClosePositionRecordRepository closePositionRecordRepository,
                                     TradeOrderRepository tradeOrderRepository) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                instrumentRulesProvider, closePositionRecordRepository, tradeOrderRepository, null, null);
    }

    @Autowired
    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     OkxInstrumentRulesProvider instrumentRulesProvider,
                                     ClosePositionRecordRepository closePositionRecordRepository,
                                     TradeOrderRepository tradeOrderRepository,
                                     TradeEventService tradeEventService) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                instrumentRulesProvider, closePositionRecordRepository, tradeOrderRepository, null, tradeEventService);
    }

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     OkxInstrumentRulesProvider instrumentRulesProvider,
                                     ClosePositionRecordRepository closePositionRecordRepository,
                                     TradeOrderRepository tradeOrderRepository,
                                     OkxPositionModeProvider positionModeProvider) {
        this(pendingOrderService, budgetService, agentProperties, okxOrderGateway, positionSnapshotService,
                instrumentRulesProvider, closePositionRecordRepository, tradeOrderRepository, positionModeProvider, null);
    }

    public AutoTradeLifecycleService(PendingOrderService pendingOrderService, AutoTradeBudgetService budgetService,
                                     AgentProperties agentProperties, OkxOrderGateway okxOrderGateway,
                                     PositionSnapshotService positionSnapshotService,
                                     OkxInstrumentRulesProvider instrumentRulesProvider,
                                     ClosePositionRecordRepository closePositionRecordRepository,
                                     TradeOrderRepository tradeOrderRepository,
                                     OkxPositionModeProvider positionModeProvider,
                                     TradeEventService tradeEventService) {
        this.pendingOrderService = pendingOrderService;
        this.budgetService = budgetService;
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
        this.okxOrderGateway = okxOrderGateway;
        this.positionSnapshotService = positionSnapshotService;
        this.instrumentRulesProvider = instrumentRulesProvider == null ? OkxInstrumentRules::defaultFor : instrumentRulesProvider;
        this.positionModeProvider = positionModeProvider == null ? () -> OkxPositionMode.LONG_SHORT : positionModeProvider;
        this.closePositionRecordRepository = closePositionRecordRepository;
        this.tradeOrderRepository = tradeOrderRepository;
        this.tradeEventService = tradeEventService;
    }

    public LifecycleRunResult runOnce() {
        return runOnce(Instant.now());
    }

    public LifecycleRunResult runOnce(Instant now) {
        int entryTimeoutCancelled = 0;
        int entryFilledProtected = 0;
        int partialFillProtected = 0;
        int sidewaysTpAdjusted = 0;
        int maxHoldCloseSubmitted = 0;
        PositionSyncResult positionSync = positions();
        if (!positionSync.available()) {
            List<PendingOrder> affectedOrders = pendingOrderService.allOrders().stream()
                    .filter(order -> order.submittedAt() != null)
                    .toList();
            for (PendingOrder order : affectedOrders) {
                recordEvent(order, TradeEventType.RECOVERY_FAILED, order.status().name(), order.status().name(),
                        "POSITION_SYNC_UNAVAILABLE", "持仓查询失败，跳过本轮生命周期动作", null, order.clientOrderId(), null);
            }
            int affected = affectedOrders.size();
            return new LifecycleRunResult(0, 0, 0, 0, 0, affected);
        }
        List<PositionSummary> positions = positionSync.positions();
        for (PendingOrder order : pendingOrderService.allOrders()) {
            if (order.submittedAt() == null) {
                continue;
            }
            PositionSummary position = matchingPosition(order, positions);
            Duration age = Duration.between(order.submittedAt(), now);
            if (position == null && order.status() == OrderStatus.SUBMITTED) {
                EntryFill fill = queryEntryFill(order);
                if (fill.complete()) {
                    try {
                        submitProtection(order, fill.avgPx(), fill.filledSize(), "FULL_FILL");
                        if (order.budgetReservationId() != null) {
                            budgetService.markUsed(order.budgetReservationId());
                        }
                        order.markProtectionSubmitted("ENTRY_FILLED_PROTECTION_SUBMITTED");
                        recordEvent(order, TradeEventType.ENTRY_FILLED, OrderStatus.SUBMITTED.name(),
                                OrderStatus.PROTECTION_SUBMITTED.name(), "ENTRY_FULLY_FILLED",
                                "入场委托完全成交，按实际成交数量提交保护单", order.externalOrderId(), order.clientOrderId(), null);
                        recordEvent(order, TradeEventType.PROTECTION_SUBMITTED, OrderStatus.SUBMITTED.name(),
                                OrderStatus.PROTECTION_SUBMITTED.name(), "FULL_FILL_PROTECTION_SUBMITTED",
                                "完全成交后提交保护单", order.externalOrderId(), order.clientOrderId(), null);
                        entryFilledProtected++;
                    } catch (RuntimeException ex) {
                        order.markProtectionFailed("PROTECTION_FAILED_AFTER_FULL_FILL: " + compact(ex.getMessage()));
                        recordEvent(order, TradeEventType.PROTECTION_FAILED, OrderStatus.SUBMITTED.name(),
                                OrderStatus.PROTECTION_FAILED.name(), "PROTECTION_FAILED_AFTER_FULL_FILL",
                                ex.getMessage(), order.externalOrderId(), order.clientOrderId(), null);
                    }
                    continue;
                }
            }
            if (position != null && shouldMaxHoldClose(order, age)) {
                maxHoldCloseSubmitted += submitMaxHoldClose(order, position, now);
                continue;
            }
            if (position != null && shouldAdjustSideways(order, position, age)) {
                sidewaysTpAdjusted += tightenTakeProfit(order, position);
                continue;
            }
            if (position == null && shouldCancelStaleEntry(order, age)) {
                EntryFill fill = queryEntryFill(order);
                cancelEntryOrder(order);
                if (fill.filledSize().signum() <= 0) {
                    order.markEntryTimeoutCancelled("ENTRY_TIMEOUT_CANCELLED");
                    if (order.budgetReservationId() != null) {
                        budgetService.release(order.budgetReservationId(), "ENTRY_TIMEOUT_CANCELLED");
                    }
                    recordEvent(order, TradeEventType.ENTRY_TIMEOUT_CANCELLED, OrderStatus.SUBMITTED.name(),
                            OrderStatus.ENTRY_TIMEOUT_CANCELLED.name(), "ENTRY_TIMEOUT_CANCELLED",
                            "入场委托超时且无成交，已撤单", order.externalOrderId(), order.clientOrderId(), null);
                    recordEvent(order, TradeEventType.BUDGET_RELEASED, "RESERVED", "RELEASED",
                            "ENTRY_TIMEOUT_CANCELLED", "入场无成交超时释放预算", null, order.clientOrderId(), null);
                    entryTimeoutCancelled++;
                } else {
                    try {
                        submitProtection(order, fill.avgPx(), fill.filledSize(), "PARTIAL_FILL_ENTRY_TIMEOUT");
                        if (order.budgetReservationId() != null) {
                            budgetService.markUsed(order.budgetReservationId());
                        }
                        order.markProtectionSubmitted("PARTIAL_FILL_PROTECTION_SUBMITTED");
                        recordEvent(order, TradeEventType.ENTRY_PARTIAL_FILLED, OrderStatus.SUBMITTED.name(),
                                OrderStatus.PROTECTION_SUBMITTED.name(), "PARTIAL_FILL_ENTRY_TIMEOUT",
                                "部分成交超时后取消剩余委托，并按实际成交数量提交保护单", order.externalOrderId(),
                                order.clientOrderId(), null);
                        recordEvent(order, TradeEventType.PROTECTION_SUBMITTED, OrderStatus.SUBMITTED.name(),
                                OrderStatus.PROTECTION_SUBMITTED.name(), "PARTIAL_FILL_PROTECTION_SUBMITTED",
                                "部分成交后提交保护单", order.externalOrderId(), order.clientOrderId(), null);
                        partialFillProtected++;
                    } catch (RuntimeException ex) {
                        order.markProtectionFailed("PROTECTION_FAILED_AFTER_PARTIAL_FILL: " + compact(ex.getMessage()));
                        recordEvent(order, TradeEventType.PROTECTION_FAILED, OrderStatus.SUBMITTED.name(),
                                OrderStatus.PROTECTION_FAILED.name(), "PROTECTION_FAILED_AFTER_PARTIAL_FILL",
                                ex.getMessage(), order.externalOrderId(), order.clientOrderId(), null);
                    }
                }
            }
        }
        return new LifecycleRunResult(entryTimeoutCancelled, entryFilledProtected, partialFillProtected,
                sidewaysTpAdjusted, maxHoldCloseSubmitted, 0);
    }

    public List<AutoTradeLifecycleSnapshot> snapshots() {
        PositionSyncResult positionSync = positions();
        List<PositionSummary> positions = positionSync.positions();
        Instant now = Instant.now();
        return pendingOrderService.allOrders().stream()
                .filter(order -> order.budgetReservationId() != null || order.clientOrderId() != null)
                .map(order -> {
                    PositionSummary position = positionSync.available() ? matchingPosition(order, positions) : null;
                    Instant entryTime = order.submittedAt() == null ? order.createdAt() : order.submittedAt();
                    BudgetReservation reservation = order.budgetReservationId() == null
                            ? null
                            : budgetService.reservation(order.budgetReservationId()).orElse(null);
                    BigDecimal budgetUsed = reservation == null ? order.marginAmount() : reservation.amount();
                    EntryOrderFact entryFact = entryFact(order, position);
                    List<ProtectionOrderFact> protectionFacts = protectionFacts(order);
                    ClosePositionFact closeFact = closeFact(order);
                    BudgetFact budgetFact = budgetFact(order, reservation);
                    List<TradeEventView> recentEvents = recentEvents(order);
                    PositionLifecycleFact positionFact = positionFact(positionSync, position);
                    return new AutoTradeLifecycleSnapshot(
                            order.instId(),
                            order.posSide(),
                            entryTime,
                            entryTime == null ? Duration.ZERO : Duration.between(entryTime, now),
                            order.price(),
                            position == null ? BigDecimal.ZERO : position.avgPrice(),
                            position == null ? BigDecimal.ZERO : position.unrealizedPnl(),
                            position == null ? BigDecimal.ZERO : pnlPct(position),
                            budgetUsed,
                            order.status().name(),
                            protectionStatus(order),
                            positionSync.available() ? lifecycleStatus(order, position) : "POSITION_SYNC_UNAVAILABLE",
                            positionSync.available() ? nextAction(order) : "WAIT_POSITION_SYNC_RECOVERY",
                            entryFact,
                            protectionFacts,
                            positionFact,
                            closeFact,
                            budgetFact,
                            recentEvents,
                            manualAttentionRequired(order, protectionFacts, closeFact)
                    );
                })
                .toList();
    }

    private EntryOrderFact entryFact(PendingOrder order, PositionSummary position) {
        return new EntryOrderFact(
                order.status().name(),
                order.externalOrderId(),
                order.clientOrderId(),
                order.size(),
                position == null ? BigDecimal.ZERO : decimal(position.size()),
                position == null ? BigDecimal.ZERO : position.avgPrice(),
                order.submittedAt()
        );
    }

    private List<ProtectionOrderFact> protectionFacts(PendingOrder order) {
        if (tradeOrderRepository == null || order == null) {
            return List.of();
        }
        return tradeOrderRepository.findByPendingOrderIdOrderByCreatedAtAsc(order.id().toString())
                .stream()
                .filter(TradeOrderEntity::isReduceOnly)
                .map(item -> new ProtectionOrderFact(
                        item.getId(),
                        item.getOrderRole(),
                        item.getStatus(),
                        item.getOkxState(),
                        item.getOkxOrdId(),
                        item.getClOrdId(),
                        item.getSize(),
                        item.getPrice(),
                        item.getCreatedAt(),
                        item.getErrorMessage()
                ))
                .toList();
    }

    private ClosePositionFact closeFact(PendingOrder order) {
        if (closePositionRecordRepository == null || order == null) {
            return null;
        }
        List<ClosePositionRecordEntity> records = closePositionRecordRepository
                .findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(currentUsername(), order.id().toString());
        if (records == null || records.isEmpty()) {
            return null;
        }
        ClosePositionRecordEntity record = records.get(0);
        return new ClosePositionFact(
                record.getId(),
                record.getStatus(),
                record.getSource(),
                record.getCloseOrderId(),
                record.getCloseClOrdId(),
                record.getRealizedPnl(),
                record.getFee(),
                record.getFundingFee(),
                record.getUpdatedAt(),
                record.getErrorMessage()
        );
    }

    private BudgetFact budgetFact(PendingOrder order, BudgetReservation reservation) {
        if (reservation == null) {
            return order.budgetReservationId() == null
                    ? null
                    : new BudgetFact(order.budgetReservationId().toString(), "UNKNOWN", order.marginAmount(), null, null);
        }
        return new BudgetFact(
                reservation.reservationId().toString(),
                reservation.status().name(),
                reservation.amount(),
                reservation.reason(),
                reservation.updatedAt()
        );
    }

    private List<TradeEventView> recentEvents(PendingOrder order) {
        if (tradeEventService == null || order == null) {
            return List.of();
        }
        try {
            return tradeEventService.recentForPendingOrder(order.id().toString(), 20);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static PositionLifecycleFact positionFact(PositionSyncResult positionSync, PositionSummary position) {
        if (!positionSync.available()) {
            return new PositionLifecycleFact(false, false, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, "POSITION_SYNC_UNAVAILABLE");
        }
        return new PositionLifecycleFact(
                true,
                position != null,
                position == null ? BigDecimal.ZERO : decimal(position.size()),
                position == null ? BigDecimal.ZERO : position.avgPrice(),
                position == null ? BigDecimal.ZERO : position.unrealizedPnl(),
                position == null ? BigDecimal.ZERO : pnlPct(position),
                position == null ? "NO_POSITION" : position.mode()
        );
    }

    private static boolean manualAttentionRequired(PendingOrder order, List<ProtectionOrderFact> protections,
                                                   ClosePositionFact closeFact) {
        if (order.status() == OrderStatus.EMERGENCY_ATTENTION_REQUIRED
                || order.status() == OrderStatus.PROTECTION_FAILED) {
            return true;
        }
        boolean protectionAttention = protections != null && protections.stream()
                .anyMatch(item -> "EMERGENCY_ATTENTION_REQUIRED".equalsIgnoreCase(item.status())
                        || "PROTECTION_CANCEL_FAILED".equalsIgnoreCase(item.status()));
        boolean closeAttention = closeFact != null && hasText(closeFact.errorMessage())
                && closeFact.errorMessage().startsWith("POSITION_QUERY_FAILED");
        return protectionAttention || closeAttention;
    }

    private boolean shouldCancelStaleEntry(PendingOrder order, Duration age) {
        return order.status() == OrderStatus.SUBMITTED
                && age.toMinutes() >= Math.max(1, agentProperties.lifecycle().entryTimeoutMinutes());
    }

    private boolean shouldAdjustSideways(PendingOrder order, PositionSummary position, Duration age) {
        if (order.status() == OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED
                || order.status() == OrderStatus.CLOSE_SUBMITTED
                || order.status() == OrderStatus.CLOSED) {
            return false;
        }
        if (age.toHours() < Math.max(1, agentProperties.lifecycle().sidewaysPositionHours())) {
            return false;
        }
        BigDecimal pnlPct = pnlPct(position);
        return pnlPct.abs().compareTo(agentProperties.lifecycle().sidewaysPnlRangePct()) <= 0;
    }

    private boolean shouldMaxHoldClose(PendingOrder order, Duration age) {
        if (order.status() == OrderStatus.CLOSE_SUBMITTED || order.status() == OrderStatus.CLOSED) {
            return false;
        }
        return age.toHours() >= Math.max(1, agentProperties.lifecycle().maxHoldHours());
    }

    private int tightenTakeProfit(PendingOrder order, PositionSummary position) {
        BigDecimal avgPx = positive(position.avgPrice(), order.price());
        BigDecimal size = decimal(position.size());
        BigDecimal triggerPx = smallProfitTakeProfit(order.posSide(), avgPx);
        if (!replaceExistingTakeProfits(order)) {
            order.markEmergencyAttentionRequired("SIDEWAYS_TP_REPLACE_FAILED");
            return 0;
        }
        Map<String, String> payload = algoPayload(order, size, "SIDEWAYS_TP");
        payload.put("tpTriggerPx", value(triggerPx));
        payload.put("tpOrdPx", "-1");
        JsonNode response = okxOrderGateway.placeAlgoOrder(payload);
        recordSidewaysTakeProfit(order, payload, size, triggerPx, response);
        order.markSidewaysTimeoutTpAdjusted("SIDEWAYS_TIMEOUT_TP_ADJUSTED");
        recordEvent(order, TradeEventType.SIDEWAYS_TP_REPLACED, OrderStatus.PROTECTION_SUBMITTED.name(),
                OrderStatus.SIDEWAYS_TIMEOUT_TP_ADJUSTED.name(), "SIDEWAYS_TP_REPLACED",
                "横盘超时后替换为小盈利止盈保护单", null, payload.get("algoClOrdId"), algoId(response));
        return 1;
    }

    private boolean replaceExistingTakeProfits(PendingOrder order) {
        if (tradeOrderRepository == null) {
            return true;
        }
        List<TradeOrderEntity> takeProfits = tradeOrderRepository
                .findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(order.id().toString(), ACTIVE_PROTECTION_STATUSES)
                .stream()
                .filter(AutoTradeLifecycleService::isTakeProfitRole)
                .toList();
        boolean allCancelled = true;
        Instant now = Instant.now();
        for (TradeOrderEntity takeProfit : takeProfits) {
            if (cancelProtection(takeProfit)) {
                takeProfit.setStatus("INVALID");
                takeProfit.setOkxState("CANCELLED");
                takeProfit.setErrorMessage("SIDEWAYS_TP_REPLACED");
            } else {
                allCancelled = false;
                takeProfit.setStatus("PROTECTION_CANCEL_FAILED");
                takeProfit.setErrorMessage("PROTECTION_CANCEL_FAILED: cancelRetry=1; "
                        + compact(takeProfit.getErrorMessage()));
            }
            takeProfit.setUpdatedAt(now);
        }
        if (!takeProfits.isEmpty()) {
            tradeOrderRepository.saveAll(takeProfits);
        }
        return allCancelled;
    }

    private boolean cancelProtection(TradeOrderEntity protection) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", protection.getInstId());
        if (hasText(protection.getOkxOrdId())) {
            payload.put("algoId", protection.getOkxOrdId());
        } else if (hasText(protection.getClOrdId())) {
            payload.put("algoClOrdId", protection.getClOrdId());
        }
        if (payload.size() <= 1) {
            return true;
        }
        try {
            okxOrderGateway.cancelAlgoOrder(payload);
            return true;
        } catch (RuntimeException ex) {
            protection.setOkxState("CANCEL_FAILED");
            protection.setErrorMessage("PROTECTION_CANCEL_FAILED: " + compact(ex.getMessage()));
            return false;
        }
    }

    private void recordSidewaysTakeProfit(PendingOrder order, Map<String, String> payload,
                                          BigDecimal size, BigDecimal triggerPx, JsonNode response) {
        if (tradeOrderRepository == null) {
            return;
        }
        Instant now = Instant.now();
        TradeOrderEntity entity = new TradeOrderEntity();
        entity.setTradePlanId(order.tradePlanId() == null ? null : order.tradePlanId().toString());
        entity.setPendingOrderId(order.id().toString());
        entity.setOrderRole("SIDEWAYS_TP");
        entity.setInstId(order.instId());
        entity.setSide(payload.get("side"));
        entity.setPosSide(order.posSide());
        entity.setOrdType(payload.get("ordType"));
        entity.setTdMode(order.tdMode());
        entity.setSize(size);
        entity.setPrice(triggerPx);
        entity.setReduceOnly(true);
        entity.setClOrdId(payload.get("algoClOrdId"));
        entity.setOkxOrdId(algoId(response));
        entity.setOkxState("live");
        entity.setStatus("PROTECTION_SUBMITTED");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        tradeOrderRepository.save(entity);
    }

    private int submitMaxHoldClose(PendingOrder order, PositionSummary position, Instant now) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("mgnMode", order.tdMode());
        payload.put("autoCxl", "true");
        if (hasText(order.posSide()) && !"net".equalsIgnoreCase(order.posSide())) {
            payload.put("posSide", order.posSide());
        }
        try {
            JsonNode response = okxOrderGateway.closePosition(payload);
            String closeOrderId = closeId(response);
            order.markCloseSubmitted(closeOrderId, "MAX_HOLD_TIMEOUT_CLOSE_SUBMITTED");
            recordMaxHoldCloseSubmitted(order, position, closeOrderId, now);
            recordEvent(order, TradeEventType.MAX_HOLD_CLOSE_SUBMITTED, OrderStatus.PROTECTION_SUBMITTED.name(),
                    OrderStatus.CLOSE_SUBMITTED.name(), "MAX_HOLD_TIMEOUT_CLOSE_SUBMITTED",
                    "达到最大持仓时间后提交平仓", closeOrderId, order.clientOrderId(), null);
            return 1;
        } catch (RuntimeException ex) {
            order.markEmergencyAttentionRequired("MAX_HOLD_TIMEOUT_CLOSE_FAILED: " + ex.getMessage());
            recordEvent(order, TradeEventType.RECOVERY_FAILED, order.status().name(),
                    OrderStatus.EMERGENCY_ATTENTION_REQUIRED.name(), "MAX_HOLD_TIMEOUT_CLOSE_FAILED",
                    ex.getMessage(), null, order.clientOrderId(), null);
            return 0;
        }
    }

    private void recordMaxHoldCloseSubmitted(PendingOrder order, PositionSummary position, String closeOrderId, Instant now) {
        if (closePositionRecordRepository == null) {
            return;
        }
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName(AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER));
        record.setInstId(order.instId());
        record.setPosSide(order.posSide());
        record.setMarginMode(order.tdMode());
        record.setCloseOrderId(closeOrderId);
        record.setCloseClOrdId("MAXC" + shortHash(order.id().toString(), 24));
        record.setSize(decimal(position.size()));
        record.setAvgPx(position.avgPrice());
        record.setStatus("CLOSE_SUBMITTED");
        record.setSource("MAX_HOLD_TIMEOUT");
        record.setPendingOrderId(order.id().toString());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        closePositionRecordRepository.save(record);
    }

    private void submitProtection(PendingOrder order, BigDecimal avgPx, BigDecimal filledSize, String suffix) {
        OkxInstrumentRules rules = instrumentRules(order.instId());
        BigDecimal normalizedSize = rules.floorToLot(filledSize);
        for (Map<String, String> payload : protectionPayloads(order, rules, normalizedSize, avgPx, suffix)) {
            okxOrderGateway.placeAlgoOrder(payload);
        }
    }

    private OkxInstrumentRules instrumentRules(String instId) {
        try {
            OkxInstrumentRules rules = instrumentRulesProvider.rules(instId);
            return rules == null ? OkxInstrumentRules.defaultFor(instId) : rules;
        } catch (RuntimeException ex) {
            return OkxInstrumentRules.defaultFor(instId);
        }
    }

    private List<Map<String, String>> protectionPayloads(PendingOrder order, OkxInstrumentRules rules,
                                                         BigDecimal filledSize, BigDecimal avgPx, String suffix) {
        if (filledSize == null || filledSize.signum() <= 0) {
            return List.of();
        }
        List<Map<String, String>> payloads = new ArrayList<>();
        Map<String, String> stopLoss = algoPayload(order, filledSize, suffix + "_SL");
        stopLoss.put("slTriggerPx", value(rules.normalizePrice(order.stopLossPrice())));
        stopLoss.put("slOrdPx", "-1");
        payloads.add(stopLoss);

        BigDecimal risk = positive(avgPx, order.price()).subtract(order.stopLossPrice()).abs();
        for (TakeProfitLeg leg : takeProfitLegs(filledSize, rules)) {
            BigDecimal triggerPx = takeProfitPrice(order, positive(avgPx, order.price()), risk, leg.rMultiple());
            Map<String, String> takeProfit = algoPayload(order, leg.size(), suffix + "_" + leg.suffix());
            takeProfit.put("tpTriggerPx", value(rules.normalizePrice(triggerPx)));
            takeProfit.put("tpOrdPx", "-1");
            payloads.add(takeProfit);
        }
        return payloads;
    }

    private List<TakeProfitLeg> takeProfitLegs(BigDecimal filledSize, OkxInstrumentRules rules) {
        BigDecimal tp1 = rules.floorToLot(filledSize.multiply(ratio(agentProperties.takeProfit().tp1Ratio())));
        BigDecimal tp2 = rules.floorToLot(filledSize.multiply(ratio(agentProperties.takeProfit().tp2Ratio())));
        if (tp1.compareTo(rules.minSize()) < 0) {
            tp1 = BigDecimal.ZERO;
        }
        if (tp2.compareTo(rules.minSize()) < 0) {
            tp2 = BigDecimal.ZERO;
        }
        BigDecimal tp3 = agentProperties.takeProfit().finalTpUseRemainingSize()
                ? rules.floorToLot(filledSize.subtract(tp1).subtract(tp2))
                : rules.floorToLot(filledSize.multiply(ratio(agentProperties.takeProfit().tp3Ratio())));
        if (tp3.compareTo(rules.minSize()) < 0) {
            if (tp2.compareTo(rules.minSize()) >= 0) {
                tp2 = tp2.add(tp3);
            } else if (tp1.compareTo(rules.minSize()) >= 0) {
                tp1 = tp1.add(tp3);
            } else {
                tp3 = filledSize;
            }
            if (!filledSize.equals(tp3)) {
                tp3 = BigDecimal.ZERO;
            }
        }
        List<TakeProfitLeg> legs = new ArrayList<>();
        if (tp1.compareTo(rules.minSize()) >= 0) {
            legs.add(new TakeProfitLeg("TP1", tp1, BigDecimal.ONE));
        }
        if (tp2.compareTo(rules.minSize()) >= 0) {
            legs.add(new TakeProfitLeg("TP2", tp2, BigDecimal.valueOf(2)));
        }
        if (tp3.compareTo(rules.minSize()) >= 0) {
            legs.add(new TakeProfitLeg("TP3", tp3, BigDecimal.valueOf(3)));
        }
        return legs;
    }

    private static BigDecimal takeProfitPrice(PendingOrder order, BigDecimal avgPx, BigDecimal risk, BigDecimal multiple) {
        BigDecimal distance = risk.multiply(multiple);
        return "short".equalsIgnoreCase(order.posSide()) ? avgPx.subtract(distance) : avgPx.add(distance);
    }

    private static BigDecimal ratio(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Map<String, String> algoPayload(PendingOrder order, BigDecimal size, String suffix) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        payload.put("tdMode", order.tdMode());
        payload.put("side", closeSide(order));
        if (positionMode() == OkxPositionMode.LONG_SHORT) {
            payload.put("posSide", order.posSide());
        }
        payload.put("ordType", "conditional");
        payload.put("reduceOnly", "true");
        payload.put("sz", value(size));
        payload.put("algoClOrdId", lifecycleAlgoClOrdId(order, suffix));
        return payload;
    }

    private EntryFill queryEntryFill(PendingOrder order) {
        if (okxOrderGateway == null) {
            return new EntryFill(BigDecimal.ZERO, BigDecimal.ZERO, "");
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        if (hasText(order.externalOrderId())) {
            payload.put("ordId", order.externalOrderId());
        } else if (hasText(order.clientOrderId())) {
            payload.put("clOrdId", order.clientOrderId());
        }
        JsonNode item = firstDataItem(okxOrderGateway.queryOrder(payload));
        if (item == null) {
            return new EntryFill(BigDecimal.ZERO, BigDecimal.ZERO, "");
        }
        return new EntryFill(decimal(item, "avgPx"), decimal(item, "accFillSz"), item.path("state").asText(""));
    }

    private void cancelEntryOrder(PendingOrder order) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", order.instId());
        if (hasText(order.externalOrderId())) {
            payload.put("ordId", order.externalOrderId());
        } else if (hasText(order.clientOrderId())) {
            payload.put("clOrdId", order.clientOrderId());
        }
        okxOrderGateway.cancelOrder(payload);
    }

    private PositionSummary matchingPosition(PendingOrder order, List<PositionSummary> positions) {
        return positions.stream()
                .filter(position -> order.instId().equalsIgnoreCase(position.instId()))
                .filter(position -> !hasText(order.posSide()) || order.posSide().equalsIgnoreCase(position.posSide()))
                .filter(position -> decimal(position.size()).signum() > 0)
                .findFirst()
                .orElse(null);
    }

    private static String protectionStatus(PendingOrder order) {
        return switch (order.status()) {
            case PROTECTION_SUBMITTED, ACTIVE, ACTIVE_WITH_TP_WARNING, SIDEWAYS_TIMEOUT_TP_ADJUSTED -> "PROTECTION_SUBMITTED";
            case PROTECTION_FAILED, EMERGENCY_ATTENTION_REQUIRED -> "PROTECTION_FAILED";
            default -> "PROTECTION_PENDING";
        };
    }

    private static String lifecycleStatus(PendingOrder order, PositionSummary position) {
        if (order.status() == OrderStatus.SUBMITTED && position == null) {
            return "ENTRY_PENDING";
        }
        if (order.status() == OrderStatus.SUBMITTED && position != null) {
            return "NORMAL";
        }
        return order.status().name();
    }

    private static String nextAction(PendingOrder order) {
        return switch (order.status()) {
            case SUBMITTED -> "WAIT_ENTRY_FILL_OR_TIMEOUT";
            case ENTRY_TIMEOUT_CANCELLED -> "FALLBACK_NEXT_CANDIDATE_ALLOWED";
            case SIDEWAYS_TIMEOUT_TP_ADJUSTED -> "WAIT_SMALL_PROFIT_TP_OR_MAX_HOLD";
            case CLOSE_SUBMITTED -> "WAIT_CLOSE_SYNC";
            case EMERGENCY_ATTENTION_REQUIRED -> "MANUAL_ATTENTION_REQUIRED";
            default -> "MONITOR";
        };
    }

    private PositionSyncResult positions() {
        if (positionSnapshotService == null) {
            return PositionSyncResult.available(List.of());
        }
        try {
            return PositionSyncResult.available(positionSnapshotService.positions());
        } catch (RuntimeException ex) {
            return PositionSyncResult.unavailable();
        }
    }

    private OkxPositionMode positionMode() {
        try {
            OkxPositionMode mode = positionModeProvider.positionMode();
            return mode == null ? OkxPositionMode.NET : mode;
        } catch (RuntimeException ex) {
            return OkxPositionMode.NET;
        }
    }

    private void recordEvent(PendingOrder order, TradeEventType eventType, String oldStatus, String newStatus,
                             String reasonCode, String reasonMessage, String okxOrdId, String clOrdId, String algoId) {
        if (tradeEventService == null || order == null) {
            return;
        }
        try {
            tradeEventService.record(new TradeEventPayload(
                    null,
                    order.instId(),
                    order.id().toString(),
                    null,
                    null,
                    eventType,
                    oldStatus,
                    newStatus,
                    reasonCode,
                    reasonMessage,
                    okxOrdId,
                    clOrdId,
                    algoId
            ));
        } catch (RuntimeException ex) {
            // Event persistence must not interrupt recovery/lifecycle handling.
        }
    }

    private BigDecimal smallProfitTakeProfit(String posSide, BigDecimal avgPx) {
        BigDecimal profitRate = agentProperties.lifecycle().sidewaysExitProfitPct()
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal factor = "short".equalsIgnoreCase(posSide)
                ? BigDecimal.ONE.subtract(profitRate)
                : BigDecimal.ONE.add(profitRate);
        return avgPx.multiply(factor).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static BigDecimal pnlPct(PositionSummary position) {
        BigDecimal notional = position.notionalUsd() == null ? BigDecimal.ZERO : position.notionalUsd().abs();
        if (notional.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal pnl = position.unrealizedPnl() == null ? BigDecimal.ZERO : position.unrealizedPnl();
        return pnl.multiply(BigDecimal.valueOf(100)).divide(notional, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal positive(BigDecimal preferred, BigDecimal fallback) {
        return preferred != null && preferred.signum() > 0 ? preferred : fallback;
    }

    private static String closeSide(PendingOrder order) {
        return "buy".equalsIgnoreCase(order.side()) ? "sell" : "buy";
    }

    private static String lifecycleAlgoClOrdId(PendingOrder order, String suffix) {
        String cleanSuffix = suffix == null ? "" : suffix.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        String typeCode = lifecycleTypeCode(cleanSuffix);
        return "lc" + shortHash(order.id().toString(), 12) + typeCode + shortHash(cleanSuffix, 8);
    }

    private static String lifecycleTypeCode(String cleanSuffix) {
        if (cleanSuffix.contains("SIDEWAYS")) {
            return "SWT";
        }
        if (cleanSuffix.contains("TP1")) {
            return "TP1";
        }
        if (cleanSuffix.contains("TP2")) {
            return "TP2";
        }
        if (cleanSuffix.contains("TP3")) {
            return "TP3";
        }
        if (cleanSuffix.contains("SL") || cleanSuffix.contains("STOP")) {
            return "SL";
        }
        return "LC";
    }

    private static String shortHash(String value, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(Character.forDigit((item >> 4) & 0xf, 16));
                builder.append(Character.forDigit(item & 0xf, 16));
            }
            return builder.substring(0, Math.min(length, builder.length()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static JsonNode firstDataItem(JsonNode response) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return null;
        }
        return data.get(0);
    }

    private static String closeId(JsonNode response) {
        JsonNode item = firstDataItem(response);
        return item == null ? null : item.path("ordId").asText("");
    }

    private static String algoId(JsonNode response) {
        JsonNode item = firstDataItem(response);
        return item == null ? null : item.path("algoId").asText("");
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null) {
            return BigDecimal.ZERO;
        }
        return decimal(node.path(field).asText(""));
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

    private static String value(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    private static boolean isTakeProfitRole(TradeOrderEntity entity) {
        String role = entity.getOrderRole();
        return "TP1".equalsIgnoreCase(role) || "TP2".equalsIgnoreCase(role) || "TP3".equalsIgnoreCase(role);
    }

    private static String compact(String value) {
        if (!hasText(value)) {
            return "unknown";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private record EntryFill(BigDecimal avgPx, BigDecimal filledSize, String state) {
        boolean complete() {
            return filledSize != null && filledSize.signum() > 0 && "filled".equalsIgnoreCase(state);
        }
    }

    private record TakeProfitLeg(String suffix, BigDecimal size, BigDecimal rMultiple) {
    }

    private record PositionSyncResult(boolean available, List<PositionSummary> positions) {
        static PositionSyncResult available(List<PositionSummary> positions) {
            return new PositionSyncResult(true, positions == null ? List.of() : positions);
        }

        static PositionSyncResult unavailable() {
            return new PositionSyncResult(false, List.of());
        }
    }

    public record LifecycleRunResult(
            int entryTimeoutCancelled,
            int entryFilledProtected,
            int partialFillProtected,
            int sidewaysTpAdjusted,
            int maxHoldCloseSubmitted,
            int positionSyncUnavailable
    ) {
        public LifecycleRunResult(int entryTimeoutCancelled, int partialFillProtected, int sidewaysTpAdjusted,
                                  int maxHoldCloseSubmitted) {
            this(entryTimeoutCancelled, 0, partialFillProtected, sidewaysTpAdjusted, maxHoldCloseSubmitted, 0);
        }
    }
}

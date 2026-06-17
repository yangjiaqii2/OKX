package com.example.quant.agent.plan;

import com.example.quant.agent.audit.AgentAuditLogger;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanStatus;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradePlanRecordService {
    private static final Logger log = LoggerFactory.getLogger(TradePlanRecordService.class);

    private final TradePlanRepository tradePlanRepository;
    private final TradeTakeProfitPlanRepository takeProfitPlanRepository;
    private final AgentAuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public TradePlanRecordService(
            TradePlanRepository tradePlanRepository,
            TradeTakeProfitPlanRepository takeProfitPlanRepository,
            AgentAuditLogger auditLogger,
            ObjectMapper objectMapper
    ) {
        this.tradePlanRepository = tradePlanRepository;
        this.takeProfitPlanRepository = takeProfitPlanRepository;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(TradePlan plan, TradePlanStatus status, String denyReason, Object aiPayload) {
        TradePlanEntity entity = new TradePlanEntity();
        entity.setId(plan.id().toString());
        entity.setScanRunId(null);
        entity.setInstId(plan.instId());
        entity.setDirection(plan.action().name());
        entity.setEntryType(plan.orderType());
        entity.setEntryPrice(plan.entryPrice());
        entity.setEntryZoneLow(entryZoneLow(plan));
        entity.setEntryZoneHigh(entryZoneHigh(plan));
        entity.setLeverage(plan.suggestedLeverage());
        entity.setPositionNotional(plan.suggestedSize().multiply(plan.entryPrice()).setScale(12, RoundingMode.HALF_UP));
        entity.setMarginRequired(plan.suggestedMargin());
        entity.setStopLoss(plan.stopLossPrice());
        entity.setTakeProfit(plan.takeProfitPrice());
        entity.setRiskRewardRatio(plan.riskRewardRatio());
        entity.setMaxLossUsdt(plan.maxLossAmount());
        entity.setMaxLossPercent(null);
        entity.setAllowTrade(status != TradePlanStatus.REJECTED);
        entity.setDenyReason(denyReason);
        entity.setPlanJson(json(planPayload(plan, status, denyReason, aiPayload)));
        entity.setStatus(status.name());
        entity.setCreatedAt(Instant.now());
        entity.setExpireAt(plan.expireTime());
        tradePlanRepository.save(entity);

        takeProfitPlanRepository.deleteByTradePlanId(plan.id().toString());
        takeProfitPlanRepository.saveAll(takeProfitRows(plan));
        auditLogger.info(
                "TRADE_PLAN_RECORDED",
                "trade_plan",
                plan.id().toString(),
                "Recorded trade plan status=" + status,
                Map.of("instId", plan.instId(), "status", status.name())
        );
    }

    @Transactional
    public void markPendingOrderCreated(UUID tradePlanId) {
        tradePlanRepository.findById(tradePlanId.toString()).ifPresent(entity -> {
            entity.setStatus(TradePlanStatus.PENDING_ORDER_CREATED.name());
            tradePlanRepository.save(entity);
            auditLogger.info(
                    "TRADE_PLAN_PENDING_ORDER_CREATED",
                    "trade_plan",
                    tradePlanId.toString(),
                    "Pending order created for trade plan",
                    null
            );
        });
    }

    @Transactional
    public void markOrderSubmitted(UUID tradePlanId, String externalOrderId) {
        tradePlanRepository.findById(tradePlanId.toString()).ifPresent(entity -> {
            entity.setStatus(TradePlanStatus.ORDER_SUBMITTED.name());
            tradePlanRepository.save(entity);
            auditLogger.info(
                    "TRADE_PLAN_ORDER_SUBMITTED",
                    "trade_plan",
                    tradePlanId.toString(),
                    "OKX order submitted for trade plan",
                    Map.of("externalOrderId", externalOrderId == null ? "" : externalOrderId)
            );
        });
    }

    private List<TradeTakeProfitPlanEntity> takeProfitRows(TradePlan plan) {
        BigDecimal riskDistance = plan.entryPrice().subtract(plan.stopLossPrice()).abs();
        BigDecimal direction = plan.action() == TradePlanType.OPEN_SHORT ? BigDecimal.valueOf(-1) : BigDecimal.ONE;
        return List.of(
                takeProfit(plan, 1, plan.entryPrice().add(riskDistance.multiply(direction)), new BigDecimal("30"), "TP1: 1R，平30%，止损移动到接近保本"),
                takeProfit(plan, 2, plan.entryPrice().add(riskDistance.multiply(BigDecimal.valueOf(2)).multiply(direction)), new BigDecimal("40"), "TP2: 2R，平40%"),
                takeProfit(plan, 3, plan.takeProfitPrice(), new BigDecimal("30"), "TP3: 3R或关键结构位，平30%")
        );
    }

    private TradeTakeProfitPlanEntity takeProfit(TradePlan plan, int level, BigDecimal price,
                                                 BigDecimal percent, String conditionText) {
        TradeTakeProfitPlanEntity entity = new TradeTakeProfitPlanEntity();
        entity.setTradePlanId(plan.id().toString());
        entity.setLevelNo(level);
        entity.setPrice(price.max(BigDecimal.ZERO).setScale(12, RoundingMode.HALF_UP));
        entity.setPositionPercent(percent);
        entity.setConditionText(conditionText);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private static BigDecimal entryZoneLow(TradePlan plan) {
        BigDecimal band = plan.entryPrice().multiply(new BigDecimal("0.001"));
        return plan.entryPrice().subtract(band).setScale(12, RoundingMode.HALF_UP);
    }

    private static BigDecimal entryZoneHigh(TradePlan plan) {
        BigDecimal band = plan.entryPrice().multiply(new BigDecimal("0.001"));
        return plan.entryPrice().add(band).setScale(12, RoundingMode.HALF_UP);
    }

    private Map<String, Object> planPayload(TradePlan plan, TradePlanStatus status, String denyReason, Object aiPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", plan.id());
        payload.put("instId", plan.instId());
        payload.put("action", plan.action());
        payload.put("orderType", plan.orderType());
        payload.put("directionBias", plan.directionBias());
        payload.put("confidence", plan.confidence());
        payload.put("entryPrice", plan.entryPrice());
        payload.put("stopLossPrice", plan.stopLossPrice());
        payload.put("takeProfitPrice", plan.takeProfitPrice());
        payload.put("suggestedLeverage", plan.suggestedLeverage());
        payload.put("suggestedSize", plan.suggestedSize());
        payload.put("suggestedMargin", plan.suggestedMargin());
        payload.put("maxLossAmount", plan.maxLossAmount());
        payload.put("riskRewardRatio", plan.riskRewardRatio());
        payload.put("signalScore", plan.signalScore());
        payload.put("reasonList", plan.reasonList());
        payload.put("riskList", plan.riskList());
        payload.put("invalidCondition", plan.invalidCondition());
        payload.put("status", status);
        payload.put("denyReason", denyReason);
        payload.put("aiPayload", aiPayload);
        return payload;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize trade plan payload: {}", ex.getMessage());
            return "{\"serializationError\":\"" + ex.getMessage().replace("\"", "'") + "\"}";
        }
    }
}

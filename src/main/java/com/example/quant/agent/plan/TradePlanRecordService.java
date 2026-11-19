package com.example.quant.agent.plan;

import com.example.quant.agent.audit.AgentAuditLogger;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.CanonicalExecutablePlan;
import com.example.quant.tradeplan.CanonicalPlanJsonCodec;
import com.example.quant.tradeplan.TradeIntent;
import com.example.quant.config.MarketDataContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.TradingMode;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    private AgentProperties agentProperties = new AgentProperties();

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

    @Autowired(required = false)
    void configureAgentProperties(AgentProperties agentProperties) {
        if (agentProperties != null) {
            this.agentProperties = agentProperties;
        }
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
        entity.setAllowTrade(status != TradePlanStatus.REJECTED
                && plan.action() != TradePlanType.WATCH
                && !"NO_ENTRY".equals(plan.orderType()));
        entity.setDenyReason(denyReason);
        entity.setPlanJson(json(planPayload(plan, status, denyReason, aiPayload)));
        entity.setStatus(status.name());
        entity.setCreatedAt(Instant.now());
        entity.setExpireAt(plan.expireTime());
        tradePlanRepository.save(entity);

        takeProfitPlanRepository.deleteByTradePlanId(plan.id().toString());
        if (plan.action() != TradePlanType.WATCH) {
            takeProfitPlanRepository.saveAll(takeProfitRows(plan));
        }
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

    @Transactional
    public void recordCanonicalPlan(UUID tradePlanId, CanonicalExecutablePlan canonicalPlan) {
        recordCanonicalPlan(tradePlanId, canonicalPlan, CanonicalPlanJsonCodec.write(canonicalPlan));
    }

    /** Persist the exact snapshot already bound to PendingOrder. */
    @Transactional
    public void recordCanonicalPlan(UUID tradePlanId, CanonicalExecutablePlan canonicalPlan,
                                    String canonicalPlanJson) {
        if (tradePlanId == null || canonicalPlan == null) {
            throw new IllegalArgumentException("canonical executable plan 不能为空");
        }
        if (canonicalPlanJson == null || canonicalPlanJson.isBlank()) {
            throw new IllegalArgumentException("canonical plan snapshot JSON 不能为空");
        }
        tradePlanRepository.findById(tradePlanId.toString()).ifPresent(entity -> {
            entity.setCanonicalPlanJson(canonicalPlanJson);
            tradePlanRepository.save(entity);
            auditLogger.info(
                    "CANONICAL_PLAN_COMPILED",
                    "trade_plan",
                    tradePlanId.toString(),
                    "Persisted canonical executable plan compilerVersion=" + canonicalPlan.compilerVersion(),
                    Map.of("instId", canonicalPlan.instId(), "status", canonicalPlan.status().name())
            );
        });
    }

    private List<TradeTakeProfitPlanEntity> takeProfitRows(TradePlan plan) {
        var intent = TradeIntent.fromPlan(plan,
                agentProperties.takeProfit().tp1Ratio(),
                agentProperties.takeProfit().tp2Ratio(),
                agentProperties.takeProfit().tp3Ratio(),
                "trade-plan-v1");
        return intent.exitLegs().stream()
                .map(exit -> takeProfit(plan, "TP_FINAL".equals(exit.semanticId()) ? 2 : Integer.parseInt(exit.semanticId().substring(2)),
                        exit.intendedTargetPrice(), exit.intendedFraction().movePointRight(2), exit.reason()))
                .toList();
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
        payload.put("riskBudget", plan.riskBudgetAmount());
        payload.put("estimatedStopLoss", plan.estimatedStopLossAmount());
        String configuredMode = System.getProperty("MARKET_DATA_MODE", System.getenv().getOrDefault("MARKET_DATA_MODE", "PUBLIC"));
        String trading = System.getProperty("TRADING_MODE", System.getenv().getOrDefault("TRADING_MODE", "UNKNOWN"));
        TradingMode executionMode;
        try { executionMode = TradingMode.valueOf(trading.toUpperCase()); }
        catch (IllegalArgumentException ex) { executionMode = TradingMode.DISABLED; }
        payload.put("marketDataContext", MarketDataContext.resolve(executionMode, configuredMode).asMap());
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

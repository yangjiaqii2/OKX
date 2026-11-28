package com.example.quant.agent.execution;

import com.example.quant.agent.execution.AutoTradeService.AutoTradeResult;
import com.example.quant.agent.platform.TradingAgentEntity;
import com.example.quant.agent.platform.TradingAgentRepository;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.common.PageResult;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.agent.selection.ContractSelectionDecision;
import com.example.quant.agent.selection.AiEntryObservationService;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutoTradeRecordService {
    private static final Logger log = LoggerFactory.getLogger(AutoTradeRecordService.class);
    private static final List<String> LEGACY_NOISE_REASONS = List.of(
            "auto_trade_disabled_by_config",
            "auto_trade_runtime_switch_off"
    );

    private final AutoTradeRecordRepository repository;
    private AiEntryObservationService observations;
    private TradingAgentRepository agentRepository;

    @Autowired
    void configureEntryObservations(AiEntryObservationService observations) {
        this.observations = observations;
    }

    @Autowired(required = false)
    void configureAgentRepository(TradingAgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    public AutoTradeRecordService(AutoTradeRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(AutoTradeResult result, int candidateCount, ContractCandidate candidate) {
        record(result, candidateCount, candidate, null);
    }

    @Transactional
    public void record(AutoTradeResult result, int candidateCount, ContractCandidate candidate,
                       ContractSelectionDecision selection) {
        if (repository == null || result == null) {
            return;
        }
        Instant now = result.createdAt() == null ? Instant.now() : result.createdAt();
        observeWait(result, candidate, selection, now);
        try {
            AutoTradeRecordEntity entity = new AutoTradeRecordEntity();
            entity.setScanId(org.slf4j.MDC.get("coverageScanId"));
            entity.setScanCoverage(org.slf4j.MDC.get("scanCoverage"));
            entity.setStatus(result.status());
            entity.setUserName(currentUsername());
            entity.setTriggerType("SCHEDULER");
            entity.setInstId(result.instId() != null ? result.instId() : candidate == null ? null : candidate.instId());
            entity.setTradePlanId(result.tradePlanId());
            entity.setPendingOrderId(result.pendingOrderId());
            entity.setOkxOrderId(result.okxOrderId());
            entity.setAction(result.action());
            entity.setPosSide(result.posSide());
            entity.setLeverage(result.leverage());
            entity.setMarginAmount(result.marginAmount());
            entity.setEntryPrice(result.entryPrice());
            entity.setCandidateCount(candidateCount);
            if (candidate != null) {
                entity.setScanAction(candidate.action());
                entity.setCandidateScore(candidate.score());
                entity.setTrendDirection(candidate.trendDirection() == null ? null : candidate.trendDirection().name());
                entity.setLastPrice(candidate.lastPrice());
                entity.setRiskRewardRatio(candidate.riskRewardRatio());
                entity.setSpreadBps(candidate.spreadBps());
                entity.setBidDepthUsdt(candidate.bidDepthUsdt());
                entity.setAskDepthUsdt(candidate.askDepthUsdt());
                entity.setMarketRiskLevel(candidate.marketRiskLevel() == null ? null : candidate.marketRiskLevel().name());
                entity.setFinalRankScore(candidate.finalRankScore());
                entity.setSignalType(candidate.signalType() == null ? null : candidate.signalType().name());
            }
            entity.setMessage(result.message());
            entity.setReasonCode(reasonCode(result.message()));
            entity.setReasonMessage(result.message());
            entity.setStage(entity.resolveStage(stageFor(result.status())));
            entity.setFallbackAllowed(fallbackAllowed(result.status()));
            applySelection(entity, result, selection);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            repository.save(entity);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist auto trade record status={} instId={} message={}",
                    result.status(), result.instId(), ex.getMessage());
        }
    }

    private void observeWait(AutoTradeResult result, ContractCandidate candidate,
                             ContractSelectionDecision selection, Instant at) {
        if (observations == null || selection == null || candidate == null
                || selection.source() != ContractSelectionDecision.DecisionSource.AI_WAIT
                || !"AI_WAIT_SKIPPED".equals(result.message())) return;
        try {
            observations.track(selection.observationId(), currentUsername(), candidate.instId(),
                    candidate.trendDirection(), candidate.lastPrice(), candidate.createdAt(), at);
        } catch (RuntimeException ex) {
            log.warn("AI entry tracking skipped errorType={}", ex.getClass().getSimpleName());
        }
    }

    private static void applySelection(AutoTradeRecordEntity entity, AutoTradeResult result,
                                       ContractSelectionDecision selection) {
        if (selection == null) {
            return;
        }
        entity.setDecisionSource(selection.source().name());
        entity.setAiObservationId(selection.observationId());
        entity.setAiRejectionCode(selection.rejectionCode());
        ContractSelectionDecision.RankedCandidate ranked = selection.rankedCandidates().stream()
                .filter(item -> result.instId() != null && result.instId().equals(item.instId()))
                .findFirst()
                .orElse(null);
        entity.setAiConfidence(ranked == null ? selection.confidence() : ranked.confidence());
        var direction = ranked == null ? selection.direction() : ranked.direction();
        entity.setAiDirection(direction == null ? null : direction.name());
        entity.setAiEntryMode(ranked == null ? selection.entryMode() : ranked.entryMode());
        List<String> reasons = ranked == null ? List.of() : ranked.reasonList();
        String reason = reasons.isEmpty() ? null : String.join("；", reasons);
        entity.setSelectionReason(hasText(reason) ? reason : null);
    }

    @Transactional
    public void recordAgentExecution(
            TradingAgentEntity agent,
            String scanId,
            String decision,
            String instId,
            String direction,
            int candidateCount,
            ContractCandidate candidate,
            BigDecimal marginAmount,
            Integer leverage,
            String okxOrderId,
            String llmModel,
            String summaryReason,
            String errorCode
    ) {
        if (repository == null || agent == null) {
            return;
        }
        try {
            Instant now = Instant.now();
            AutoTradeRecordEntity entity = new AutoTradeRecordEntity();
            entity.setUserName(agent.getUserName());
            entity.setAgentId(agent.getId());
            entity.setTriggerType("AGENT_" + (agent.getRunMode() == null ? "AUTO" : agent.getRunMode().name()));
            entity.setScanId(scanId);
            entity.setStatus(mapAgentDecisionToRecordStatus(decision));
            entity.setInstId(hasText(instId) ? instId : candidate != null ? candidate.instId() : null);
            if ("LONG".equalsIgnoreCase(direction)) {
                entity.setAction("BUY");
                entity.setPosSide("long");
            } else if ("SHORT".equalsIgnoreCase(direction)) {
                entity.setAction("SELL");
                entity.setPosSide("short");
            }
            entity.setLeverage(leverage != null ? leverage : agent.getMaxLeverage());
            entity.setMarginAmount(marginAmount);
            entity.setOkxOrderId(hasText(okxOrderId) ? okxOrderId : null);
            entity.setCandidateCount(candidateCount);
            if (candidate != null) {
                entity.setEntryPrice(candidate.entryPrice());
                entity.setScanAction(candidate.action());
                entity.setCandidateScore(candidate.score());
                entity.setTrendDirection(candidate.trendDirection() == null ? null : candidate.trendDirection().name());
                entity.setLastPrice(candidate.lastPrice());
                entity.setRiskRewardRatio(candidate.riskRewardRatio());
                entity.setSpreadBps(candidate.spreadBps());
                entity.setBidDepthUsdt(candidate.bidDepthUsdt());
                entity.setAskDepthUsdt(candidate.askDepthUsdt());
                entity.setMarketRiskLevel(candidate.marketRiskLevel() == null ? null : candidate.marketRiskLevel().name());
                entity.setFinalRankScore(candidate.finalRankScore());
                entity.setSignalType(candidate.signalType() == null ? null : candidate.signalType().name());
            }
            entity.setDecisionSource(hasText(llmModel) ? "AGENT_LLM" : "AGENT_RULE");
            entity.setSelectionReason(summaryReason);
            entity.setMessage(summaryReason);
            entity.setReasonCode(hasText(errorCode) ? errorCode : decision);
            entity.setReasonMessage(summaryReason);
            entity.setStage(decision);
            entity.setFallbackAllowed(false);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            repository.save(entity);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist agent auto trade record agentId={} decision={} error={}",
                    agent.getId(), decision, ex.getMessage());
        }
    }

    private static String mapAgentDecisionToRecordStatus(String decision) {
        if (decision == null) {
            return "SKIPPED";
        }
        return switch (decision) {
            case "AUTO_ORDER_EXECUTED" -> "EXECUTED";
            case "MANUAL_ORDER_CREATED" -> "WAITING_ENTRY";
            case "BLOCKED" -> "REJECTED";
            case "ERROR" -> "FAILED";
            default -> "SKIPPED";
        };
    }

    @Transactional(readOnly = true)
    public PageResult<AutoTradeRecordView> list(String status, String instId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        Page<AutoTradeRecordEntity> result = repository.findAll(spec(status, instId),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, String> agentNames = resolveAgentNames(result.getContent());
        return new PageResult<>(
                result.getContent().stream()
                        .map(entity -> AutoTradeRecordView.from(entity,
                                entity.getAgentId() == null ? null : agentNames.get(entity.getAgentId())))
                        .toList(),
                result.getTotalElements(),
                safePage,
                safeSize
        );
    }

    private Map<Long, String> resolveAgentNames(List<AutoTradeRecordEntity> records) {
        if (agentRepository == null || records == null || records.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = records.stream()
                .map(AutoTradeRecordEntity::getAgentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        agentRepository.findAllById(ids).forEach(agent -> names.put(agent.getId(), agent.getName()));
        return names;
    }

    private static Specification<AutoTradeRecordEntity> spec(String status, String instId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (hasText(status)) {
                predicates.add(builder.equal(root.get("status"), status.trim()));
            }
            predicates.add(builder.equal(root.get("userName"), currentUsername()));
            predicates.add(builder.or(
                    builder.isNull(root.get("reasonCode")),
                    builder.not(root.get("reasonCode").in(LEGACY_NOISE_REASONS))
            ));
            predicates.add(builder.or(
                    builder.isNull(root.get("message")),
                    builder.not(root.get("message").in(LEGACY_NOISE_REASONS))
            ));
            if (hasText(instId)) {
                predicates.add(builder.like(builder.lower(root.get("instId")), "%" + instId.trim().toLowerCase() + "%"));
            }
            return predicates.isEmpty() ? builder.conjunction() : builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String reasonCode(String message) {
        if (!hasText(message)) {
            return null;
        }
        String compact = message.trim();
        int colon = compact.indexOf(':');
        if (colon > 0) {
            compact = compact.substring(0, colon);
        }
        return compact.length() > 128 ? compact.substring(0, 128) : compact;
    }

    private static String stageFor(String status) {
        if (!hasText(status)) {
            return "UNKNOWN";
        }
        return switch (status) {
            case "EXECUTED" -> "SUBMIT";
            case "ENTRY_SUBMITTED" -> "ENTRY";
            case "WAITING_ENTRY" -> "WAIT_ENTRY";
            case "SKIPPED" -> "SCAN";
            case "REJECTED" -> "CONFIRM";
            case "UNKNOWN_SUBMIT_STATUS" -> "OKX_SUBMIT";
            default -> "AUTO_TRADE";
        };
    }

    private static Boolean fallbackAllowed(String status) {
        if (!hasText(status)) {
            return null;
        }
        return "SKIPPED".equals(status) || "REJECTED".equals(status);
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }
}

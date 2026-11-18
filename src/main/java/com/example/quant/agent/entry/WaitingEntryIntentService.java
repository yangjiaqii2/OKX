package com.example.quant.agent.entry;

import com.example.quant.agent.selection.ContractSelectionDecision;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.scan.ActiveEntryIntentInstIds;
import com.example.quant.market.DirectionBias;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitingEntryIntentService implements ActiveEntryIntentInstIds {
    private static final Logger log = LoggerFactory.getLogger(WaitingEntryIntentService.class);
    private static final Duration MAX_WAIT = Duration.ofMinutes(30);
    private static final Duration RELAXED_TIMING_AFTER = Duration.ofMinutes(10);
    private static final BigDecimal PRICE_TOLERANCE_RATE = new BigDecimal("0.002");
    private static final List<WaitingEntryIntentStatus> ACTIVE_STATUSES = List.of(
            WaitingEntryIntentStatus.WAITING, WaitingEntryIntentStatus.TRIGGERED,
            WaitingEntryIntentStatus.VALIDATING);
    private final WaitingEntryIntentRepository repository;

    public WaitingEntryIntentService(WaitingEntryIntentRepository repository) {
        this.repository = repository;
    }

    /**
     * 所有未终结的等待意图对应的合约，跨 owner 汇总。
     *
     * <p>{@link #evaluate} 对不在交付列表里的合约直接跳过，所以"记录还在"并不等于
     * "还在被评估"。扫描层用这个集合为它们固定深度分析与交付名额，
     * 让触发判断和失效判断每轮都拿得到新数据。
     *
     * <p>这里只回答"谁需要新数据"，不授予任何交易权限。
     */
    @Override
    @Transactional(readOnly = true)
    public Set<String> currentInstIds() {
        return repository.findByStatusIn(ACTIVE_STATUSES).stream()
                .map(WaitingEntryIntentEntity::getInstId)
                .filter(instId -> instId != null && !instId.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 测试与诊断用别名，语义同 {@link #currentInstIds()}。 */
    public Set<String> activeInstIds() {
        return currentInstIds();
    }

    public Intent createOrGet(String owner, String strategyId, ContractCandidate candidate,
                              ContractSelectionDecision selection, String decisionClientOrderId, Instant now) {
        requireWaitingSelection(candidate, selection);
        requireText(owner, "owner");
        requireText(strategyId, "strategyId");
        requireText(decisionClientOrderId, "decisionClientOrderId");
        Instant createdAt = now == null ? Instant.now() : now;
        Optional<WaitingEntryIntentEntity> sameDecision = repository.findByDecisionClientOrderId(decisionClientOrderId);
        if (sameDecision.isPresent()) {
            return view(sameDecision.get(), false);
        }
        String activeKey = activeKey(owner, strategyId, candidate.instId());
        Optional<WaitingEntryIntentEntity> active = repository.findByActiveKey(activeKey);
        if (active.isPresent()) {
            return view(active.get(), false);
        }
        BigDecimal reference = positive(candidate.entryPrice(), candidate.lastPrice());
        BigDecimal invalidation = positive(candidate.stopLossPrice(), fallbackInvalidation(reference, selection.direction()));
        WaitingEntryIntentEntity row = new WaitingEntryIntentEntity();
        row.setIntentId(UUID.randomUUID().toString());
        row.setDecisionClientOrderId(decisionClientOrderId);
        row.setActiveKey(activeKey);
        row.setUserName(owner);
        row.setStrategyId(strategyId);
        row.setInstId(candidate.instId());
        row.setDirection(selection.direction().name());
        row.setWaitType(selection.entryMode());
        row.setReferencePrice(reference);
        row.setInvalidationPrice(invalidation);
        row.setDecisionCandleTimestamp(candidate.klineAnalysis().decisionCandleTimestamp());
        row.setExpireAt(createdAt.plus(MAX_WAIT));
        row.setStatus(WaitingEntryIntentStatus.WAITING);
        row.setTriggerCount(0);
        row.setReason("AI_WAITING_ENTRY");
        row.setCreatedAt(createdAt);
        row.setUpdatedAt(createdAt);
        try {
            return view(repository.saveAndFlush(row), true);
        } catch (DataIntegrityViolationException race) {
            return repository.findByDecisionClientOrderId(decisionClientOrderId)
                    .or(() -> repository.findByActiveKey(activeKey))
                    .map(existing -> view(existing, false))
                    .orElseThrow(() -> race);
        }
    }

    @Transactional
    public Optional<TriggeredIntent> evaluate(String owner, List<ContractCandidate> candidates, Instant now) {
        Instant evaluatedAt = now == null ? Instant.now() : now;
        Map<String, ContractCandidate> byInstId = (candidates == null ? List.<ContractCandidate>of() : candidates)
                .stream().filter(candidate -> candidate != null && candidate.instId() != null)
                .collect(Collectors.toMap(ContractCandidate::instId, Function.identity(), (first, ignored) -> first));
        for (WaitingEntryIntentEntity row : repository.findByUserNameAndStatusInOrderByCreatedAtAsc(owner,
                ACTIVE_STATUSES)) {
            ContractCandidate candidate = byInstId.get(row.getInstId());
            if (expired(row, candidate, evaluatedAt)) {
                terminate(row, WaitingEntryIntentStatus.EXPIRED, "AI_WAIT_EXPIRED", evaluatedAt);
                continue;
            }
            if (candidate == null) {
                // 本轮没有该合约的数据 → 触发与失效都无从判断，只能等下一轮。
                // 扫描层会为活跃等待意图固定名额，正常情况下不该走到这里；
                // 真的走到了说明它掉出了硬过滤名单，必须可观测，不能静默。
                log.warn("waiting intent not evaluated this round: no candidate data intentId={} symbol={} "
                                + "status={} expireAt={}",
                        row.getIntentId(), row.getInstId(), row.getStatus(), row.getExpireAt());
                continue;
            }
            String invalidReason = invalidReason(row, candidate);
            if (invalidReason != null) {
                terminate(row, WaitingEntryIntentStatus.INVALIDATED, invalidReason, evaluatedAt);
                continue;
            }
            if (triggered(row, candidate)) {
                if (row.getStatus() == WaitingEntryIntentStatus.WAITING) {
                    row.setStatus(WaitingEntryIntentStatus.TRIGGERED);
                    row.setTriggerCount(row.getTriggerCount() + 1);
                    row.setReason("AI_WAIT_TRIGGERED");
                    row.setUpdatedAt(evaluatedAt);
                    repository.save(row);
                }
                return Optional.of(new TriggeredIntent(row.getIntentId(), row.getInstId(),
                        DirectionBias.valueOf(row.getDirection()), row.getDecisionClientOrderId(), candidate));
            }
        }
        return Optional.empty();
    }

    @Transactional
    public void complete(String intentId, WaitingEntryIntentStatus status, String pendingOrderId,
                         String okxOrderId, String reason, Instant now) {
        WaitingEntryIntentEntity row = repository.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("waiting entry intent not found"));
        row.setStatus(status);
        row.setPendingOrderId(blankToNull(pendingOrderId));
        row.setOkxOrderId(blankToNull(okxOrderId));
        row.setReason(compact(reason));
        if (terminal(status)) {
            row.setActiveKey(null);
        }
        row.setUpdatedAt(now == null ? Instant.now() : now);
        repository.save(row);
    }

    private static boolean expired(WaitingEntryIntentEntity row, ContractCandidate candidate, Instant now) {
        if (!now.isBefore(row.getExpireAt())) {
            return true;
        }
        Instant candle = candle(candidate);
        return candle != null && !candle.isBefore(row.getDecisionCandleTimestamp().plus(MAX_WAIT));
    }

    private static String invalidReason(WaitingEntryIntentEntity row, ContractCandidate candidate) {
        if (candidate.marketRiskLevel() == null || candidate.marketRiskLevel().ordinal() >= RiskLevel.HIGH.ordinal()) {
            return "AI_WAIT_INVALIDATED_MARKET_RISK";
        }
        if (candidate.newsAnalysis() == null || candidate.newsAnalysis().decision() == null
                || !candidate.newsAnalysis().decision().allowTrade()) {
            return candidate.newsAnalysis() != null && "UNKNOWN".equals(candidate.newsAnalysis().newsRiskLevel())
                    ? "AI_WAIT_INVALIDATED_NEWS_UNAVAILABLE" : "AI_WAIT_INVALIDATED_NEWS_RISK";
        }
        DirectionBias direction = DirectionBias.valueOf(row.getDirection());
        if (candidate.trendDirection() != DirectionBias.NEUTRAL && candidate.trendDirection() != direction) {
            return "AI_WAIT_INVALIDATED_DIRECTION";
        }
        BigDecimal price = positive(candidate.lastPrice(), candidate.entryPrice());
        if (direction == DirectionBias.BULLISH && price.compareTo(row.getInvalidationPrice()) <= 0
                || direction == DirectionBias.BEARISH && price.compareTo(row.getInvalidationPrice()) >= 0) {
            return "AI_WAIT_INVALIDATED_PRICE";
        }
        return null;
    }

    private static boolean triggered(WaitingEntryIntentEntity row, ContractCandidate candidate) {
        Instant candle = candle(candidate);
        if (candle == null || !candle.isAfter(row.getDecisionCandleTimestamp())) {
            return false;
        }
        String timing = candidate.klineAnalysis().entryTiming5m();
        boolean ready = "READY".equals(timing);
        boolean matchingWaitAfterTwoCandles = !candle.isBefore(
                row.getDecisionCandleTimestamp().plus(RELAXED_TIMING_AFTER))
                && row.getWaitType().equals(timing);
        if (!ready && !matchingWaitAfterTwoCandles) {
            return false;
        }
        BigDecimal price = positive(candidate.lastPrice(), candidate.entryPrice());
        DirectionBias direction = DirectionBias.valueOf(row.getDirection());
        BigDecimal tolerance = row.getReferencePrice().multiply(PRICE_TOLERANCE_RATE);
        return direction == DirectionBias.BULLISH
                ? price.compareTo(row.getReferencePrice().add(tolerance)) <= 0
                : price.compareTo(row.getReferencePrice().subtract(tolerance)) >= 0;
    }

    private static void terminate(WaitingEntryIntentEntity row, WaitingEntryIntentStatus status,
                                  String reason, Instant now) {
        row.setStatus(status);
        row.setActiveKey(null);
        row.setReason(reason);
        row.setUpdatedAt(now);
    }

    private static boolean terminal(WaitingEntryIntentStatus status) {
        return status == WaitingEntryIntentStatus.SUBMITTED || status == WaitingEntryIntentStatus.EXPIRED
                || status == WaitingEntryIntentStatus.INVALIDATED || status == WaitingEntryIntentStatus.CANCELED
                || status == WaitingEntryIntentStatus.FAILED_SAFE;
    }

    private static void requireWaitingSelection(ContractCandidate candidate, ContractSelectionDecision selection) {
        if (candidate == null || candidate.klineAnalysis() == null
                || candidate.klineAnalysis().decisionCandleTimestamp() == null) {
            throw new IllegalArgumentException("closed decision candle is required");
        }
        if (selection == null || selection.source() != ContractSelectionDecision.DecisionSource.AI_WAIT
                || selection.selectedInstId() == null || !selection.selectedInstId().equals(candidate.instId())
                || selection.direction() == null || selection.entryMode() == null
                || !selection.entryMode().startsWith("WAIT")) {
            throw new IllegalArgumentException("selected AI wait decision is required");
        }
    }

    private static Instant candle(ContractCandidate candidate) {
        return candidate == null || candidate.klineAnalysis() == null
                ? null : candidate.klineAnalysis().decisionCandleTimestamp();
    }

    private static String activeKey(String owner, String strategyId, String instId) {
        return owner + "|" + strategyId + "|" + instId;
    }

    private static BigDecimal fallbackInvalidation(BigDecimal reference, DirectionBias direction) {
        BigDecimal distance = reference.multiply(new BigDecimal("0.02"));
        return direction == DirectionBias.BEARISH ? reference.add(distance) : reference.subtract(distance);
    }

    private static BigDecimal positive(BigDecimal value, BigDecimal fallback) {
        return value != null && value.signum() > 0 ? value : fallback;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.substring(0, Math.min(240, compact.length()));
    }

    private static Intent view(WaitingEntryIntentEntity row, boolean created) {
        return new Intent(row.getIntentId(), row.getInstId(), row.getStatus(), row.getDecisionClientOrderId(), created);
    }

    public record Intent(String intentId, String instId, WaitingEntryIntentStatus status,
                         String clientOrderId, boolean created) {}

    public record TriggeredIntent(String intentId, String instId, DirectionBias direction,
                                  String clientOrderId, ContractCandidate candidate) {}
}

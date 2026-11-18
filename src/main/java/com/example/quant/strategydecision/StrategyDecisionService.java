package com.example.quant.strategydecision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StrategyDecisionService {
    private static final Duration DEFAULT_PROCESSING_TIMEOUT = Duration.ofMinutes(5);
    private final StrategyDecisionRepository repository;
    private final Duration processingTimeout;

    @Autowired
    public StrategyDecisionService(StrategyDecisionRepository repository) {
        this(repository, DEFAULT_PROCESSING_TIMEOUT);
    }

    public StrategyDecisionService(StrategyDecisionRepository repository, Duration processingTimeout) {
        this.repository = repository;
        this.processingTimeout = processingTimeout == null ? DEFAULT_PROCESSING_TIMEOUT : processingTimeout;
    }

    public DecisionClaim claim(String owner, String strategyId, String instId, String timeframe,
                               Instant candleTimestamp, Instant now) {
        requireText(owner, "owner");
        requireText(strategyId, "strategyId");
        requireText(instId, "instId");
        requireText(timeframe, "timeframe");
        if (candleTimestamp == null || now == null) {
            throw new IllegalArgumentException("candleTimestamp and now are required");
        }
        Optional<StrategyDecisionEntity> existing = find(owner, strategyId, instId, timeframe, candleTimestamp);
        if (existing.isPresent()) {
            return claim(existing.get(), false);
        }
        String stableClientOrderId = clientOrderId(owner, strategyId, instId, timeframe, candleTimestamp);
        Optional<StrategyDecisionEntity> stableExisting = repository.findByClientOrderId(stableClientOrderId);
        if (stableExisting.isPresent()) {
            return claim(validateStableIdentity(stableExisting.get(), owner, strategyId, instId, timeframe), false);
        }
        StrategyDecisionEntity row = new StrategyDecisionEntity();
        row.setDecisionId(UUID.randomUUID().toString());
        row.setUserName(owner);
        row.setStrategyId(strategyId);
        row.setInstId(instId);
        row.setTimeframe(timeframe);
        row.setCandleTimestamp(candleTimestamp);
        row.setStatus(StrategyDecisionStatus.PROCESSING);
        row.setClientOrderId(stableClientOrderId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            return claim(repository.saveAndFlush(row), true);
        } catch (DataIntegrityViolationException race) {
            return claim(find(owner, strategyId, instId, timeframe, candleTimestamp)
                    .or(() -> repository.findByClientOrderId(stableClientOrderId))
                    .map(existingRow -> validateStableIdentity(existingRow, owner, strategyId, instId, timeframe))
                    .orElseThrow(() -> race), false);
        }
    }

    @Transactional
    public int recoverStaleProcessing(Instant now) {
        Instant cutoff = now.minus(processingTimeout);
        var stale = repository.findByStatusAndUpdatedAtBefore(StrategyDecisionStatus.PROCESSING, cutoff);
        stale.forEach(row -> {
            row.setStatus(StrategyDecisionStatus.RECONCILING);
            row.setReason("PROCESSING_LEASE_EXPIRED");
            row.setUpdatedAt(now);
        });
        repository.saveAll(stale);
        return stale.size();
    }

    @Transactional
    public void complete(String decisionId, StrategyDecisionStatus status, String pendingOrderId,
                         String okxOrderId, String reason, Instant now) {
        if (status == null || status == StrategyDecisionStatus.PROCESSING) {
            throw new IllegalArgumentException("terminal decision status is required");
        }
        StrategyDecisionEntity row = repository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("strategy decision not found"));
        row.setStatus(status);
        row.setPendingOrderId(blankToNull(pendingOrderId));
        row.setOkxOrderId(blankToNull(okxOrderId));
        row.setReason(compact(reason));
        row.setSignalExclusionReason(status == StrategyDecisionStatus.NO_SIGNAL
                && ("auto_trade_disabled_by_config".equals(reason) || "auto_trade_runtime_switch_off".equals(reason))
                ? reason : null);
        row.setUpdatedAt(now == null ? Instant.now() : now);
        repository.save(row);
    }

    private Optional<StrategyDecisionEntity> find(String owner, String strategyId, String instId,
                                                   String timeframe, Instant candleTimestamp) {
        return repository.findByUserNameAndStrategyIdAndInstIdAndTimeframeAndCandleTimestamp(
                owner, strategyId, instId, timeframe, candleTimestamp);
    }

    private static DecisionClaim claim(StrategyDecisionEntity row, boolean claimed) {
        return new DecisionClaim(claimed, row.getDecisionId(), row.getClientOrderId(), row.getStatus());
    }

    private static StrategyDecisionEntity validateStableIdentity(StrategyDecisionEntity row, String owner,
                                                                   String strategyId, String instId, String timeframe) {
        if (!owner.equals(row.getUserName()) || !strategyId.equals(row.getStrategyId())
                || !instId.equals(row.getInstId()) || !timeframe.equals(row.getTimeframe())) {
            throw new IllegalStateException("stable client order id collision");
        }
        return row;
    }

    private static String clientOrderId(String owner, String strategyId, String instId,
                                        String timeframe, Instant candleTimestamp) {
        try {
            String key = String.join("|", owner, strategyId, instId, timeframe, candleTimestamp.toString());
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
            return "qd" + hash.substring(0, 30);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot create decision client order id", ex);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
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

    public record DecisionClaim(boolean claimed, String decisionId, String clientOrderId,
                                StrategyDecisionStatus status) {
    }
}

package com.example.quant.agent.evaluation;

import com.example.quant.agent.score.AgentContractCandidateScoreEntity;
import com.example.quant.agent.score.AgentContractCandidateScoreRepository;
import com.example.quant.crypto.OkxMarketService;
import com.example.quant.crypto.dto.ContractCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Reconciles only mature, confirmed 15 minute bars. */
@Service
public class HistoricalCandidateOutcomeReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(HistoricalCandidateOutcomeReconciliationService.class);
    private static final Set<String> RETRYABLE_STATUSES = Set.of("PENDING", "PARTIAL", "FAILED");
    private static final Duration CANDLE_INTERVAL = Duration.ofMinutes(15);
    // A 24h outcome maturing up to 48h ago still fits in the latest 300 bars (75h).
    private static final Duration RECENT_MATURITY_WINDOW = Duration.ofHours(48);

    private final AgentContractCandidateScoreRepository snapshotRepository;
    private final CandidateFutureOutcomeRepository outcomeRepository;
    private final OkxMarketService marketService;
    private final Clock clock;
    private final int batchSize;
    private final long retryDelayMs;
    private final long stuckThresholdMs;
    private final ReentrantLock reconciliationLock = new ReentrantLock();
    private Instant historicalBackoffUntil = Instant.MIN;

    @Autowired
    public HistoricalCandidateOutcomeReconciliationService(
            AgentContractCandidateScoreRepository snapshotRepository,
            CandidateFutureOutcomeRepository outcomeRepository,
            OkxMarketService marketService,
            @Value("${quant.agent.evaluation.outcome-reconciliation-batch-size:100}") int batchSize,
            @Value("${quant.agent.evaluation.outcome-reconciliation-retry-delay-ms:300000}") long retryDelayMs,
            @Value("${quant.agent.evaluation.outcome-reconciliation-stuck-threshold-ms:21600000}") long stuckThresholdMs
    ) {
        this(snapshotRepository, outcomeRepository, marketService, Clock.systemUTC(), batchSize, retryDelayMs,
                stuckThresholdMs);
    }

    public HistoricalCandidateOutcomeReconciliationService(
            AgentContractCandidateScoreRepository snapshotRepository,
            CandidateFutureOutcomeRepository outcomeRepository,
            OkxMarketService marketService,
            Clock clock
    ) {
        this(snapshotRepository, outcomeRepository, marketService, clock, 100, 300_000L);
    }

    public HistoricalCandidateOutcomeReconciliationService(
            AgentContractCandidateScoreRepository snapshotRepository,
            CandidateFutureOutcomeRepository outcomeRepository,
            OkxMarketService marketService,
            Clock clock,
            int batchSize,
            long retryDelayMs
    ) {
        this(snapshotRepository, outcomeRepository, marketService, clock, batchSize, retryDelayMs, 21_600_000L);
    }

    public HistoricalCandidateOutcomeReconciliationService(
            AgentContractCandidateScoreRepository snapshotRepository,
            CandidateFutureOutcomeRepository outcomeRepository,
            OkxMarketService marketService,
            Clock clock,
            int batchSize,
            long retryDelayMs,
            long stuckThresholdMs
    ) {
        this.snapshotRepository = snapshotRepository;
        this.outcomeRepository = outcomeRepository;
        this.marketService = marketService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.batchSize = Math.max(2, batchSize);
        this.retryDelayMs = Math.max(0L, retryDelayMs);
        this.stuckThresholdMs = Math.max(0L, stuckThresholdMs);
    }

    @Scheduled(fixedDelayString = "${quant.agent.evaluation.outcome-reconciliation-interval-ms:120000}")
    public void reconcileScheduled() {
        ReconciliationResult result = reconcile(clock.instant());
        log.info("Historical candidate outcome reconciliation processed={} completed={} failed={} partial={} skipped={}",
                result.processed(), result.completed(), result.failed(), result.partial(), result.skipped());
    }

    public ReconciliationResult reconcile(Instant requestedNow) {
        if (!reconciliationLock.tryLock()) {
            return new ReconciliationResult(0, 0, 0, 0, 0);
        }
        try {
            return reconcileLocked(requestedNow == null ? clock.instant() : requestedNow);
        } finally {
            reconciliationLock.unlock();
        }
    }

    public OutcomeDiagnostics diagnostics(Instant requestedNow) {
        Instant now = requestedNow == null ? clock.instant() : requestedNow;
        long pending = outcomeRepository.countByStatus("PENDING");
        long partial = outcomeRepository.countByStatus("PARTIAL");
        long failed = outcomeRepository.countByStatus("FAILED");
        long complete = outcomeRepository.countByStatus("COMPLETE");
        long stuck = outcomeRepository.countByStatusInAndMaturityAtLessThanEqual(
                RETRYABLE_STATUSES, now.minusMillis(stuckThresholdMs));
        Instant oldestMaturity = outcomeRepository.findFirstByStatusInOrderByMaturityAtAsc(RETRYABLE_STATUSES)
                .map(CandidateFutureOutcomeEntity::getMaturityAt).orElse(null);
        long oldestPendingAgeSeconds = oldestMaturity == null
                ? 0L : Math.max(0L, Duration.between(oldestMaturity, now).getSeconds());
        return new OutcomeDiagnostics(pending, partial, failed, complete, stuck, oldestPendingAgeSeconds);
    }

    private ReconciliationResult reconcileLocked(Instant now) {
        Instant cutoff = now.minus(RECENT_MATURITY_WINDOW);
        int historicalCapacity = Math.max(1, batchSize / 5);
        boolean canFetchHistory = !now.isBefore(historicalBackoffUntil);
        List<CandidateFutureOutcomeEntity> controls = !canFetchHistory || historicalCapacity < 2 ? List.of()
                : outcomeRepository.findHistoricalControlDue(RETRYABLE_STATUSES, cutoff, now,
                        PageRequest.of(0, Math.max(1, historicalCapacity / 5)));
        List<CandidateFutureOutcomeEntity> historical = !canFetchHistory ? List.of() : outcomeRepository.findHistoricalDue(
                RETRYABLE_STATUSES, cutoff, now, PageRequest.of(0, historicalCapacity - controls.size()));
        List<CandidateFutureOutcomeEntity> due = new ArrayList<>(outcomeRepository.findRecentDue(
                RETRYABLE_STATUSES, cutoff, now, PageRequest.of(0, batchSize - historical.size() - controls.size())));
        due.addAll(historical);
        due.addAll(controls);
        Map<MarketWindow, List<Work>> byMarketWindow = new LinkedHashMap<>();
        int skipped = 0;
        int failed = 0;
        for (CandidateFutureOutcomeEntity outcome : due) {
            if (!isDue(outcome, now)) {
                skipped++;
                continue;
            }
            AgentContractCandidateScoreEntity snapshot = outcome.getSnapshotId() == null
                    ? null : snapshotRepository.findById(outcome.getSnapshotId()).orElse(null);
            if (snapshot == null || snapshot.getInstId() == null) {
                markFailure(outcome, now, "SNAPSHOT_NOT_FOUND");
                failed++;
                continue;
            }
            Instant before = outcome.getMaturityAt().isBefore(cutoff)
                    ? historicalWindowEnd(outcome.getMaturityAt()) : null;
            MarketWindow marketWindow = new MarketWindow(snapshot.getInstId(), before);
            byMarketWindow.computeIfAbsent(marketWindow, ignored -> new ArrayList<>())
                    .add(new Work(snapshot, outcome));
        }

        int completed = 0;
        int partial = 0;
        for (Map.Entry<MarketWindow, List<Work>> entry : byMarketWindow.entrySet()) {
            MarketWindow window = entry.getKey();
            List<Work> work = entry.getValue();
            if (window.before() != null && now.isBefore(historicalBackoffUntil)) {
                skipped += work.size();
                continue;
            }
            List<ContractCandle> candles;
            try {
                candles = window.before() == null
                        ? marketService.candles(window.instId(), "15m", 300)
                        : marketService.historicalCandlesBefore(window.instId(), "15m", window.before());
            } catch (OkxMarketService.HistoryRateLimitedException ex) {
                Instant observed = clock.instant();
                Instant backoffStart = observed.isAfter(now) ? observed : now;
                historicalBackoffUntil = backoffStart.plusMillis(Math.max(300_000L, retryDelayMs));
                log.warn("Historical candle rate limited HTTP 429 instId={} after={} resumeAt={}",
                        window.instId(), window.before(), historicalBackoffUntil);
                for (Work item : work) {
                    markFailure(item.outcome(), now, "HISTORY_RATE_LIMIT_HTTP_429", historicalBackoffUntil);
                    failed++;
                }
                continue;
            } catch (RuntimeException ex) {
                for (Work item : work) {
                    markFailure(item.outcome(), now, "MARKET_DATA_ERROR:" + compact(ex.getMessage()));
                    failed++;
                }
                continue;
            }
            for (Work item : work) {
                OutcomeResult result = reconcileOne(item.snapshot(), item.outcome(), candles, now);
                switch (result) {
                    case COMPLETE -> completed++;
                    case FAILED -> failed++;
                    case PARTIAL -> partial++;
                    case SKIPPED -> skipped++;
                }
            }
        }
        return new ReconciliationResult(due.size(), completed, failed, partial, skipped);
    }

    private static Instant historicalWindowEnd(Instant maturity) {
        // A 100-bar page covers 25h. Sharing the next hour boundary still covers every 24h outcome in the group.
        Instant hour = maturity.truncatedTo(ChronoUnit.HOURS);
        return hour.equals(maturity) ? hour : hour.plus(1, ChronoUnit.HOURS);
    }

    private OutcomeResult reconcileOne(AgentContractCandidateScoreEntity snapshot,
                                       CandidateFutureOutcomeEntity outcome,
                                       List<ContractCandle> source,
                                       Instant now) {
        if ("COMPLETE".equals(outcome.getStatus())) {
            return OutcomeResult.SKIPPED;
        }
        markAttempt(outcome, now);
        Instant t0 = snapshot.getCreatedAt();
        BigDecimal p0 = snapshot.getReferencePrice();
        if (t0 == null || p0 == null || p0.signum() <= 0) {
            outcome.setStatus("UNAVAILABLE");
            outcome.setErrorReason("INVALID_REFERENCE_PRICE");
            outcome.setNextRetryAt(null);
            outcome.setUpdatedAt(now);
            outcomeRepository.save(outcome);
            return OutcomeResult.FAILED;
        }

        Instant maturity = outcome.getMaturityAt();
        List<ContractCandle> window = eligibleWindow(source, t0, maturity);
        if (window.isEmpty()) {
            markPartial(outcome, now, "FUTURE_CANDLE_MISSING");
            return OutcomeResult.PARTIAL;
        }
        if (window.size() != expectedFullCandleCount(t0, maturity) || !isContiguous(window)) {
            markPartial(outcome, now, "FUTURE_CANDLE_GAP");
            return OutcomeResult.PARTIAL;
        }

        ContractCandle horizon = window.get(window.size() - 1);
        BigDecimal maxHigh = window.stream().map(ContractCandle::high).max(BigDecimal::compareTo).orElse(null);
        BigDecimal minLow = window.stream().map(ContractCandle::low).min(BigDecimal::compareTo).orElse(null);
        if (maxHigh == null || minLow == null) {
            markPartial(outcome, now, "FUTURE_CANDLE_RANGE_MISSING");
            return OutcomeResult.PARTIAL;
        }
        BigDecimal raw = ratio(horizon.close().subtract(p0), p0);
        BigDecimal maxUpside = ratio(maxHigh.subtract(p0), p0);
        BigDecimal maxDownside = ratio(minLow.subtract(p0), p0);
        outcome.setFutureTimestamp(horizon.timestamp());
        outcome.setObservedAt(horizon.timestamp().plus(CANDLE_INTERVAL));
        outcome.setWindowStart(window.get(0).timestamp());
        outcome.setWindowEnd(horizon.timestamp().plus(CANDLE_INTERVAL));
        outcome.setFuturePrice(horizon.close());
        outcome.setRawReturn(raw);
        outcome.setMaxUpside(maxUpside);
        outcome.setMaxDownside(maxDownside);
        if ("PULLBACK_LONG".equals(snapshot.getSignalType())) {
            // Historical snapshots do not reliably have a planned pullback entry price.
            outcome.setPullbackReferenceTouched(minLow.compareTo(p0) <= 0);
        }
        String direction = directionValue(snapshot);
        if (direction.contains("SHORT") || "BEARISH".equals(direction)) {
            outcome.setDirectionalReturn(raw.negate());
            outcome.setDirectionalMfe(maxDownside.negate().max(BigDecimal.ZERO));
            outcome.setDirectionalMae(maxUpside.max(BigDecimal.ZERO));
            recordWindowRisk(snapshot, outcome, window, p0, false);
        } else if (direction.contains("LONG") || "BULLISH".equals(direction)) {
            outcome.setDirectionalReturn(raw);
            outcome.setDirectionalMfe(maxUpside.max(BigDecimal.ZERO));
            outcome.setDirectionalMae(maxDownside.negate().max(BigDecimal.ZERO));
            recordWindowRisk(snapshot, outcome, window, p0, true);
        } else {
            outcome.setDirectionalReturn(null);
            outcome.setDirectionalMfe(null);
            outcome.setDirectionalMae(null);
            outcome.setDirectionalMaxDrawdown(null);
            outcome.setFirstBarrierTouch("UNAVAILABLE");
        }
        outcome.setStatus("COMPLETE");
        outcome.setErrorReason(null);
        outcome.setNextRetryAt(null);
        outcome.setUpdatedAt(now);
        outcomeRepository.save(outcome);
        return OutcomeResult.COMPLETE;
    }

    private static void recordWindowRisk(AgentContractCandidateScoreEntity snapshot, CandidateFutureOutcomeEntity outcome,
                                         List<ContractCandle> window, BigDecimal reference, boolean isLong) {
        BigDecimal stop = snapshot.getSnapshotStopLossPrice();
        BigDecimal target = snapshot.getSnapshotTakeProfitPrice();
        boolean hasBarriers = stop != null && target != null && stop.signum() > 0 && target.signum() > 0
                && (isLong ? stop.compareTo(reference) < 0 && target.compareTo(reference) > 0
                           : target.compareTo(reference) < 0 && stop.compareTo(reference) > 0);
        String firstTouch = hasBarriers ? "NONE" : "UNAVAILABLE";
        BigDecimal best = reference;
        BigDecimal drawdown = BigDecimal.ZERO;
        for (ContractCandle bar : window) {
            best = isLong ? best.max(bar.high()) : best.min(bar.low());
            BigDecimal adverseMove = isLong ? best.subtract(bar.low()) : bar.high().subtract(best);
            drawdown = drawdown.max(adverseMove);
            if (!"NONE".equals(firstTouch)) {
                continue;
            }
            boolean stopHit = isLong ? bar.low().compareTo(stop) <= 0 : bar.high().compareTo(stop) >= 0;
            boolean targetHit = isLong ? bar.high().compareTo(target) >= 0 : bar.low().compareTo(target) <= 0;
            if (stopHit) {
                firstTouch = "STOP_LOSS";
            } else if (targetHit) {
                firstTouch = "TAKE_PROFIT";
            }
        }
        outcome.setDirectionalMaxDrawdown(ratio(drawdown, reference));
        outcome.setFirstBarrierTouch(firstTouch);
    }

    private List<ContractCandle> eligibleWindow(List<ContractCandle> source, Instant t0, Instant maturity) {
        if (maturity == null || maturity.isBefore(t0)) return List.of();
        return (source == null ? List.<ContractCandle>of() : source).stream()
                .filter(this::usable)
                .filter(candle -> !candle.timestamp().isBefore(t0))
                .filter(candle -> !candle.timestamp().plus(CANDLE_INTERVAL).isAfter(maturity))
                .sorted(Comparator.comparing(ContractCandle::timestamp))
                .toList();
    }

    private boolean isContiguous(List<ContractCandle> window) {
        for (int index = 1; index < window.size(); index++) {
            Instant expected = window.get(index - 1).timestamp().plus(CANDLE_INTERVAL);
            if (!expected.equals(window.get(index).timestamp())) return false;
        }
        return true;
    }

    private int expectedFullCandleCount(Instant t0, Instant maturity) {
        Instant first = ceilToCandleBoundary(t0);
        Instant last = floorToCandleBoundary(maturity.minus(CANDLE_INTERVAL));
        if (last.isBefore(first)) return 0;
        return Math.toIntExact(Duration.between(first, last).toMinutes() / CANDLE_INTERVAL.toMinutes() + 1);
    }

    private Instant ceilToCandleBoundary(Instant value) {
        Instant floor = floorToCandleBoundary(value);
        return floor.equals(value) ? floor : floor.plus(CANDLE_INTERVAL);
    }

    private Instant floorToCandleBoundary(Instant value) {
        long intervalSeconds = CANDLE_INTERVAL.getSeconds();
        long floorEpochSecond = Math.floorDiv(value.getEpochSecond(), intervalSeconds) * intervalSeconds;
        return Instant.ofEpochSecond(floorEpochSecond);
    }

    private boolean isDue(CandidateFutureOutcomeEntity outcome, Instant now) {
        return outcome != null
                && RETRYABLE_STATUSES.contains(outcome.getStatus())
                && outcome.getMaturityAt() != null
                && !outcome.getMaturityAt().isAfter(now)
                && (outcome.getNextRetryAt() == null || !outcome.getNextRetryAt().isAfter(now));
    }

    private void markAttempt(CandidateFutureOutcomeEntity outcome, Instant now) {
        int current = outcome.getRetryCount() == null ? 0 : outcome.getRetryCount();
        outcome.setRetryCount(current + 1);
        outcome.setLastAttemptAt(now);
    }

    private void markPartial(CandidateFutureOutcomeEntity outcome, Instant now, String reason) {
        outcome.setStatus("PARTIAL");
        outcome.setErrorReason(reason);
        outcome.setNextRetryAt(now.plusMillis(retryDelayMs));
        outcome.setUpdatedAt(now);
        outcomeRepository.save(outcome);
    }

    private void markFailure(CandidateFutureOutcomeEntity outcome, Instant now, String reason) {
        markFailure(outcome, now, reason, now.plusMillis(retryDelayMs));
    }

    private void markFailure(CandidateFutureOutcomeEntity outcome, Instant now, String reason, Instant nextRetryAt) {
        markAttempt(outcome, now);
        outcome.setStatus("FAILED");
        outcome.setErrorReason(reason);
        outcome.setNextRetryAt(nextRetryAt);
        outcome.setUpdatedAt(now);
        outcomeRepository.save(outcome);
    }

    private boolean usable(ContractCandle candle) {
        return candle != null && candle.timestamp() != null && candle.close() != null
                && candle.high() != null && candle.low() != null
                && candle.close().signum() > 0 && candle.high().signum() > 0 && candle.low().signum() > 0
                && candle.timestamp().equals(floorToCandleBoundary(candle.timestamp()));
    }

    private String directionValue(AgentContractCandidateScoreEntity snapshot) {
        String signal = snapshot.getSignalType();
        String direction = signal == null ? snapshot.getDirection() : signal;
        return direction == null ? "" : direction.toUpperCase(java.util.Locale.ROOT);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, 12, RoundingMode.HALF_UP);
    }

    private static String compact(String message) {
        if (message == null || message.isBlank()) return "unknown";
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private record Work(AgentContractCandidateScoreEntity snapshot, CandidateFutureOutcomeEntity outcome) {}

    private record MarketWindow(String instId, Instant before) {}

    private enum OutcomeResult { COMPLETE, FAILED, PARTIAL, SKIPPED }

    public record ReconciliationResult(int processed, int completed, int failed, int partial, int skipped) {
        public ReconciliationResult(int processed, int completed, int failed, int partial) {
            this(processed, completed, failed, partial, 0);
        }
    }

    public record OutcomeDiagnostics(long pending, long partial, long failed, long complete,
                                     long stuck, long oldestPendingAgeSeconds) {}
}

package com.example.quant.agent.evaluation;

import com.example.quant.agent.score.AgentContractCandidateScoreEntity;
import com.example.quant.agent.score.AgentContractCandidateScoreRepository;
import com.example.quant.agent.scan.AgentScanRunEntity;
import com.example.quant.agent.scan.AgentScanRunRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class HistoricalCandidateEvaluationService {
    private static final List<Integer> HORIZONS = List.of(60, 240, 1440);

    private final AgentContractCandidateScoreRepository snapshotRepository;
    private final CandidateFutureOutcomeRepository outcomeRepository;
    private final AgentScanRunRepository scanRunRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public HistoricalCandidateEvaluationService(AgentContractCandidateScoreRepository snapshotRepository,
                                                CandidateFutureOutcomeRepository outcomeRepository,
                                                AgentScanRunRepository scanRunRepository) {
        this.snapshotRepository = snapshotRepository;
        this.outcomeRepository = outcomeRepository;
        this.scanRunRepository = scanRunRepository;
    }

    /** Compatibility constructor for isolated calculator tests without cohort persistence. */
    public HistoricalCandidateEvaluationService(AgentContractCandidateScoreRepository snapshotRepository,
                                                CandidateFutureOutcomeRepository outcomeRepository) {
        this(snapshotRepository, outcomeRepository, null);
    }

    public EvaluationReport evaluate(String scoringVersion, int minimumSampleSize) {
        int minimum = Math.max(1, minimumSampleSize);
        List<AgentContractCandidateScoreEntity> loadedSnapshots = snapshotRepository
                .findByScoringVersionOrderBySnapshotRankAsc(scoringVersion);
        CohortSelection cohortSelection = selectCompleteCohorts(loadedSnapshots);
        List<AgentContractCandidateScoreEntity> snapshots = cohortSelection.snapshots();
        List<Long> ids = snapshots.stream().map(AgentContractCandidateScoreEntity::getId)
                .filter(java.util.Objects::nonNull).toList();
        Map<Long, List<CandidateFutureOutcomeEntity>> outcomesBySnapshot = ids.isEmpty()
                ? Map.of() : outcomeRepository.findBySnapshotIdIn(ids).stream()
                .collect(Collectors.groupingBy(CandidateFutureOutcomeEntity::getSnapshotId));
        Map<Integer, HorizonEvaluation> horizonReports = new LinkedHashMap<>();
        for (Integer horizon : HORIZONS) {
            List<Sample> samples = snapshots.stream()
                    .flatMap(snapshot -> outcomesBySnapshot.getOrDefault(snapshot.getId(), List.of()).stream()
                            .filter(outcome -> horizon.equals(outcome.getHorizonMinutes()))
                            .filter(outcome -> "COMPLETE".equals(outcome.getStatus()))
                            .map(outcome -> new Sample(snapshot, outcome)))
                    .toList();
            Map<String, BucketAccumulator> ranks = new LinkedHashMap<>();
            Map<String, BucketAccumulator> coverage = new LinkedHashMap<>();
            Map<String, BucketAccumulator> dimensions = new LinkedHashMap<>();
            for (Sample sample : samples) {
                add(ranks, rankBucket(sample.snapshot().getSnapshotRank()), sample);
                add(coverage, coverageBucket(sample.snapshot().getV2CoverageRatio()), sample);
                for (Dimension dimension : Dimension.values()) {
                    String bucket = dimensionBucket(dimension.value(sample.snapshot()));
                    if (bucket != null) add(dimensions, dimension.name() + ":" + bucket, sample);
                }
            }
            horizonReports.put(horizon, new HorizonEvaluation(
                    snapshots.size(), samples.size(), snapshots.size() - samples.size(),
                    materialize(ranks, minimum), materialize(coverage, minimum),
                    materialize(dimensions, minimum)));
        }
        return new EvaluationReport(scoringVersion, snapshots.size(), cohortSelection.incompleteCohortCount(), horizonReports);
    }

    private CohortSelection selectCompleteCohorts(List<AgentContractCandidateScoreEntity> loaded) {
        if (scanRunRepository == null) return new CohortSelection(loaded, 0);
        Map<Long, List<AgentContractCandidateScoreEntity>> byRun = loaded.stream()
                .filter(snapshot -> snapshot.getScanRunId() != null)
                .collect(Collectors.groupingBy(AgentContractCandidateScoreEntity::getScanRunId));
        List<AgentContractCandidateScoreEntity> complete = new ArrayList<>();
        int incomplete = 0;
        for (Map.Entry<Long, List<AgentContractCandidateScoreEntity>> entry : byRun.entrySet()) {
            AgentScanRunEntity run = scanRunRepository.findById(entry.getKey()).orElse(null);
            long persisted = snapshotRepository.countByScanRunId(entry.getKey());
            boolean isComplete = run != null && "SUCCESS".equals(run.getStatus())
                    && run.getCandidateCount() > 0
                    && run.getCandidateCount() == persisted
                    && persisted == entry.getValue().size();
            if (isComplete) complete.addAll(entry.getValue());
            else incomplete++;
        }
        incomplete += (int) loaded.stream().filter(snapshot -> snapshot.getScanRunId() == null)
                .map(ignored -> Boolean.TRUE).distinct().count();
        return new CohortSelection(complete, incomplete);
    }

    private static void add(Map<String, BucketAccumulator> buckets, String bucket, Sample sample) {
        if (bucket == null) return;
        buckets.computeIfAbsent(bucket, ignored -> new BucketAccumulator()).add(sample);
    }

    private static Map<String, BucketMetrics> materialize(Map<String, BucketAccumulator> source, int minimum) {
        Map<String, BucketMetrics> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, value.metrics(minimum)));
        return result;
    }

    private static String rankBucket(Integer rank) {
        if (rank == null || rank < 1) return null;
        if (rank <= 5) return "RANK_1_5";
        if (rank <= 10) return "RANK_6_10";
        if (rank <= 20) return "RANK_11_20";
        return "RANK_21_PLUS";
    }

    private static String coverageBucket(BigDecimal coverage) {
        if (coverage == null) return null;
        if (coverage.compareTo(new BigDecimal("0.50")) < 0) return "LOW";
        if (coverage.compareTo(new BigDecimal("0.85")) < 0) return "MEDIUM";
        return "HIGH";
    }

    private static String dimensionBucket(BigDecimal score) {
        if (score == null || score.signum() < 0) return null;
        if (score.compareTo(BigDecimal.valueOf(20)) < 0) return "0_20";
        if (score.compareTo(BigDecimal.valueOf(40)) < 0) return "20_40";
        if (score.compareTo(BigDecimal.valueOf(60)) < 0) return "40_60";
        if (score.compareTo(BigDecimal.valueOf(80)) < 0) return "60_80";
        return "80_100";
    }

    private enum Dimension {
        CAPITAL_FLOW(AgentContractCandidateScoreEntity::getCapitalFlowScore),
        DERIVATIVES(AgentContractCandidateScoreEntity::getDerivativesScore),
        RELATIVE_STRENGTH(AgentContractCandidateScoreEntity::getRelativeStrengthScore),
        MARKET_STRUCTURE(AgentContractCandidateScoreEntity::getMarketStructureScore),
        VOLUME_QUALITY(AgentContractCandidateScoreEntity::getVolumeQualityScore),
        BREAKOUT(AgentContractCandidateScoreEntity::getBreakoutScore),
        NEWS(AgentContractCandidateScoreEntity::getV2NewsScore);

        private final Function<AgentContractCandidateScoreEntity, BigDecimal> accessor;

        Dimension(Function<AgentContractCandidateScoreEntity, BigDecimal> accessor) {
            this.accessor = accessor;
        }

        BigDecimal value(AgentContractCandidateScoreEntity snapshot) {
            return accessor.apply(snapshot);
        }
    }

    private record Sample(AgentContractCandidateScoreEntity snapshot, CandidateFutureOutcomeEntity outcome) {}

    private static final class BucketAccumulator {
        private final List<Sample> samples = new ArrayList<>();

        void add(Sample sample) { samples.add(sample); }

        BucketMetrics metrics(int minimumSampleSize) {
            return new BucketMetrics(
                    samples.size(),
                    count(samples, sample -> sample.outcome().getRawReturn()),
                    count(samples, sample -> sample.outcome().getDirectionalReturn()),
                    samples.stream().filter(sample -> sample.outcome().getDirectionalMfe() != null
                            && sample.outcome().getDirectionalMae() != null).count(),
                    samples.size() < minimumSampleSize,
                    average(samples, sample -> sample.outcome().getRawReturn()),
                    median(samples, sample -> sample.outcome().getRawReturn()),
                    average(samples, sample -> sample.outcome().getDirectionalReturn()),
                    median(samples, sample -> sample.outcome().getDirectionalReturn()),
                    hitRate(samples),
                    average(samples, sample -> sample.snapshot().getV2RankingScore()),
                    average(samples, sample -> sample.outcome().getDirectionalMfe()),
                    average(samples, sample -> sample.outcome().getDirectionalMae()));
        }
    }

    private static BigDecimal average(List<Sample> samples, Function<Sample, BigDecimal> value) {
        List<BigDecimal> values = samples.stream().map(value).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 12, RoundingMode.HALF_UP);
    }

    private static long count(List<Sample> samples, Function<Sample, BigDecimal> value) {
        return samples.stream().map(value).filter(java.util.Objects::nonNull).count();
    }

    private static BigDecimal median(List<Sample> samples, Function<Sample, BigDecimal> value) {
        List<BigDecimal> values = samples.stream().map(value).filter(java.util.Objects::nonNull)
                .sorted().toList();
        if (values.isEmpty()) return null;
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) return values.get(middle);
        return values.get(middle - 1).add(values.get(middle))
                .divide(BigDecimal.valueOf(2), 12, RoundingMode.HALF_UP);
    }

    private static BigDecimal hitRate(List<Sample> samples) {
        List<BigDecimal> returns = samples.stream().map(sample -> sample.outcome().getDirectionalReturn())
                .filter(java.util.Objects::nonNull).toList();
        if (returns.isEmpty()) return null;
        long hits = returns.stream().filter(value -> value.signum() > 0).count();
        return BigDecimal.valueOf(hits).divide(BigDecimal.valueOf(returns.size()), 12, RoundingMode.HALF_UP);
    }

    public record EvaluationReport(String scoringVersion, int candidateCount, int incompleteCohortCount,
                                   Map<Integer, HorizonEvaluation> horizons) {
        public EvaluationReport(String scoringVersion, int candidateCount,
                                Map<Integer, HorizonEvaluation> horizons) {
            this(scoringVersion, candidateCount, 0, horizons);
        }
    }

    public record HorizonEvaluation(int candidateCount, int completeOutcomeCount, int missingOutcomeCount,
                                    Map<String, BucketMetrics> rankBuckets,
                                    Map<String, BucketMetrics> coverageBuckets,
                                    Map<String, BucketMetrics> dimensionBuckets) {}

    public record BucketMetrics(int sampleCount, long rawSampleCount, long directionalSampleCount,
                                long excursionSampleCount, boolean insufficientSample,
                                BigDecimal averageRawReturn, BigDecimal medianRawReturn,
                                BigDecimal averageDirectionalReturn, BigDecimal medianDirectionalReturn,
                                BigDecimal hitRate, BigDecimal averageRankingScore,
                                BigDecimal averageMfe, BigDecimal averageMae) {
        public BucketMetrics(int sampleCount, boolean insufficientSample,
                             BigDecimal averageRawReturn, BigDecimal medianRawReturn,
                             BigDecimal averageDirectionalReturn, BigDecimal medianDirectionalReturn,
                             BigDecimal hitRate, BigDecimal averageRankingScore,
                             BigDecimal averageMfe, BigDecimal averageMae) {
            this(sampleCount, sampleCount, sampleCount, sampleCount, insufficientSample,
                    averageRawReturn, medianRawReturn, averageDirectionalReturn, medianDirectionalReturn,
                    hitRate, averageRankingScore, averageMfe, averageMae);
        }
    }

    private record CohortSelection(List<AgentContractCandidateScoreEntity> snapshots,
                                   int incompleteCohortCount) {}
}

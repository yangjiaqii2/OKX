package com.example.quant.crypto;

import com.example.quant.agent.scan.ScanContext;
import com.example.quant.agent.scan.ScanRunReport;
import com.example.quant.agent.scan.ScanRunStatus;
import com.example.quant.agent.score.ContractCandidateScoreRecorder;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.ContractProperties;
import com.example.quant.crypto.dto.ContractAnalysisReport;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.score.ContractScoreCalculator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ContractAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(ContractAnalysisService.class);

    private final OkxContractScanner scanner;
    private final ContractCandidateScoreRecorder scoreRecorder;
    private final AtomicReference<LiveScanSnapshot> liveSnapshot =
            new AtomicReference<>(LiveScanSnapshot.empty());
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile ScanContext activeScanContext;
    private ContractProperties contractProperties;
    private AgentProperties agentProperties = new AgentProperties();

    public ContractAnalysisService(
            OkxContractScanner scanner,
            ContractScoreCalculator calculator,
            ContractCandidateScoreRecorder scoreRecorder
    ) {
        this.scanner = scanner;
        this.scoreRecorder = scoreRecorder;
    }

    public List<ContractCandidate> candidates() {
        if (contractProperties != null && !contractProperties.enabled()) {
            return List.of();
        }
        return liveSnapshot.get().candidates();
    }

    public List<ContractCandidate> refreshCandidates() {
        return refreshScan(null).candidates();
    }

    public ContractScanResult refreshScan(String scanId) {
        if (contractProperties != null && !contractProperties.enabled()) {
            return new ContractScanResult(List.of(), null);
        }
        refreshLock.lock();
        try {
            ContractScanResult result = scanLiveCandidates(scanId);
            log.info("Live contract candidates refreshed by scheduler, scanId={}, recommendations={}, result={}",
                    scanId, result.candidates().size(), result.status());
            return result;
        } finally {
            refreshLock.unlock();
        }
    }

    public ScanContext runningScanContext() {
        ScanContext local = activeScanContext;
        if (local != null) {
            return local;
        }
        return scanner == null ? null : scanner.activeScanContext();
    }

    public Map<String, V2SelectionScoreResult> cachedV2Scores() {
        return liveSnapshot.get().v2Scores();
    }

    public LiveScanSnapshot liveSnapshot() {
        return liveSnapshot.get();
    }

    @Autowired
    void configureContractProperties(ContractProperties contractProperties) {
        this.contractProperties = contractProperties;
    }

    @Autowired
    void configureAgentProperties(AgentProperties agentProperties) {
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
    }

    private ContractScanResult scanLiveCandidates(String scanId) {
        ScanContext context = ScanContext.create(scanId, agentProperties);
        activeScanContext = context;
        Instant startedAt = context.startedAt();
        try {
            List<ContractCandidate> scanned = scanner.scan(context);
            ContractScanResult outcome = context.result();
            ScanRunReport report = outcome == null ? scanner.lastScanReport() : outcome.report();
            if (report == null) {
                log.warn("scanId={} status=UNKNOWN reason=SCAN_REPORT_MISSING", context.scanId());
                Instant finishedAt = Instant.now();
                persistScanOutcome(null, List.of(), startedAt, finishedAt, ScanRunStatus.UNKNOWN, Map.of());
                return new ContractScanResult(List.of(), null);
            }
            ScanRunStatus status = report.status();
            Instant finishedAt = Instant.now();
            Map<String, V2SelectionScoreResult> v2Scores = status != ScanRunStatus.COMPLETED ? Map.of()
                    : outcome == null ? scanner.lastV2ScoreResults() : outcome.v2Scores();
            persistScanOutcome(report, scanned, startedAt, finishedAt, status, v2Scores);
            if (status == ScanRunStatus.COMPLETED) {
                List<ContractCandidate> published = scanned == null
                        ? List.of()
                        : scanned.stream().filter(Objects::nonNull).toList();
                LiveScanSnapshot previous = liveSnapshot.get();
                if (published.isEmpty() && !previous.candidates().isEmpty()) {
                    log.warn("scanId={} completed with zero candidates; retaining prior live snapshot scanId={} candidates={}",
                            report.scanId(), previous.scanId(), previous.candidates().size());
                    return new ContractScanResult(List.of(), report, Map.of());
                }
                liveSnapshot.set(LiveScanSnapshot.completed(
                        report.scanId(),
                        published,
                        v2Scores == null ? Map.of() : Map.copyOf(v2Scores),
                        startedAt,
                        finishedAt));
                return outcome == null ? new ContractScanResult(published, report, v2Scores) : outcome;
            }
            return new ContractScanResult(List.of(), report);
        } finally {
            activeScanContext = null;
        }
    }

    private void persistScanOutcome(ScanRunReport report, List<ContractCandidate> scanned,
                                    Instant startedAt, Instant finishedAt, ScanRunStatus status,
                                    Map<String, V2SelectionScoreResult> v2Scores) {
        if (scoreRecorder == null) {
            return;
        }
        try {
            if (status == ScanRunStatus.COMPLETED) {
                scoreRecorder.recordScanOutcome(report, scanned, startedAt, finishedAt,
                        scanner.isV2Mode(), v2Scores);
            } else {
                scoreRecorder.recordScanOutcome(report, List.of(), startedAt, finishedAt,
                        scanner.isV2Mode(), Map.of());
            }
        } catch (RuntimeException ex) {
            log.warn("Historical candidate snapshot persistence failed without changing scan result: {}",
                    ex.getMessage());
        }
    }

    public ContractAnalysisReport analyze(String instId) {
        LiveScanSnapshot snapshot = liveSnapshot.get();
        if (snapshot.status() != ScanRunStatus.COMPLETED) {
            throw new IllegalArgumentException("合约不在当前有效扫描结果中: " + instId);
        }
        ContractCandidate candidate = snapshot.candidates().stream()
                .filter(item -> item.instId().equals(instId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("合约不在当前有效扫描结果中: " + instId));
        V2SelectionScoreResult v2Score = snapshot.v2Scores().get(instId);
        int score = requireCanonicalScore(v2Score, instId);
        return new ContractAnalysisReport(
                candidate.instId(),
                score,
                recommendLevel(score),
                candidate.trendDirection(),
                buildSummary(candidate, v2Score, score),
                candidate.candidateReasonList(),
                candidate.riskTagList(),
                "可基于当前OKX价格生成待确认TradePlan。",
                "行情延迟、风险等级升高或用户未确认时失效。",
                Instant.now()
        );
    }

    private static int requireCanonicalScore(V2SelectionScoreResult v2Score, String instId) {
        if (v2Score != null && v2Score.rankingScore() != null) {
            return v2Score.rankingScore().setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        }
        if (v2Score != null && v2Score.normalizedScore() != null) {
            return v2Score.normalizedScore().setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        }
        throw new IllegalStateException("V2 canonical score 不可用: " + instId);
    }

    private static String recommendLevel(int score) {
        if (score >= 85) {
            return "STRONG";
        }
        if (score >= 75) {
            return "GOOD";
        }
        if (score >= 65) {
            return "WATCH";
        }
        return "WEAK";
    }

    private static String buildSummary(ContractCandidate candidate, V2SelectionScoreResult v2Score, int score) {
        String ranking = v2Score == null || v2Score.rankingScore() == null
                ? "UNKNOWN"
                : v2Score.rankingScore().stripTrailingZeros().toPlainString();
        String normalized = v2Score == null || v2Score.normalizedScore() == null
                ? "UNKNOWN"
                : v2Score.normalizedScore().stripTrailingZeros().toPlainString();
        return "OKX实时扫描V2评分，rankingScore=" + ranking
                + "，normalizedScore=" + normalized
                + "，canonicalScore=" + score
                + "，finalRankScore=" + (candidate.finalRankScore() == null
                ? "UNKNOWN" : candidate.finalRankScore().stripTrailingZeros().toPlainString());
    }
}

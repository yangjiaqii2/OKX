package com.example.quant.agent.score;

import com.example.quant.agent.gate.ContractTradeGate;
import com.example.quant.agent.audit.AgentAuditLogger;
import com.example.quant.agent.scan.AgentScanRunEntity;
import com.example.quant.agent.scan.AgentScanRunRepository;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractCandidateScoreRecorder {
    private static final Logger log = LoggerFactory.getLogger(ContractCandidateScoreRecorder.class);

    private final AgentProperties agentProperties;
    private final AgentScanRunRepository scanRunRepository;
    private final AgentContractCandidateScoreRepository scoreRepository;
    private final AgentAuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public ContractCandidateScoreRecorder(
            AgentProperties agentProperties,
            AgentScanRunRepository scanRunRepository,
            AgentContractCandidateScoreRepository scoreRepository,
            AgentAuditLogger auditLogger,
            ObjectMapper objectMapper
    ) {
        this.agentProperties = agentProperties;
        this.scanRunRepository = scanRunRepository;
        this.scoreRepository = scoreRepository;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recordLiveScan(List<ContractCandidate> candidates, Instant startedAt, Instant finishedAt) {
        if (!agentProperties.enabled()) {
            return;
        }
        try {
            AgentScanRunEntity scanRun = new AgentScanRunEntity();
            scanRun.setStatus("SUCCESS");
            scanRun.setStartedAt(startedAt);
            scanRun.setFinishedAt(finishedAt);
            scanRun.setCandidateCount(candidates.size());
            candidates.stream().findFirst().ifPresentOrElse(candidate -> {
                scanRun.setMarketRisk(String.valueOf(candidate.marketRiskLevel()));
                scanRun.setBtcTrend(String.valueOf(candidate.btcTrend()));
                scanRun.setEthTrend(String.valueOf(candidate.ethTrend()));
            }, () -> {
                scanRun.setMarketRisk("UNKNOWN");
                scanRun.setBtcTrend("UNKNOWN");
                scanRun.setEthTrend("UNKNOWN");
            });
            scanRun.setMessage("Live OKX contract candidates scored, count=" + candidates.size());
            scanRun.setCreatedAt(finishedAt);
            AgentScanRunEntity saved = scanRunRepository.save(scanRun);

            List<AgentContractCandidateScoreEntity> rows = candidates.stream()
                    .map(candidate -> scoreRow(saved.getId(), candidate, finishedAt))
                    .toList();
            scoreRepository.saveAll(rows);

            auditLogger.info(
                    "CONTRACT_SCAN_RECORDED",
                    "agent_scan_run",
                    String.valueOf(saved.getId()),
                    "Recorded live OKX contract score run",
                    List.copyOf(candidates.stream().map(ContractCandidate::instId).toList())
            );
        } catch (RuntimeException ex) {
            log.warn("Failed to record live contract scan: {}", ex.getMessage());
            auditLogger.warn("CONTRACT_SCAN_RECORD_FAILED", "agent_scan_run", null, ex.getMessage(), null);
        }
    }

    private AgentContractCandidateScoreEntity scoreRow(Long scanRunId, ContractCandidate candidate, Instant createdAt) {
        ContractFactorScore factor = candidate.factorScore();
        TradeGate gate = tradeGate(candidate);
        AgentContractCandidateScoreEntity row = new AgentContractCandidateScoreEntity();
        row.setScanRunId(scanRunId);
        row.setInstId(candidate.instId());
        row.setDirection(String.valueOf(candidate.trendDirection()));
        row.setTotalScore(candidate.score());
        row.setTrendScore(factor.trendScore());
        row.setVolumeScore(factor.volumeScore());
        row.setVolatilityScore(factor.volatilityScore());
        row.setLiquidityScore(factor.liquidityScore());
        row.setOiFundingScore(factor.oiFundingScore());
        row.setMarketEnvScore(factor.marketEnvScore());
        row.setNewsRiskScore(factor.newsRiskScore());
        row.setAllowTrade(gate.allowTrade());
        row.setDenyReason(gate.denyReason());
        row.setReasonJson(json(candidate.candidateReasonList()));
        row.setRiskJson(json(candidate.riskTagList()));
        row.setRawMarketJson(json(rawMarket(candidate)));
        row.setCreatedAt(createdAt);
        return row;
    }

    private TradeGate tradeGate(ContractCandidate candidate) {
        List<String> reasons = ContractTradeGate.scoreDenyReasons(candidate, agentProperties);
        return reasons.isEmpty()
                ? new TradeGate(true, "")
                : new TradeGate(false, String.join(",", reasons));
    }

    private CandidateRawMarket rawMarket(ContractCandidate candidate) {
        return new CandidateRawMarket(
                candidate.lastPrice(),
                candidate.changePercent24h(),
                candidate.changePercent5m(),
                candidate.volume24h(),
                candidate.volumeSpikeRatio(),
                candidate.fundingRate(),
                candidate.openInterest(),
                candidate.openInterestChange(),
                candidate.volatility(),
                candidate.spreadBps(),
                candidate.bidDepthUsdt(),
                candidate.askDepthUsdt(),
                candidate.estimatedSlippageBps(),
                candidate.btcTrend(),
                candidate.ethTrend(),
                candidate.marketRiskLevel(),
                candidate.createdAt()
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{\"serializationError\":\"" + ex.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private record TradeGate(boolean allowTrade, String denyReason) {
    }

    private record CandidateRawMarket(
            Object lastPrice,
            Object changePercent24h,
            Object changePercent5m,
            Object volume24h,
            Object volumeSpikeRatio,
            Object fundingRate,
            Object openInterest,
            Object openInterestChange,
            Object volatility,
            Object spreadBps,
            Object bidDepthUsdt,
            Object askDepthUsdt,
            Object estimatedSlippageBps,
            Object btcTrend,
            Object ethTrend,
            Object marketRiskLevel,
            Instant createdAt
    ) {
    }
}

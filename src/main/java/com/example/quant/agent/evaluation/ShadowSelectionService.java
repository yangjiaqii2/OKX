package com.example.quant.agent.evaluation;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.agent.gate.ContractTradeGate;
import com.example.quant.agent.selection.AiContractSelectionService;
import com.example.quant.agent.selection.ContractSelectionDecision;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShadowSelectionService {
    private static final List<String> POLICIES = List.of(
            "CURRENT_AI_OVERRIDE", "RULE_FIRST_AI_RANK", "RULE_ONLY", "LIMITED_AI_OVERRIDE");
    private final ShadowSelectionRepository repository;
    private final AiContractSelectionService aiSelection;
    private final AgentProperties properties;

    public ShadowSelectionService(ShadowSelectionRepository repository,
                                  AiContractSelectionService aiSelection,
                                  AgentProperties properties) {
        this.repository = repository;
        this.aiSelection = aiSelection;
        this.properties = properties;
    }

    @Transactional
    public void recordScan(String scanId, List<ContractCandidate> candidates, String executionVenue) {
        List<ContractCandidate> safe = candidates == null ? List.of() : candidates.stream()
                .filter(candidate -> ContractTradeGate.aiHardDenyReasons(candidate, properties).isEmpty()).toList();
        ContractSelectionDecision ai = aiSelection.select(safe);
        List<String> ranked = ai.rankedCandidates().stream()
                .map(ContractSelectionDecision.RankedCandidate::instId).toList();
        for (String policy : POLICIES) {
            List<ContractCandidate> eligible = eligible(policy, safe);
            String selected = select(policy, eligible, ranked);
            ShadowSelectionEntity row = new ShadowSelectionEntity();
            row.setUserName(AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER));
            row.setScanId(scanId);
            row.setPolicy(policy);
            row.setCandidateCount(candidates == null ? 0 : candidates.size());
            row.setEligibleCount(eligible.size());
            row.setSelectedInstId(selected);
            row.setSelected(selected != null);
            row.setReasonCode(selected == null ? "NO_ELIGIBLE_SELECTION" : "SHADOW_ONLY");
            row.setSignalVenue("PUBLIC_LIVE");
            row.setExecutionVenue(executionVenue == null ? "UNKNOWN" : executionVenue);
            row.setCreatedAt(Instant.now());
            repository.save(row);
        }
    }

    private List<ContractCandidate> eligible(String policy, List<ContractCandidate> candidates) {
        if ("CURRENT_AI_OVERRIDE".equals(policy)) return candidates;
        if ("LIMITED_AI_OVERRIDE".equals(policy)) {
            return candidates.stream().filter(candidate -> planDenials(candidate).size() <= 1).toList();
        }
        return candidates.stream().filter(candidate -> planDenials(candidate).isEmpty()).toList();
    }

    private List<String> planDenials(ContractCandidate candidate) {
        try {
            return ContractTradeGate.planDenyReasons(candidate, properties);
        } catch (RuntimeException ex) {
            return List.of("INVALID_CANDIDATE_FACTS");
        }
    }

    private static String select(String policy, List<ContractCandidate> eligible, List<String> ranked) {
        Set<String> ids = eligible.stream().map(ContractCandidate::instId)
                .collect(java.util.stream.Collectors.toSet());
        if (!"RULE_ONLY".equals(policy)) {
            String aiSelected = ranked.stream().filter(ids::contains).findFirst().orElse(null);
            if (aiSelected != null) return aiSelected;
        }
        return eligible.stream()
                .max(Comparator.comparing(ContractCandidate::finalRankScore,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ContractCandidate::instId).orElse(null);
    }

}

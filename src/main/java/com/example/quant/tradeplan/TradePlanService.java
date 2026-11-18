package com.example.quant.tradeplan;

import org.springframework.stereotype.Service;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.agent.selection.ContractSelectionDecision;

@Service
public class TradePlanService {
    private final ContractTradePlanBuilder builder;

    public TradePlanService(ContractTradePlanBuilder builder) {
        this.builder = builder;
    }

    public TradePlan createContractPlan(String instId) {
        return builder.buildPlan(instId);
    }

    public TradePlan createTopCandidateContractPlan() {
        return builder.buildTopCandidatePlan();
    }

    public TradePlan createContractPlan(ContractCandidate candidate) {
        return builder.buildPlan(candidate);
    }

    public TradePlan createNoRiskContractPlan(ContractCandidate candidate) {
        return builder.buildNoRiskPlan(candidate);
    }

    public TradePlan createSelectedContractPlan(ContractCandidate candidate, ContractSelectionDecision selection) {
        return builder.buildSelectedPlan(candidate, selection);
    }

    public TradePlan createRuleFallbackPlan(ContractCandidate candidate) {
        return builder.buildRuleFallbackPlan(candidate);
    }
}

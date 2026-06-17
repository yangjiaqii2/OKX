package com.example.quant.tradeplan;

import org.springframework.stereotype.Service;
import com.example.quant.crypto.dto.ContractCandidate;

@Service
public class TradePlanService {
    private final ContractTradePlanBuilder builder;

    public TradePlanService(ContractTradePlanBuilder builder) {
        this.builder = builder;
    }

    public TradePlan createContractPlan(String instId) {
        return builder.buildPlan(instId);
    }

    public TradePlan createContractPlan(ContractCandidate candidate) {
        return builder.buildPlan(candidate);
    }

    public TradePlan createNoRiskContractPlan(ContractCandidate candidate) {
        return builder.buildNoRiskPlan(candidate);
    }
}

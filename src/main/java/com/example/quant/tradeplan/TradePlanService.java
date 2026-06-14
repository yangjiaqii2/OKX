package com.example.quant.tradeplan;

import org.springframework.stereotype.Service;

@Service
public class TradePlanService {
    private final ContractTradePlanBuilder builder;

    public TradePlanService(ContractTradePlanBuilder builder) {
        this.builder = builder;
    }

    public TradePlan createContractPlan(String instId) {
        return builder.buildPlan(instId);
    }
}

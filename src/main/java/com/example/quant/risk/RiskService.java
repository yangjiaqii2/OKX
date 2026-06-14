package com.example.quant.risk;

public interface RiskService {
    RiskCheckResult check(ContractRiskRequest request);
}

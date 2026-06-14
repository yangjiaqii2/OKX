package com.example.quant.controller;

import com.example.quant.risk.ContractRiskRequest;
import com.example.quant.risk.RiskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/risk")
public class RiskController {
    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    @GetMapping("/status")
    public Object status() {
        return riskService.check(ContractRiskRequest.safeDefaults());
    }
}

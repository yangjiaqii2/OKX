package com.example.quant.controller;

import com.example.quant.crypto.ContractAnalysisService;
import com.example.quant.stock.StockAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/recommend")
public class RecommendController {
    private final StockAnalysisService stockAnalysisService;
    private final ContractAnalysisService contractAnalysisService;

    public RecommendController(StockAnalysisService stockAnalysisService, ContractAnalysisService contractAnalysisService) {
        this.stockAnalysisService = stockAnalysisService;
        this.contractAnalysisService = contractAnalysisService;
    }

    @GetMapping
    public Object recommend() {
        return java.util.Map.of(
                "stocks", stockAnalysisService.candidates(),
                "contracts", contractAnalysisService.candidates()
        );
    }
}

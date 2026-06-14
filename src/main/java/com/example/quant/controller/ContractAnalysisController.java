package com.example.quant.controller;

import com.example.quant.crypto.ContractAnalysisService;
import com.example.quant.crypto.OkxMarketService;
import com.example.quant.news.CryptoNewsService;
import com.example.quant.task.ContractMarketScanTask;
import com.example.quant.tradeplan.TradePlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/contract")
public class ContractAnalysisController {
    private final ContractAnalysisService contractAnalysisService;
    private final CryptoNewsService cryptoNewsService;
    private final ContractMarketScanTask contractMarketScanTask;
    private final TradePlanService tradePlanService;
    private final OkxMarketService okxMarketService;

    public ContractAnalysisController(ContractAnalysisService contractAnalysisService, CryptoNewsService cryptoNewsService,
                                      ContractMarketScanTask contractMarketScanTask, TradePlanService tradePlanService,
                                      OkxMarketService okxMarketService) {
        this.contractAnalysisService = contractAnalysisService;
        this.cryptoNewsService = cryptoNewsService;
        this.contractMarketScanTask = contractMarketScanTask;
        this.tradePlanService = tradePlanService;
        this.okxMarketService = okxMarketService;
    }

    @GetMapping("/candidates")
    public Object candidates() {
        return contractAnalysisService.candidates();
    }

    @GetMapping("/report")
    public Object report(@RequestParam String instId) {
        return contractAnalysisService.analyze(instId);
    }

    @GetMapping("/news")
    public Object news(@RequestParam String instId) {
        return cryptoNewsService.news(instId);
    }

    @GetMapping("/candles")
    public Object candles(@RequestParam String instId, @RequestParam(defaultValue = "15m") String bar) {
        return okxMarketService.candles(instId, bar);
    }

    @PostMapping("/scan")
    public Object scan() {
        return contractMarketScanTask.runOnce();
    }

    @PostMapping("/analyze")
    public Object analyze(@RequestParam String instId) {
        return contractAnalysisService.analyze(instId);
    }

    @PostMapping("/trade-plan")
    public Object tradePlan(@RequestParam String instId) {
        return tradePlanService.createContractPlan(instId);
    }
}

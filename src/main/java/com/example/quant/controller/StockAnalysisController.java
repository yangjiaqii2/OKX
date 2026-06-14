package com.example.quant.controller;

import com.example.quant.news.StockNewsService;
import com.example.quant.stock.StockAnalysisService;
import com.example.quant.task.StockMarketScanTask;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/stock")
public class StockAnalysisController {
    private final StockAnalysisService stockAnalysisService;
    private final StockNewsService stockNewsService;
    private final StockMarketScanTask stockMarketScanTask;

    public StockAnalysisController(StockAnalysisService stockAnalysisService, StockNewsService stockNewsService,
                                   StockMarketScanTask stockMarketScanTask) {
        this.stockAnalysisService = stockAnalysisService;
        this.stockNewsService = stockNewsService;
        this.stockMarketScanTask = stockMarketScanTask;
    }

    @GetMapping("/candidates")
    public Object candidates() {
        return stockAnalysisService.candidates();
    }

    @GetMapping("/report")
    public Object report(@RequestParam String symbol) {
        return stockAnalysisService.analyze(symbol);
    }

    @GetMapping("/news")
    public Object news(@RequestParam String symbol) {
        return stockNewsService.news(symbol);
    }

    @PostMapping("/scan")
    public Object scan() {
        return stockMarketScanTask.runOnce();
    }

    @PostMapping("/analyze")
    public Object analyze(@RequestParam String symbol) {
        return stockAnalysisService.analyze(symbol);
    }
}

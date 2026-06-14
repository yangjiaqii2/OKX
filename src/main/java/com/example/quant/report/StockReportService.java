package com.example.quant.report;

import com.example.quant.stock.StockAnalysisService;
import com.example.quant.stock.dto.StockAnalysisReport;
import org.springframework.stereotype.Service;

@Service
public class StockReportService {
    private final StockAnalysisService stockAnalysisService;

    public StockReportService(StockAnalysisService stockAnalysisService) {
        this.stockAnalysisService = stockAnalysisService;
    }

    public StockAnalysisReport report(String symbol) {
        return stockAnalysisService.analyze(symbol);
    }
}

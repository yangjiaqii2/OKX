package com.example.quant.stock;

import org.springframework.stereotype.Service;

@Service
public class StockNewsAnalysisService {
    public String status() {
        return "stock-news-analysis-ready";
    }
}

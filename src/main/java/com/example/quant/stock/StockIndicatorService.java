package com.example.quant.stock;

import org.springframework.stereotype.Service;

@Service
public class StockIndicatorService {
    public String status() {
        return "stock-indicator-adapter-ready";
    }
}

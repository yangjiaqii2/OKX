package com.example.quant.stock;

import org.springframework.stereotype.Service;

@Service
public class StockSectorService {
    public String status() {
        return "stock-sector-adapter-ready";
    }
}

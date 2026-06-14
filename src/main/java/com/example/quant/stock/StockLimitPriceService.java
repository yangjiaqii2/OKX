package com.example.quant.stock;

import org.springframework.stereotype.Service;

@Service
public class StockLimitPriceService {
    public String status() {
        return "stock-limit-price-ready";
    }
}

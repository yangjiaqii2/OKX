package com.example.quant.stock;

import org.springframework.stereotype.Service;

@Service
public class StockMoneyFlowService {
    public String status() {
        return "stock-money-flow-adapter-ready";
    }
}

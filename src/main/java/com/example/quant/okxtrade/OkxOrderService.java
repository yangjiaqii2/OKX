package com.example.quant.okxtrade;

import org.springframework.stereotype.Service;

@Service
public class OkxOrderService {
    public String status() {
        return "okx-order-adapter-ready";
    }
}

package com.example.quant.crypto;

import org.springframework.stereotype.Service;

@Service
public class FundingRateService {
    public String status() {
        return "funding-rate-adapter-ready";
    }
}

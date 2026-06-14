package com.example.quant.crypto;

import org.springframework.stereotype.Service;

@Service
public class ContractIndicatorService {
    public String status() {
        return "contract-indicator-ready";
    }
}

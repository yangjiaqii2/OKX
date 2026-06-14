package com.example.quant.crypto;

import org.springframework.stereotype.Service;

@Service
public class OpenInterestService {
    public String status() {
        return "open-interest-adapter-ready";
    }
}

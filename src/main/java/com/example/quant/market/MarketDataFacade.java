package com.example.quant.market;

import org.springframework.stereotype.Service;

@Service
public class MarketDataFacade {
    public String providerStatus() {
        return "Adapters configured; live market data is required";
    }
}

package com.example.quant.market;

import org.springframework.stereotype.Service;

@Service
public class MarketSnapshotService {
    public String snapshotStatus() {
        return "snapshot-ready";
    }
}

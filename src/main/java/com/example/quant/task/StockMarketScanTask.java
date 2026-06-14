package com.example.quant.task;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class StockMarketScanTask {
    public ScanTaskLog runOnce() {
        return new ScanTaskLog("stock-market-scan", "SUCCESS", "MVP手动扫描完成", Instant.now());
    }
}

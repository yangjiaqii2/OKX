package com.example.quant.task;

import com.example.quant.crypto.ContractAnalysisService;
import java.time.Instant;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ContractMarketScanTask {
    private final ContractAnalysisService contractAnalysisService;

    public ContractMarketScanTask(ContractAnalysisService contractAnalysisService) {
        this.contractAnalysisService = contractAnalysisService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        runOnce();
    }

    @Scheduled(fixedRateString = "PT1H", initialDelayString = "PT1H")
    public void runHourly() {
        runOnce();
    }

    public ScanTaskLog runOnce() {
        try {
            int size = contractAnalysisService.refreshCandidates().size();
            return new ScanTaskLog("contract-market-scan", "SUCCESS", "Contract signal scan completed, recommendations=" + size, Instant.now());
        } catch (RuntimeException ex) {
            return new ScanTaskLog("contract-market-scan", "FAILED", "Contract signal scan failed: " + compact(ex.getMessage()), Instant.now());
        }
    }

    private static String compact(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }
}

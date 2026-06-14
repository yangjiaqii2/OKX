package com.example.quant.report;

import com.example.quant.crypto.ContractAnalysisService;
import com.example.quant.crypto.dto.ContractAnalysisReport;
import org.springframework.stereotype.Service;

@Service
public class ContractReportService {
    private final ContractAnalysisService contractAnalysisService;

    public ContractReportService(ContractAnalysisService contractAnalysisService) {
        this.contractAnalysisService = contractAnalysisService;
    }

    public ContractAnalysisReport report(String instId) {
        return contractAnalysisService.analyze(instId);
    }
}

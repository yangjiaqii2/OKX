package com.example.quant.report;

import org.springframework.stereotype.Service;

@Service
public class AnalysisReportService {
    public String disclaimer() {
        return "所有分析报告必须包含风险提示和不确定性说明，不构成投资建议。";
    }
}

package com.example.quant.crypto;

import com.example.quant.crypto.dto.ContractAnalysisReport;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.score.ContractScoreCalculator;
import com.example.quant.score.ContractScoreInput;
import com.example.quant.score.ScoreDetail;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ContractAnalysisService {
    private final OkxContractScanner scanner;
    private final ContractScoreCalculator calculator;

    public ContractAnalysisService(OkxContractScanner scanner, ContractScoreCalculator calculator) {
        this.scanner = scanner;
        this.calculator = calculator;
    }

    public List<ContractCandidate> candidates() {
        return scanner.scan();
    }

    public ContractAnalysisReport analyze(String instId) {
        ContractCandidate candidate = scanner.scan().stream()
                .filter(item -> item.instId().equals(instId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("OKX did not return contract: " + instId));
        ScoreDetail score = calculator.calculate(new ContractScoreInput(
                80,
                candidate.volumeSpikeRatio(),
                candidate.fundingRate(),
                candidate.volatility(),
                1,
                0
        ));
        return new ContractAnalysisReport(
                candidate.instId(),
                score.score(),
                score.recommendLevel(),
                candidate.trendDirection(),
                "OKX实时ticker生成的合约分析，执行仍必须经过风控和用户确认。",
                score.reasonList(),
                score.riskList(),
                "可基于当前OKX价格生成待确认TradePlan。",
                "行情延迟、风险等级升高或用户未确认时失效。",
                Instant.now()
        );
    }
}

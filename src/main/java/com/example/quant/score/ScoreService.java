package com.example.quant.score;

import org.springframework.stereotype.Service;

@Service
public class ScoreService {
    private final StockScoreCalculator stockScoreCalculator;
    private final ContractScoreCalculator contractScoreCalculator;

    public ScoreService(StockScoreCalculator stockScoreCalculator, ContractScoreCalculator contractScoreCalculator) {
        this.stockScoreCalculator = stockScoreCalculator;
        this.contractScoreCalculator = contractScoreCalculator;
    }

    public ScoreDetail stock(StockScoreInput input) {
        return stockScoreCalculator.calculate(input);
    }

    public ScoreDetail contract(ContractScoreInput input) {
        return contractScoreCalculator.calculate(input);
    }
}

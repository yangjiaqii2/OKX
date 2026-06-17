package com.example.quant.crypto.dto;

public record ContractFactorScore(
        int trendScore,
        int volumeScore,
        int volatilityScore,
        int liquidityScore,
        int oiFundingScore,
        int marketEnvScore,
        int newsRiskScore
) {
    public int totalScore() {
        return trendScore
                + volumeScore
                + volatilityScore
                + liquidityScore
                + oiFundingScore
                + marketEnvScore
                + newsRiskScore;
    }
}

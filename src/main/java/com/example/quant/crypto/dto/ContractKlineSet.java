package com.example.quant.crypto.dto;

import java.util.List;

public record ContractKlineSet(
        List<ContractCandle> twentyMinute,
        List<ContractCandle> fiveMinute,
        List<ContractCandle> oneHour,
        List<ContractCandle> fourHour,
        List<ContractCandle> oneDay
) {
    public ContractKlineSet {
        twentyMinute = twentyMinute == null ? List.of() : List.copyOf(twentyMinute);
        fiveMinute = fiveMinute == null ? List.of() : List.copyOf(fiveMinute);
        oneHour = oneHour == null ? List.of() : List.copyOf(oneHour);
        fourHour = fourHour == null ? List.of() : List.copyOf(fourHour);
        oneDay = oneDay == null ? List.of() : List.copyOf(oneDay);
    }

    public static ContractKlineSet fromSingleInterval(List<ContractCandle> candles) {
        List<ContractCandle> safe = candles == null ? List.of() : candles;
        return new ContractKlineSet(safe, safe, safe, safe, List.of());
    }
}

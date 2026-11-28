package com.example.quant.ai;

public record LlmConnectionTestResult(
        boolean ok,
        String model,
        long elapsedMs,
        String message
) {
}

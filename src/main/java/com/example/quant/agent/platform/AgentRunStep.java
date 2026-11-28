package com.example.quant.agent.platform;

public record AgentRunStep(
        int stepNo,
        String stage,
        String status,
        String detail,
        long elapsedMs
) {
}

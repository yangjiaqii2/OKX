package com.example.quant.agent.platform;

import java.time.Instant;
import java.util.List;

public record AgentRunView(
        Long id,
        Long agentId,
        int agentVersionNo,
        String scanRunId,
        String runMode,
        String decision,
        String selectedInstId,
        String selectedDirection,
        String llmModel,
        String summaryReason,
        List<AgentRunStep> steps,
        long elapsedMs,
        Instant createdAt
) {
}

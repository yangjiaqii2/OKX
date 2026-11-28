package com.example.quant.agent.platform;

public record SaveAgentMemoryRequest(
        String memoryType,
        String instId,
        String content,
        Boolean pinned
) {
}

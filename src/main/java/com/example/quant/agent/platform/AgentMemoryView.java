package com.example.quant.agent.platform;

import java.time.Instant;

public record AgentMemoryView(
        Long id,
        Long agentId,
        String memoryType,
        String instId,
        String content,
        boolean pinned,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    public static AgentMemoryView from(AgentMemoryEntity entity) {
        return new AgentMemoryView(
                entity.getId(),
                entity.getAgentId(),
                entity.getMemoryType(),
                entity.getInstId(),
                entity.getContent(),
                entity.isPinned(),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

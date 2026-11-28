package com.example.quant.ai;

import java.time.Instant;

public record UserLlmProfileView(
        Long id,
        String name,
        String providerType,
        String baseUrl,
        String modelName,
        String maskedApiKey,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserLlmProfileView from(UserLlmProfileEntity entity) {
        return new UserLlmProfileView(
                entity.getId(),
                entity.getName(),
                entity.getProviderType(),
                entity.getBaseUrl(),
                entity.getModelName(),
                entity.getMaskedApiKey(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

package com.example.quant.auth;

import java.time.Instant;

public record AuthUserView(
        Long id,
        String username,
        AuthRole role,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt
) {
    static AuthUserView from(AuthUserEntity entity) {
        return new AuthUserView(
                entity.getId(),
                entity.getUsername(),
                entity.getRole(),
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastLoginAt()
        );
    }
}

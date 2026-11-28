package com.example.quant.auth;

import java.util.List;
import java.util.Optional;

public interface AuthUserRepository {
    Optional<AuthUserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    default Optional<AuthUserEntity> findByEmail(String email) {
        return Optional.empty();
    }

    default boolean existsByEmail(String email) {
        return false;
    }

    long count();

    AuthUserEntity save(AuthUserEntity entity);

    List<AuthUserEntity> findAllByOrderByCreatedAtAsc();
}

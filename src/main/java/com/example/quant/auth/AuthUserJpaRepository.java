package com.example.quant.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthUserJpaRepository extends JpaRepository<AuthUserEntity, Long> {
    Optional<AuthUserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AuthUserEntity> findAllByOrderByCreatedAtAsc();
}

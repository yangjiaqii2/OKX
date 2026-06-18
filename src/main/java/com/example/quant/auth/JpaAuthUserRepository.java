package com.example.quant.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaAuthUserRepository implements AuthUserRepository {
    private final AuthUserJpaRepository delegate;

    public JpaAuthUserRepository(AuthUserJpaRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<AuthUserEntity> findByUsername(String username) {
        return delegate.findByUsername(username);
    }

    @Override
    public boolean existsByUsername(String username) {
        return delegate.existsByUsername(username);
    }

    @Override
    public long count() {
        return delegate.count();
    }

    @Override
    public AuthUserEntity save(AuthUserEntity entity) {
        return delegate.save(entity);
    }

    @Override
    public List<AuthUserEntity> findAllByOrderByCreatedAtAsc() {
        return delegate.findAllByOrderByCreatedAtAsc();
    }
}

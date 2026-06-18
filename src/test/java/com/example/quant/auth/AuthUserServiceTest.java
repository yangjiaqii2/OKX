package com.example.quant.auth;

import com.example.quant.config.AuthProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthUserServiceTest {

    @Test
    void initializesDefaultAdminWithBcryptHashWhenNoUsersExist() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService service = new AuthUserService(
                repository,
                new Pbkdf2PasswordHasher(),
                new AuthProperties("admin", "admin123", 480)
        );

        service.initializeDefaultAdmin();

        AuthUserEntity admin = repository.findByUsername("admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(AuthRole.ADMIN);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getPasswordHash()).isNotEqualTo("admin123");
        assertThat(service.authenticate("admin", "admin123")).isPresent();
    }

    @Test
    void changeOwnPasswordRequiresOldPasswordAndInvalidatesPreviousPassword() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService service = seededService(repository);

        service.changeOwnPassword("admin", new ChangePasswordRequest("admin123", "newSecret123"));

        assertThat(service.authenticate("admin", "admin123")).isEmpty();
        assertThat(service.authenticate("admin", "newSecret123")).isPresent();
    }

    @Test
    void disabledUserCannotLogin() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService service = seededService(repository);
        service.createUser(new CreateUserRequest("operator", "pass12345", AuthRole.USER, true));
        service.setEnabled("operator", false);

        assertThat(service.authenticate("operator", "pass12345")).isEmpty();
    }

    @Test
    void nonAdminCannotCreateUsers() {
        InMemoryAuthUserRepository repository = new InMemoryAuthUserRepository();
        AuthUserService service = seededService(repository);
        service.createUser(new CreateUserRequest("operator", "pass12345", AuthRole.USER, true));

        assertThatThrownBy(() -> service.createUserAs("operator",
                new CreateUserRequest("other", "pass12345", AuthRole.USER, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("管理员");
    }

    private static AuthUserService seededService(InMemoryAuthUserRepository repository) {
        AuthUserService service = new AuthUserService(
                repository,
                new Pbkdf2PasswordHasher(),
                new AuthProperties("admin", "admin123", 480)
        );
        service.initializeDefaultAdmin();
        return service;
    }

    private static final class InMemoryAuthUserRepository implements AuthUserRepository {
        private final List<AuthUserEntity> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public Optional<AuthUserEntity> findByUsername(String username) {
            return rows.stream()
                    .filter(row -> row.getUsername().equals(username))
                    .findFirst();
        }

        @Override
        public boolean existsByUsername(String username) {
            return findByUsername(username).isPresent();
        }

        @Override
        public long count() {
            return rows.size();
        }

        @Override
        public AuthUserEntity save(AuthUserEntity entity) {
            if (entity.getId() == null) {
                entity.setId(nextId++);
                entity.setCreatedAt(Instant.now());
            }
            entity.setUpdatedAt(Instant.now());
            findByUsername(entity.getUsername()).ifPresent(rows::remove);
            rows.add(entity);
            return entity;
        }

        @Override
        public List<AuthUserEntity> findAllByOrderByCreatedAtAsc() {
            return List.copyOf(rows);
        }
    }
}

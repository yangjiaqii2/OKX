package com.example.quant.auth;

import com.example.quant.config.AuthProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AuthInterceptorTest {
    @Test
    void allowsLoginAndSessionWithoutToken() {
        AuthInterceptor interceptor = new AuthInterceptor(sessionService());

        assertThat(preHandle(interceptor, "POST", "/api/quant/auth/login", "")).isTrue();
        assertThat(preHandle(interceptor, "GET", "/api/quant/auth/session", "")).isTrue();
    }

    @Test
    void rejectsUserManagementWithoutToken() {
        AuthInterceptor interceptor = new AuthInterceptor(sessionService());
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = preHandle(interceptor, "GET", "/api/quant/auth/users", "", response);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void allowsUserManagementWithValidToken() {
        AuthSessionService sessionService = sessionService();
        AuthInterceptor interceptor = new AuthInterceptor(sessionService);
        String token = sessionService.login(new AuthLoginRequest("admin", "admin123")).token();

        assertThat(preHandle(interceptor, "GET", "/api/quant/auth/users", token)).isTrue();
    }

    @Test
    void exposesCurrentUsernameDuringAuthenticatedRequestAndClearsItAfterCompletion() {
        AuthSessionService sessionService = sessionService();
        AuthInterceptor interceptor = new AuthInterceptor(sessionService);
        String token = sessionService.login(new AuthLoginRequest("admin", "admin123")).token();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/quant/account/binding-status");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(AuthUserContext.currentUsername()).contains("admin");

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        assertThat(AuthUserContext.currentUsername()).isEmpty();
    }

    private static boolean preHandle(AuthInterceptor interceptor, String method, String uri, String token) {
        return preHandle(interceptor, method, uri, token, new MockHttpServletResponse());
    }

    private static boolean preHandle(
            AuthInterceptor interceptor,
            String method,
            String uri,
            String token,
            MockHttpServletResponse response
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (token != null && !token.isBlank()) {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return interceptor.preHandle(request, response, new Object());
    }

    private static AuthSessionService sessionService() {
        AuthProperties properties = new AuthProperties("admin", "admin123", 480);
        AuthUserService userService = new AuthUserService(
                new InMemoryAuthUserRepository(),
                new Pbkdf2PasswordHasher(),
                properties
        );
        userService.initializeDefaultAdmin();
        return new AuthSessionService(
                userService,
                properties,
                Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneOffset.UTC)
        );
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

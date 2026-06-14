package com.example.quant.auth;

import com.example.quant.config.AuthProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuthSessionService {
    private final AuthProperties authProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Autowired
    public AuthSessionService(AuthProperties authProperties) {
        this(authProperties, Clock.systemUTC());
    }

    AuthSessionService(AuthProperties authProperties, Clock clock) {
        this.authProperties = authProperties;
        this.clock = clock;
    }

    public AuthSessionResponse login(AuthLoginRequest request) {
        if (request == null || !authProperties.effectiveUsername().equals(trim(request.username()))
                || !authProperties.effectivePassword().equals(request.password())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        cleanupExpired();
        String token = newToken();
        Instant expiresAt = clock.instant().plusSeconds(authProperties.effectiveSessionTtlMinutes() * 60L);
        sessions.put(token, new Session(authProperties.effectiveUsername(), expiresAt));
        return new AuthSessionResponse(true, token, authProperties.effectiveUsername(), expiresAt.toEpochMilli());
    }

    public AuthSessionResponse session(String token) {
        Session session = validSession(token);
        if (session == null) {
            return AuthSessionResponse.unauthenticated();
        }
        return new AuthSessionResponse(true, token, session.username(), session.expiresAt().toEpochMilli());
    }

    public boolean isAuthenticated(String token) {
        return validSession(token) != null;
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private Session validSession(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    private void cleanupExpired() {
        Instant now = clock.instant();
        Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().getValue().expiresAt().isAfter(now)) {
                iterator.remove();
            }
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record Session(String username, Instant expiresAt) {
    }
}

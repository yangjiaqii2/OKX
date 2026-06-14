package com.example.quant.auth;

import com.example.quant.config.AuthProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthSessionServiceTest {
    @Test
    void loginCreatesUsableSessionToken() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-14T00:00:00Z"));
        AuthSessionService service = new AuthSessionService(new AuthProperties("admin", "secret", 30), clock);

        var response = service.login(new AuthLoginRequest("admin", "secret"));

        assertTrue(response.authenticated());
        assertTrue(response.token().length() > 20);
        assertTrue(service.isAuthenticated(response.token()));
    }

    @Test
    void rejectsInvalidPassword() {
        AuthSessionService service = new AuthSessionService(
                new AuthProperties("admin", "secret", 30),
                Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC)
        );

        assertThrows(IllegalArgumentException.class, () -> service.login(new AuthLoginRequest("admin", "wrong")));
    }

    @Test
    void expiredSessionIsNotAuthenticated() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-14T00:00:00Z"));
        AuthSessionService service = new AuthSessionService(new AuthProperties("admin", "secret", 1), clock);
        var response = service.login(new AuthLoginRequest("admin", "secret"));

        clock.advance(Duration.ofMinutes(2));

        assertFalse(service.isAuthenticated(response.token()));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

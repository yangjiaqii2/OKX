package com.example.quant.auth;

import java.util.Optional;
import java.util.function.Supplier;

public final class AuthUserContext {
    private static final ThreadLocal<String> CURRENT_USERNAME = new ThreadLocal<>();

    private AuthUserContext() {
    }

    public static Optional<String> currentUsername() {
        String username = CURRENT_USERNAME.get();
        return username == null || username.isBlank() ? Optional.empty() : Optional.of(username);
    }

    public static void setCurrentUsername(String username) {
        if (username == null || username.isBlank()) {
            CURRENT_USERNAME.remove();
            return;
        }
        CURRENT_USERNAME.set(username.trim());
    }

    public static void clear() {
        CURRENT_USERNAME.remove();
    }

    public static void runAs(String username, Runnable runnable) {
        callAs(username, () -> {
            runnable.run();
            return null;
        });
    }

    public static <T> T callAs(String username, Supplier<T> supplier) {
        String previous = CURRENT_USERNAME.get();
        setCurrentUsername(username);
        try {
            return supplier.get();
        } finally {
            if (previous == null || previous.isBlank()) {
                CURRENT_USERNAME.remove();
            } else {
                CURRENT_USERNAME.set(previous);
            }
        }
    }
}

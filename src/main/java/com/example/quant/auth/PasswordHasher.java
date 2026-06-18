package com.example.quant.auth;

public interface PasswordHasher {
    String hash(String rawPassword);

    boolean matches(String rawPassword, String encodedPassword);
}

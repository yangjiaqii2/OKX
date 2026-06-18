package com.example.quant.auth;

public record AuthSessionResponse(
        boolean authenticated,
        String token,
        String username,
        long expiresAtEpochMillis,
        String role
) {
    public static AuthSessionResponse unauthenticated() {
        return new AuthSessionResponse(false, "", "", 0, "");
    }
}

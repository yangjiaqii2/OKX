package com.example.quant.auth;

public record AuthRegisterRequest(
        String username,
        String password,
        String inviteCode,
        String email,
        String emailCode
) {
    public AuthRegisterRequest(String username, String password, String inviteCode) {
        this(username, password, inviteCode, null, null);
    }
}

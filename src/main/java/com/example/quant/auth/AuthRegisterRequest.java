package com.example.quant.auth;

public record AuthRegisterRequest(
        String username,
        String password,
        String inviteCode
) {
}

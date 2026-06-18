package com.example.quant.auth;

public record CreateUserRequest(
        String username,
        String password,
        AuthRole role,
        Boolean enabled
) {
}

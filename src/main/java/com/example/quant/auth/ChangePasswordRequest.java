package com.example.quant.auth;

public record ChangePasswordRequest(
        String oldPassword,
        String newPassword
) {
}

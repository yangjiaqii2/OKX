package com.example.quant.auth;

public record ForgotPasswordResetRequest(
        String email,
        String emailCode,
        String newPassword
) {
}

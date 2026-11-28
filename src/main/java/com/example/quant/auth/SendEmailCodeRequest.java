package com.example.quant.auth;

public record SendEmailCodeRequest(
        String email,
        String purpose
) {
}

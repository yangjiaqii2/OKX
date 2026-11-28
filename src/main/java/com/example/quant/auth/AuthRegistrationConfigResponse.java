package com.example.quant.auth;

public record AuthRegistrationConfigResponse(
        boolean registrationEnabled,
        boolean inviteCodeRequired
) {
}

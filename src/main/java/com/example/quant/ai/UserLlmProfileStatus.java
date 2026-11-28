package com.example.quant.ai;

public record UserLlmProfileStatus(
        boolean configured,
        Long activeProfileId,
        String activeProfileName,
        String activeModelName,
        String activeMaskedApiKey,
        int profileCount
) {
    public static UserLlmProfileStatus empty() {
        return new UserLlmProfileStatus(false, null, null, null, null, 0);
    }
}

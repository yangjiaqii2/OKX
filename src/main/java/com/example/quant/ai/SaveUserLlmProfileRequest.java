package com.example.quant.ai;

public record SaveUserLlmProfileRequest(
        Long id,
        String name,
        String baseUrl,
        String modelName,
        String apiKey,
        Boolean active
) {
}

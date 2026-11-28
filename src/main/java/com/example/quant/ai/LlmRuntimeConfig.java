package com.example.quant.ai;

public record LlmRuntimeConfig(
        String baseUrl,
        String apiKey,
        String modelName
) {
}

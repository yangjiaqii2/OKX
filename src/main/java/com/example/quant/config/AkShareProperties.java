package com.example.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "akshare")
public record AkShareProperties(String baseUrl) {
}

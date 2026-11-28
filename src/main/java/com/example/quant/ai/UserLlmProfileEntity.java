package com.example.quant.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "quant_user_llm_profile")
public class UserLlmProfileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "provider_type", nullable = false, length = 32)
    private String providerType = "OPENAI_COMPATIBLE";

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "api_key_encoded", nullable = false, length = 1024)
    private String apiKeyEncoded;

    @Column(name = "masked_api_key", nullable = false, length = 128)
    private String maskedApiKey;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getApiKeyEncoded() {
        return apiKeyEncoded;
    }

    public void setApiKeyEncoded(String apiKeyEncoded) {
        this.apiKeyEncoded = apiKeyEncoded;
    }

    public String getMaskedApiKey() {
        return maskedApiKey;
    }

    public void setMaskedApiKey(String maskedApiKey) {
        this.maskedApiKey = maskedApiKey;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

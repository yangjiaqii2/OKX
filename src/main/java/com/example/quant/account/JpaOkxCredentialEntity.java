package com.example.quant.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "quant_okx_credential")
public class JpaOkxCredentialEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;

    @Column(name = "api_key_encoded", nullable = false, length = 2048)
    private String apiKeyEncoded;

    @Column(name = "secret_encoded", nullable = false, length = 2048)
    private String secretEncoded;

    @Column(name = "passphrase_encoded", nullable = false, length = 2048)
    private String passphraseEncoded;

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

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getApiKeyEncoded() {
        return apiKeyEncoded;
    }

    public void setApiKeyEncoded(String apiKeyEncoded) {
        this.apiKeyEncoded = apiKeyEncoded;
    }

    public String getSecretEncoded() {
        return secretEncoded;
    }

    public void setSecretEncoded(String secretEncoded) {
        this.secretEncoded = secretEncoded;
    }

    public String getPassphraseEncoded() {
        return passphraseEncoded;
    }

    public void setPassphraseEncoded(String passphraseEncoded) {
        this.passphraseEncoded = passphraseEncoded;
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

CREATE TABLE IF NOT EXISTS quant_okx_credential (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_name VARCHAR(128) NOT NULL,
    api_key_encoded VARCHAR(2048) NOT NULL,
    secret_encoded VARCHAR(2048) NOT NULL,
    passphrase_encoded VARCHAR(2048) NOT NULL,
    masked_api_key VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_quant_okx_credential_active (active, updated_at)
);

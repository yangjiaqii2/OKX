CREATE TABLE IF NOT EXISTS quant_user_llm_profile (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_name VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    provider_type VARCHAR(32) NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
    base_url VARCHAR(512) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    api_key_encoded VARCHAR(1024) NOT NULL,
    masked_api_key VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_llm_profile_user_active ON quant_user_llm_profile (user_name, active);

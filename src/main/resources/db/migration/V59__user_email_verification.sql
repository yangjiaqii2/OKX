ALTER TABLE quant_auth_user ADD COLUMN email VARCHAR(255) NULL;
CREATE UNIQUE INDEX idx_auth_user_email ON quant_auth_user (email);

CREATE TABLE IF NOT EXISTS quant_email_verification_code (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    code VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_email_code_lookup ON quant_email_verification_code (email, purpose, used, created_at);

CREATE TABLE IF NOT EXISTS quant_auth_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP NULL,
    UNIQUE KEY uk_quant_auth_user_username (username),
    INDEX idx_quant_auth_user_enabled (enabled),
    INDEX idx_quant_auth_user_role (role)
);

CREATE TABLE IF NOT EXISTS exchange_position_state (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_name VARCHAR(128) NOT NULL,
    inst_id VARCHAR(64) NOT NULL,
    pos_side VARCHAR(16) NOT NULL,
    size_value DECIMAL(30, 12),
    avg_px DECIMAL(30, 12),
    unrealized_pnl DECIMAL(30, 12),
    ownership VARCHAR(16) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_exchange_position_owner (user_name, inst_id, pos_side),
    INDEX idx_exchange_position_ownership (user_name, ownership)
);

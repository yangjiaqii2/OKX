CREATE TABLE IF NOT EXISTS system_control_state (
    id VARCHAR(32) PRIMARY KEY,
    emergency_stop BOOLEAN NOT NULL DEFAULT FALSE,
    auto_trade_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    auto_trade_owner_username VARCHAR(128) NOT NULL DEFAULT 'local-admin',
    auto_trade_risk_mode VARCHAR(32) NOT NULL DEFAULT 'STRICT',
    auto_trade_margin_usdt DECIMAL(30, 12) DEFAULT 0,
    no_risk_min_score INT NOT NULL DEFAULT 70,
    auto_trade_min_leverage INT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS auto_trade_budget_reservation (
    reservation_id VARCHAR(36) PRIMARY KEY,
    plan_id VARCHAR(36),
    pending_order_id VARCHAR(36),
    symbol VARCHAR(64) NOT NULL,
    amount DECIMAL(30, 12),
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_auto_trade_budget_status (status),
    INDEX idx_auto_trade_budget_pending_order (pending_order_id),
    INDEX idx_auto_trade_budget_symbol_status (symbol, status)
);

CREATE TABLE IF NOT EXISTS close_position_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_name VARCHAR(128) NOT NULL,
    inst_id VARCHAR(64) NOT NULL,
    pos_side VARCHAR(16),
    margin_mode VARCHAR(16),
    close_order_id VARCHAR(128),
    close_cl_ord_id VARCHAR(64),
    size_value DECIMAL(30, 12),
    avg_px DECIMAL(30, 12),
    realized_pnl DECIMAL(30, 12),
    fee DECIMAL(30, 12),
    funding_fee DECIMAL(30, 12),
    status VARCHAR(32) NOT NULL,
    source VARCHAR(16) NOT NULL,
    auto_trade_record_id BIGINT,
    pending_order_id VARCHAR(36),
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_close_position_user_created_at (user_name, created_at),
    INDEX idx_close_position_inst_status (inst_id, status),
    INDEX idx_close_position_pending_order (pending_order_id)
);

ALTER TABLE auto_trade_record ADD COLUMN scan_id VARCHAR(64);
ALTER TABLE auto_trade_record ADD COLUMN final_rank_score DECIMAL(18, 8);
ALTER TABLE auto_trade_record ADD COLUMN signal_type VARCHAR(64);
ALTER TABLE auto_trade_record ADD COLUMN risk_mode VARCHAR(32);
ALTER TABLE auto_trade_record ADD COLUMN stage VARCHAR(64);
ALTER TABLE auto_trade_record ADD COLUMN reason_code VARCHAR(128);
ALTER TABLE auto_trade_record ADD COLUMN reason_message TEXT;
ALTER TABLE auto_trade_record ADD COLUMN fallback_allowed BOOLEAN;
ALTER TABLE auto_trade_record ADD COLUMN updated_at TIMESTAMP;

ALTER TABLE trade_order ADD COLUMN trigger_px DECIMAL(30, 12);
ALTER TABLE trade_order ADD COLUMN lifecycle_status VARCHAR(64);
ALTER TABLE trade_order ADD COLUMN next_action VARCHAR(128);

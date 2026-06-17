CREATE TABLE IF NOT EXISTS trade_plan (
    id VARCHAR(36) PRIMARY KEY,
    scan_run_id BIGINT,
    inst_id VARCHAR(64) NOT NULL,
    direction VARCHAR(32) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    entry_price DECIMAL(30, 12),
    entry_zone_low DECIMAL(30, 12),
    entry_zone_high DECIMAL(30, 12),
    leverage INT,
    position_notional DECIMAL(30, 12),
    margin_required DECIMAL(30, 12),
    stop_loss DECIMAL(30, 12),
    take_profit DECIMAL(30, 12),
    risk_reward_ratio DECIMAL(18, 8),
    max_loss_usdt DECIMAL(30, 12),
    max_loss_percent DECIMAL(18, 8),
    allow_trade BOOLEAN NOT NULL DEFAULT FALSE,
    deny_reason TEXT,
    plan_json TEXT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at TIMESTAMP,
    INDEX idx_trade_plan_inst_id (inst_id),
    INDEX idx_trade_plan_status (status),
    INDEX idx_trade_plan_created_at (created_at)
);

CREATE TABLE IF NOT EXISTS trade_take_profit_plan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trade_plan_id VARCHAR(36) NOT NULL,
    level_no INT NOT NULL,
    price DECIMAL(30, 12) NOT NULL,
    position_percent DECIMAL(8, 4) NOT NULL,
    condition_text VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_trade_take_profit_trade_plan_id (trade_plan_id)
);

CREATE TABLE IF NOT EXISTS quant_trading_agent (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_name VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    template_code VARCHAR(32) NOT NULL DEFAULT 'TREND_BALANCED',
    status VARCHAR(32) NOT NULL DEFAULT 'PAUSED',
    run_mode VARCHAR(32) NOT NULL DEFAULT 'PAPER',
    llm_profile_id BIGINT NULL,
    allocated_budget_usdt DECIMAL(18, 4) NOT NULL DEFAULT 100.0000,
    max_open_positions INT NOT NULL DEFAULT 2,
    max_leverage INT NOT NULL DEFAULT 5,
    min_total_score INT NOT NULL DEFAULT 60,
    min_final_rank_score INT NOT NULL DEFAULT 60,
    max_single_loss_pct DECIMAL(6, 3) NOT NULL DEFAULT 1.500,
    max_daily_loss_pct DECIMAL(6, 3) NOT NULL DEFAULT 4.000,
    direction_preference VARCHAR(16) NOT NULL DEFAULT 'BOTH',
    symbol_whitelist VARCHAR(512) NOT NULL DEFAULT '',
    symbol_blacklist VARCHAR(512) NOT NULL DEFAULT '',
    system_prompt TEXT,
    version_no INT NOT NULL DEFAULT 1,
    last_decision VARCHAR(32),
    last_summary VARCHAR(512),
    last_error_code VARCHAR(128),
    last_run_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_trading_agent_user_status ON quant_trading_agent (user_name, status);
CREATE INDEX idx_trading_agent_status ON quant_trading_agent (status);

CREATE TABLE IF NOT EXISTS quant_agent_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    user_name VARCHAR(128) NOT NULL,
    agent_version_no INT NOT NULL DEFAULT 1,
    scan_run_id VARCHAR(64),
    run_mode VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    selected_inst_id VARCHAR(64),
    selected_direction VARCHAR(16),
    llm_model VARCHAR(128),
    summary_reason VARCHAR(512),
    steps_json TEXT,
    elapsed_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_run_agent_created ON quant_agent_run (agent_id, created_at);
CREATE INDEX idx_agent_run_user_created ON quant_agent_run (user_name, created_at);

ALTER TABLE trade_order ADD COLUMN agent_id BIGINT NULL;
ALTER TABLE pending_order_state ADD COLUMN agent_id BIGINT NULL;
ALTER TABLE auto_trade_record ADD COLUMN agent_id BIGINT NULL;
ALTER TABLE auto_trade_budget_reservation ADD COLUMN agent_id BIGINT NULL;

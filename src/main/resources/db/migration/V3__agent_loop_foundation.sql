CREATE TABLE IF NOT EXISTS agent_audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type VARCHAR(128) NOT NULL,
    entity_type VARCHAR(128),
    entity_id VARCHAR(128),
    level VARCHAR(32) NOT NULL,
    message TEXT,
    payload_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_agent_audit_log_created_at (created_at),
    INDEX idx_agent_audit_log_event_type (event_type)
);

CREATE TABLE IF NOT EXISTS agent_scan_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    message TEXT,
    market_risk VARCHAR(32),
    btc_trend VARCHAR(32),
    eth_trend VARCHAR(32),
    candidate_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_agent_scan_run_started_at (started_at),
    INDEX idx_agent_scan_run_status (status)
);

CREATE TABLE IF NOT EXISTS agent_contract_candidate_score (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    scan_run_id BIGINT,
    inst_id VARCHAR(64) NOT NULL,
    direction VARCHAR(32),
    total_score INT NOT NULL,
    trend_score INT NOT NULL,
    volume_score INT NOT NULL,
    volatility_score INT NOT NULL,
    liquidity_score INT NOT NULL,
    oi_funding_score INT NOT NULL,
    market_env_score INT NOT NULL,
    news_risk_score INT NOT NULL,
    allow_trade BOOLEAN NOT NULL DEFAULT FALSE,
    deny_reason TEXT,
    reason_json TEXT,
    risk_json TEXT,
    raw_market_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_agent_candidate_score_scan_run (scan_run_id),
    INDEX idx_agent_candidate_score_inst_id (inst_id),
    INDEX idx_agent_candidate_score_total_score (total_score)
);

CREATE TABLE IF NOT EXISTS quant_stock_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    price DECIMAL(32, 10),
    change_percent DECIMAL(16, 8),
    turnover_amount DECIMAL(32, 8),
    volume DECIMAL(32, 8),
    volume_ratio DECIMAL(16, 8),
    turnover_rate DECIMAL(16, 8),
    snapshot_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_contract_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    inst_id VARCHAR(64) NOT NULL,
    last_price DECIMAL(32, 10),
    change_percent_24h DECIMAL(16, 8),
    change_percent_5m DECIMAL(16, 8),
    volume_24h DECIMAL(32, 8),
    funding_rate DECIMAL(16, 10),
    open_interest DECIMAL(32, 8),
    snapshot_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_stock_candidate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_type VARCHAR(32) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    price DECIMAL(32, 10),
    change_percent DECIMAL(16, 8),
    turnover_amount DECIMAL(32, 8),
    volume DECIMAL(32, 8),
    volume_ratio DECIMAL(16, 8),
    turnover_rate DECIMAL(16, 8),
    sector VARCHAR(128),
    concept_json JSON,
    ma5 DECIMAL(32, 10),
    ma10 DECIMAL(32, 10),
    ma20 DECIMAL(32, 10),
    rsi DECIMAL(16, 8),
    macd_signal VARCHAR(64),
    money_flow DECIMAL(32, 8),
    limit_up_price DECIMAL(32, 10),
    limit_down_price DECIMAL(32, 10),
    is_limit_up BOOLEAN DEFAULT FALSE,
    is_limit_down BOOLEAN DEFAULT FALSE,
    is_broken_limit_up BOOLEAN DEFAULT FALSE,
    consecutive_limit_up_count INT DEFAULT 0,
    is_st BOOLEAN DEFAULT FALSE,
    is_suspended BOOLEAN DEFAULT FALSE,
    candidate_reason_json JSON,
    risk_tag_json JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_contract_candidate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_type VARCHAR(32) NOT NULL,
    inst_id VARCHAR(64) NOT NULL,
    base_currency VARCHAR(32),
    quote_currency VARCHAR(32),
    last_price DECIMAL(32, 10),
    change_percent_24h DECIMAL(16, 8),
    change_percent_5m DECIMAL(16, 8),
    volume_24h DECIMAL(32, 8),
    volume_spike_ratio DECIMAL(16, 8),
    funding_rate DECIMAL(16, 10),
    open_interest DECIMAL(32, 8),
    open_interest_change DECIMAL(16, 8),
    trend_direction VARCHAR(32),
    volatility DECIMAL(16, 8),
    ma5 DECIMAL(32, 10),
    ma20 DECIMAL(32, 10),
    rsi DECIMAL(16, 8),
    macd_signal VARCHAR(64),
    candidate_reason_json JSON,
    risk_tag_json JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_news_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_type VARCHAR(32) NOT NULL,
    symbol VARCHAR(64),
    title VARCHAR(512) NOT NULL,
    source VARCHAR(128) NOT NULL,
    url VARCHAR(1024),
    publish_time TIMESTAMP,
    content_summary TEXT,
    sentiment VARCHAR(32),
    sentiment_score INT,
    importance_score INT,
    related_symbols_json JSON,
    risk_keywords_json JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_news_item (title, source, publish_time)
);

CREATE TABLE IF NOT EXISTS quant_news_sentiment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    news_item_id BIGINT,
    sentiment VARCHAR(32),
    sentiment_score INT,
    risk_keywords_json JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_analysis_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_type VARCHAR(32) NOT NULL,
    symbol VARCHAR(64),
    name VARCHAR(128),
    inst_id VARCHAR(64),
    score INT,
    recommend_level VARCHAR(64),
    direction_bias VARCHAR(32),
    summary TEXT,
    reason_json JSON,
    risk_json JSON,
    news_summary_json JSON,
    trade_plan_summary TEXT,
    invalid_condition TEXT,
    suggestion VARCHAR(128),
    generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_score_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_type VARCHAR(32) NOT NULL,
    symbol VARCHAR(64),
    inst_id VARCHAR(64),
    score INT,
    factor_json JSON,
    reason_json JSON,
    risk_json JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_contract_trade_plan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    inst_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    direction_bias VARCHAR(32),
    confidence DECIMAL(16, 8),
    order_type VARCHAR(32),
    entry_price DECIMAL(32, 10),
    stop_loss_price DECIMAL(32, 10),
    take_profit_price DECIMAL(32, 10),
    suggested_leverage INT,
    suggested_size DECIMAL(32, 10),
    suggested_margin DECIMAL(32, 10),
    max_loss_amount DECIMAL(32, 10),
    risk_reward_ratio DECIMAL(16, 8),
    td_mode VARCHAR(32),
    reason_json JSON,
    risk_json JSON,
    invalid_condition TEXT,
    status VARCHAR(32),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_pending_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_type VARCHAR(32) NOT NULL,
    inst_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    side VARCHAR(16),
    pos_side VARCHAR(16),
    order_type VARCHAR(32),
    price DECIMAL(32, 10),
    size DECIMAL(32, 10),
    leverage INT,
    td_mode VARCHAR(32),
    stop_loss_price DECIMAL(32, 10),
    take_profit_price DECIMAL(32, 10),
    max_loss_amount DECIMAL(32, 10),
    risk_reward_ratio DECIMAL(16, 8),
    trade_plan_id BIGINT,
    risk_check_id BIGINT,
    confirm_token VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    user_confirmed BOOLEAN DEFAULT FALSE,
    confirmed_at TIMESTAMP,
    executed_at TIMESTAMP,
    reject_reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_order_execution (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    pending_order_id BIGINT,
    market_type VARCHAR(32) NOT NULL,
    inst_id VARCHAR(64) NOT NULL,
    external_order_id VARCHAR(128),
    request_json JSON,
    response_json JSON,
    executed_price DECIMAL(32, 10),
    executed_size DECIMAL(32, 10),
    status VARCHAR(32),
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_risk_check (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_type VARCHAR(32),
    symbol VARCHAR(64),
    inst_id VARCHAR(64),
    trade_plan_id BIGINT,
    pending_order_id BIGINT,
    passed BOOLEAN NOT NULL,
    risk_level VARCHAR(32),
    reject_code VARCHAR(64),
    reject_reason TEXT,
    warning_json JSON,
    adjusted_json JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_account_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    equity DECIMAL(32, 10),
    available_balance DECIMAL(32, 10),
    risk_exposure_json JSON,
    snapshot_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_position_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    inst_id VARCHAR(64),
    pos_side VARCHAR(16),
    size DECIMAL(32, 10),
    avg_price DECIMAL(32, 10),
    unrealized_pnl DECIMAL(32, 10),
    snapshot_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_scan_task_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_system_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type VARCHAR(128) NOT NULL,
    event_level VARCHAR(32),
    message TEXT,
    payload_json JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_system_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(128) NOT NULL UNIQUE,
    config_value TEXT,
    description VARCHAR(512),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

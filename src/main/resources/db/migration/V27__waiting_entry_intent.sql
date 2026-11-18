CREATE TABLE waiting_entry_intent (
    intent_id VARCHAR(36) PRIMARY KEY,
    decision_client_order_id VARCHAR(32) NOT NULL,
    active_key VARCHAR(320),
    user_name VARCHAR(128) NOT NULL,
    strategy_id VARCHAR(64) NOT NULL,
    inst_id VARCHAR(64) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    wait_type VARCHAR(32) NOT NULL,
    reference_price DECIMAL(30, 12) NOT NULL,
    invalidation_price DECIMAL(30, 12) NOT NULL,
    decision_candle_ts TIMESTAMP(6) NOT NULL,
    expire_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trigger_count INT NOT NULL DEFAULT 0,
    reason VARCHAR(240),
    pending_order_id VARCHAR(36),
    okx_order_id VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_waiting_entry_decision UNIQUE (decision_client_order_id),
    CONSTRAINT uk_waiting_entry_active UNIQUE (active_key)
);

CREATE INDEX idx_waiting_entry_owner_status ON waiting_entry_intent(user_name, status, created_at);


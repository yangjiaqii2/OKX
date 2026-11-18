CREATE TABLE strategy_decision (
    decision_id VARCHAR(36) PRIMARY KEY,
    user_name VARCHAR(128) NOT NULL,
    strategy_id VARCHAR(64) NOT NULL,
    inst_id VARCHAR(64) NOT NULL,
    timeframe VARCHAR(16) NOT NULL,
    candle_ts TIMESTAMP(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    client_order_id VARCHAR(32) NOT NULL,
    pending_order_id VARCHAR(36),
    okx_order_id VARCHAR(128),
    reason VARCHAR(240),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_strategy_decision_candle UNIQUE (user_name, strategy_id, inst_id, timeframe, candle_ts),
    CONSTRAINT uk_strategy_decision_client_order UNIQUE (client_order_id)
);

CREATE INDEX idx_strategy_decision_recovery ON strategy_decision(status, updated_at);

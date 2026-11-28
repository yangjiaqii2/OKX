CREATE TABLE IF NOT EXISTS quant_agent_memory (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    user_name VARCHAR(128) NOT NULL,
    memory_type VARCHAR(32) NOT NULL DEFAULT 'LESSON',
    inst_id VARCHAR(64) NULL,
    content VARCHAR(1000) NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_memory_agent_enabled ON quant_agent_memory (agent_id, enabled, pinned);

ALTER TABLE quant_trading_agent ADD COLUMN tool_calling_enabled BOOLEAN NOT NULL DEFAULT TRUE;

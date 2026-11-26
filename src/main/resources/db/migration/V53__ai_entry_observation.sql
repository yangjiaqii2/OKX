CREATE TABLE ai_entry_observation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    scope VARCHAR(16) NOT NULL,
    user_name VARCHAR(128) NOT NULL,
    scan_id VARCHAR(64),
    observed_at TIMESTAMP(6) NOT NULL,
    inst_id VARCHAR(64),
    reason_code VARCHAR(48) NOT NULL,
    model_requested VARCHAR(128),
    model_reported VARCHAR(128),
    prompt_version VARCHAR(64),
    threshold_version VARCHAR(64),
    input_sha256 VARCHAR(64),
    input_json LONGTEXT,
    decision_json LONGTEXT NOT NULL
);

CREATE INDEX idx_ai_entry_obs_summary ON ai_entry_observation (user_name, scope, observed_at);
CREATE INDEX idx_ai_entry_obs_scan ON ai_entry_observation (scan_id, inst_id);

ALTER TABLE auto_trade_record ADD COLUMN ai_observation_id VARCHAR(36);
ALTER TABLE auto_trade_record ADD COLUMN ai_rejection_code VARCHAR(48);

CREATE TABLE ai_entry_counterfactual (
    observation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_name VARCHAR(128) NOT NULL,
    inst_id VARCHAR(64) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    bucket_5m BIGINT NOT NULL,
    reference_price DECIMAL(30, 12) NOT NULL,
    reference_at TIMESTAMP(6),
    skipped_at TIMESTAMP(6) NOT NULL,
    next_check_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    outcome_5m TEXT,
    outcome_15m TEXT,
    outcome_30m TEXT,
    CONSTRAINT uq_ai_entry_cf_window UNIQUE (user_name, inst_id, direction, bucket_5m),
    CONSTRAINT fk_ai_entry_cf_observation FOREIGN KEY (observation_id) REFERENCES ai_entry_observation (id)
);

CREATE INDEX idx_ai_entry_cf_due ON ai_entry_counterfactual (status, next_check_at);

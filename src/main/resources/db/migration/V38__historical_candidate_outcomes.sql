-- Wave 5: immutable V2 candidate evidence and durable future outcomes.
ALTER TABLE agent_contract_candidate_score ADD COLUMN snapshot_rank INT;
ALTER TABLE agent_contract_candidate_score ADD COLUMN reference_price DECIMAL(18, 12);
ALTER TABLE agent_contract_candidate_score ADD COLUMN scoring_version VARCHAR(32);
ALTER TABLE agent_contract_candidate_score ADD COLUMN v2_normalized_score DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN v2_ranking_score DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN v2_available_weight DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN v2_coverage_ratio DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN capital_flow_score DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN derivatives_score DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN relative_strength_score DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN market_structure_score DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN volume_quality_score DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN breakout_score DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN v2_news_score DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN oi_data_quality VARCHAR(24);
ALTER TABLE agent_contract_candidate_score ADD COLUMN funding_data_quality VARCHAR(24);
ALTER TABLE agent_contract_candidate_score ADD COLUMN relative_strength_data_quality VARCHAR(24);
ALTER TABLE agent_contract_candidate_score ADD COLUMN breakout_data_quality VARCHAR(24);
ALTER TABLE agent_contract_candidate_score ADD COLUMN snapshot_volume_24h DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN snapshot_open_interest DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN oi_change_1h DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN oi_change_4h DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN oi_change_24h DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN oi_direction VARCHAR(24);
ALTER TABLE agent_contract_candidate_score ADD COLUMN snapshot_funding_rate DECIMAL(18, 12);
ALTER TABLE agent_contract_candidate_score ADD COLUMN funding_rate_24h DECIMAL(18, 12);
ALTER TABLE agent_contract_candidate_score ADD COLUMN funding_acceleration DECIMAL(18, 12);
ALTER TABLE agent_contract_candidate_score ADD COLUMN funding_trend VARCHAR(24);
ALTER TABLE agent_contract_candidate_score ADD COLUMN funding_extreme BOOLEAN;
ALTER TABLE agent_contract_candidate_score ADD COLUMN rs_1h DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN rs_4h DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN rs_24h DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN snapshot_volume_spike_ratio DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN snapshot_volume_mcap_ratio DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN snapshot_spread_bps DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN ath_price DECIMAL(18, 12);
ALTER TABLE agent_contract_candidate_score ADD COLUMN distance_to_ath_pct DECIMAL(18, 8);
ALTER TABLE agent_contract_candidate_score ADD COLUMN breakout_level VARCHAR(24);
ALTER TABLE agent_contract_candidate_score ADD COLUMN news_risk_level VARCHAR(24);
ALTER TABLE agent_contract_candidate_score ADD COLUMN signal_type VARCHAR(32);
ALTER TABLE agent_contract_candidate_score ADD COLUMN market_structure VARCHAR(64);
ALTER TABLE agent_contract_candidate_score ADD COLUMN snapshot_market_risk_level VARCHAR(24);

CREATE TABLE candidate_future_outcome (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    horizon_minutes INT NOT NULL,
    maturity_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    future_timestamp TIMESTAMP NULL,
    future_price DECIMAL(18, 12) NULL,
    raw_return DECIMAL(18, 12) NULL,
    directional_return DECIMAL(18, 12) NULL,
    max_upside DECIMAL(18, 12) NULL,
    max_downside DECIMAL(18, 12) NULL,
    directional_mfe DECIMAL(18, 12) NULL,
    directional_mae DECIMAL(18, 12) NULL,
    error_reason TEXT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_candidate_future_outcome_snapshot_horizon UNIQUE (snapshot_id, horizon_minutes),
    CONSTRAINT fk_candidate_future_outcome_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES agent_contract_candidate_score(id),
    INDEX idx_candidate_future_outcome_due (status, maturity_at),
    INDEX idx_candidate_future_outcome_snapshot (snapshot_id)
);

CREATE INDEX idx_agent_candidate_score_version_rank
    ON agent_contract_candidate_score (scoring_version, snapshot_rank);

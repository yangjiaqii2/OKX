CREATE TABLE shadow_selection (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_name VARCHAR(128) NOT NULL,
    scan_id VARCHAR(64) NOT NULL,
    policy VARCHAR(32) NOT NULL,
    candidate_count INT NOT NULL,
    eligible_count INT NOT NULL,
    selected_inst_id VARCHAR(64),
    selected BOOLEAN NOT NULL,
    reason_code VARCHAR(128),
    signal_venue VARCHAR(32) NOT NULL,
    execution_venue VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_shadow_selection_scan_policy UNIQUE (user_name, scan_id, policy)
);

CREATE INDEX idx_shadow_selection_created ON shadow_selection (user_name, created_at);
